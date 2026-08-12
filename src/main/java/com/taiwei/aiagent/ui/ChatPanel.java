package com.taiwei.aiagent.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.taiwei.aiagent.agent.AgentContext;
import com.taiwei.aiagent.agent.AgentMode;
import com.taiwei.aiagent.agent.AgentService;
import com.taiwei.aiagent.agent.SessionManager;
import com.taiwei.aiagent.agent.context.ContextMentionResolver;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.memory.MemoryCategory;
import com.taiwei.aiagent.memory.MemoryCommandHandler;
import com.taiwei.aiagent.memory.MemoryEntry;
import com.taiwei.aiagent.memory.MemoryManager;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.skill.SkillManager;
import com.taiwei.aiagent.util.TokenCounter;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ChatPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ChatPanel.class);

    private final Project project;
    private final AgentService agentService;
    private final SkillManager skillManager;
    private final MemoryManager memoryManager;
    private final MemoryCommandHandler memoryCommandHandler;

    private JBCefBrowser browser;
    private JBCefJSQuery jsQuery;

    // Mutated both from the JCEF JS-bridge callback thread (handleJsMessage) and from
    // pooled background threads running the AgentListener callbacks in sendMessage(); must be thread-safe.
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private final Runnable settingsChangeListener = this::onSettingsChanged;
    private volatile boolean isCompressing = false;

    // P2: aggregate streamed content chunks and flush to JS at most once per window,
    // instead of one executeJavaScript() call per LLM token/delta.
    private static final long CONTENT_FLUSH_WINDOW_MS = 75;
    private final ScheduledExecutorService contentFlushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-agent-content-flush");
        t.setDaemon(true);
        return t;
    });
    private final StringBuilder pendingContentBuffer = new StringBuilder();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    public ChatPanel(Project project) {
        this.project = project;
        this.agentService = project.getService(AgentService.class);
        this.skillManager = SkillManager.getInstance(project);
        this.memoryManager = MemoryManager.getInstance(project);
        this.memoryCommandHandler = new MemoryCommandHandler(memoryManager);
        this.agentService.setCompressionListener(new AgentService.CompressionListener() {
            @Override
            public void onCompressed(int beforeTokens, int afterTokens) {
                SwingUtilities.invokeLater(() -> {
                    int savedPercent = beforeTokens > 0 ? (int) ((1.0 - (double) afterTokens / beforeTokens) * 100) : 0;
                    String json = "{\"before\":" + beforeTokens + ",\"after\":" + afterTokens + ",\"percent\":" + savedPercent + "}";
                    pushToJs("showCompressNotification", escapeJsString(json));
                    pushToJs("updateCompressedTokenCount", String.valueOf(afterTokens));
                    pushToJs("enableManualCompress", "");
                    isCompressing = false;
                });
            }

            @Override
            public void onCompressionStarted() {
                SwingUtilities.invokeLater(() -> {
                    pushToJs("showNotification", escapeJsString("正在摘要对话..."));
                });
            }

            @Override
            public void onCompressionFailed(String reason) {
                SwingUtilities.invokeLater(() -> {
                    pushToJs("showCompressFailedNotification", escapeJsString(reason));
                    pushToJs("enableManualCompress", "");
                    isCompressing = false;
                });
            }
        });
        AiAgentSettings.getInstance().addChangeListener(settingsChangeListener);
        initUI();
        EditorChatBridge.getInstance(project).register(this);
    }

    private void initUI() {
        setLayout(new BorderLayout());
        initBrowser();
        add(browser.getComponent(), BorderLayout.CENTER);
        pushInitialData();
    }

    private void initBrowser() {
        browser = new JBCefBrowser();
        setupJsBridge();

        String theme = JBColor.isBright() ? "light" : "dark";
        String html = buildHtmlContent(theme);
        browser.loadHTML(html);

        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser cefBrowser,
                                             boolean isLoading,
                                             boolean canGoBack,
                                             boolean canGoForward) {
                if (!isLoading) {
                    pushInitialData();
                }
            }
        }, browser.getCefBrowser());
    }

    private void setupJsBridge() {
        jsQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        jsQuery.addHandler(request -> {
            handleJsMessage(request);
            return new JBCefJSQuery.Response("ok");
        });
    }

    // ========== HTML Content Assembly ==========

    private String buildHtmlContent(String theme) {
        String html = loadResource("/html/chat.html");
        String css = loadResource("/html/css/chat.css");
        String markdownJs = loadResource("/html/js/markdown.js");
        String chatJs = loadResource("/html/js/chat.js");

        String injection = "window.taiweiQuery = function(msg) { " +
                jsQuery.inject("msg") + " };";

        html = html.replace("__CSS__", css);
        html = html.replace("__MARKDOWN_JS__", markdownJs);
        html = html.replace("__CHAT_JS__", injection + "\n" + chatJs);
        html = html.replace("__THEME__", theme);
        return html;
    }

    private static String loadResource(String path) {
        try (InputStream is = ChatPanel.class.getResourceAsStream(path)) {
            if (is == null) return "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            LOG.warn("Failed to load resource: " + path, e);
            return "";
        }
    }

    // ========== Push initial data to JS ==========

    private void pushInitialData() {
        pushSessionListToJs();
        pushModelListToJs();
        pushHistoryToJs();
        pushModeToJs();
        pushSkillsCountToJs();
        pushMemoriesCountToJs();
        pushVisionCapableToJs();
        pushTokenBudgetToJs();
    }

    private void pushSkillsCountToJs() {
        int count = skillManager.getSkillCount();
        pushToJs("updateSkillsCount", String.valueOf(count));
    }

    private void pushMemoriesCountToJs() {
        pushToJs("updateMemoriesCount", String.valueOf(memoryManager.getMemoryCount()));
    }

    private void pushMentionSuggestionsToJs(String query) {
        List<ContextMentionResolver.MentionSuggestion> suggestions =
                ContextMentionResolver.getSuggestions(query);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) json.append(",");
            ContextMentionResolver.MentionSuggestion s = suggestions.get(i);
            json.append("{\"keyword\":\"")
                    .append(s.getKeyword())
                    .append("\",\"description\":\"")
                    .append(escapeJsString(s.getDescription()).replace("\"", ""))
                    .append("\",\"type\":\"")
                    .append(s.getType())
                    .append("\"}");
        }
        json.append("]");
        pushToJs("updateMentionSuggestions", escapeJsString(json.toString()));
    }

    private void openMemoryManager() {
        SwingUtilities.invokeLater(() -> {
            MemoryManagerDialog dialog = new MemoryManagerDialog(project);
            dialog.getWindow().addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    pushMemoriesCountToJs();
                }
            });
            dialog.show();
        });
    }

    private void openMcpSettings() {
        SwingUtilities.invokeLater(() -> {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "com.taiwei.aiagent.settings.mcp");
        });
    }

    private void openToolManager() {
        SwingUtilities.invokeLater(() -> {
            new ToolManagerDialog(project).show();
        });
    }

    private void openImageInBrowser(com.google.gson.JsonObject data) {
        String url = data.has("url") ? data.get("url").getAsString() : null;
        if (url == null || url.isEmpty()) {
            pushToJs("showNotification", escapeJsString("图像打开失败：无可用链接"));
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                com.intellij.ide.BrowserUtil.browse(url);
            } catch (Exception e) {
                LOG.error("图像打开失败", e);
                pushToJs("showNotification", escapeJsString("图像打开失败: " + e.getMessage()));
            }
        });
    }

    private void regenerateImage(com.google.gson.JsonObject data) {
        String toolCallId = data.has("toolCallId") ? data.get("toolCallId").getAsString() : "";
        String imgElId = data.has("imgElId") ? data.get("imgElId").getAsString() : "";
        String btnId = data.has("btnId") ? data.get("btnId").getAsString() : "";
        String prompt = data.has("prompt") ? data.get("prompt").getAsString() : "";
        String size = data.has("size") ? data.get("size").getAsString() : "";

        if (prompt.isEmpty()) {
            pushToJs("updateGeneratedImage",
                    escapeJsString(imgElId) + "," + escapeJsString(btnId) + "," + escapeJsString("图像重新生成失败：找不到原始提示词"));
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            com.google.gson.JsonObject args = new com.google.gson.JsonObject();
            args.addProperty("prompt", prompt);
            if (!size.isEmpty()) args.addProperty("size", size);
            args.addProperty("n", 1);

            String result = new com.taiwei.aiagent.tool.impl.ImageGenerationTool().execute(args.toString());
            pushToJs("updateGeneratedImage",
                    escapeJsString(imgElId) + "," + escapeJsString(btnId) + "," + escapeJsString(result));
        });
    }

    public void submitExternalPrompt(String prompt) {
        String sessionId = agentService.getActiveSessionId();
        if (sessionId == null) {
            createNewSession();
            sessionId = agentService.getActiveSessionId();
            if (sessionId == null) return;
        }
        sendMessage(prompt, null);
    }

    public void insertExternalInputText(String text) {
        pushToJs("setInputText", escapeJsString(text));
    }

    private void pushMemoriesListToJs(List<MemoryEntry> memories) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (MemoryEntry memory : memories) {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("id", memory.getId());
            obj.addProperty("content", memory.getContent());
            obj.addProperty("category", memory.getCategory().name());
            com.google.gson.JsonArray tags = new com.google.gson.JsonArray();
            memory.getTags().forEach(tags::add);
            obj.add("tags", tags);
            obj.addProperty("importance", memory.getImportance());
            obj.addProperty("accessCount", memory.getAccessCount());
            obj.addProperty("createdAt", memory.getCreatedAt());
            obj.addProperty("updatedAt", memory.getUpdatedAt());
            obj.addProperty("lastAccessedAt", memory.getLastAccessedAt());
            array.add(obj);
        }
        pushToJs("updateMemoriesList", array.toString());
    }

    private void rememberMemory(com.google.gson.JsonObject data) {
        try {
            String content = data.get("content").getAsString();
            MemoryCategory category = parseMemoryCategory(
                    data.has("category") ? data.get("category").getAsString() : null);
            List<String> tags = new ArrayList<>();
            if (data.has("tags")) {
                for (com.google.gson.JsonElement el : data.getAsJsonArray("tags")) {
                    tags.add(el.getAsString());
                }
            }
            int importance = data.has("importance") ? data.get("importance").getAsInt() : 5;
            memoryManager.remember(content, category, tags, importance);
            pushMemoriesCountToJs();
        } catch (Exception e) {
            LOG.warn("Failed to remember memory", e);
            pushError("记忆保存失败: " + e.getMessage());
        }
    }

    private void forgetMemory(String id) {
        memoryManager.forget(id);
        pushMemoriesCountToJs();
    }

    private static MemoryCategory parseMemoryCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MemoryCategory.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void openSkillManager() {
        SwingUtilities.invokeLater(() -> {
            SkillManagerDialog dialog = new SkillManagerDialog(project);
            dialog.getWindow().addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    pushSkillsCountToJs();
                }
            });
            dialog.show();
        });
    }

    private void pushSkillsListToJs(List<com.taiwei.aiagent.skill.Skill> skills) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (com.taiwei.aiagent.skill.Skill skill : skills) {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("name", skill.getName());
            obj.addProperty("description", skill.getDescription());
            com.google.gson.JsonArray tags = new com.google.gson.JsonArray();
            skill.getTags().forEach(tags::add);
            obj.add("tags", tags);
            array.add(obj);
        }
        pushToJs("updateSkillsList", array.toString());
    }

    private void pushSkillViewToJs(String name) {
        java.util.Optional<com.taiwei.aiagent.skill.Skill> found = skillManager.getSkill(name);
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        if (found.isPresent()) {
            com.taiwei.aiagent.skill.Skill skill = found.get();
            obj.addProperty("name", skill.getName());
            obj.addProperty("description", skill.getDescription());
            com.google.gson.JsonArray tags = new com.google.gson.JsonArray();
            skill.getTags().forEach(tags::add);
            obj.add("tags", tags);
            obj.addProperty("content", skill.getContent());
        }
        pushToJs("updateSkillView", obj.toString());
    }

    private void addSkill(String fileName, String content) {
        try {
            skillManager.addSkill(fileName, content);
            pushSkillsListToJs(skillManager.listSkills());
            pushSkillsCountToJs();
        } catch (Exception e) {
            LOG.warn("Failed to add skill: " + fileName, e);
            pushError("添加 Skill 失败: " + e.getMessage());
        }
    }

    private void removeSkill(String name) {
        try {
            skillManager.removeSkill(name);
            pushSkillsListToJs(skillManager.listSkills());
            pushSkillsCountToJs();
        } catch (Exception e) {
            LOG.warn("Failed to remove skill: " + name, e);
            pushError("删除 Skill 失败: " + e.getMessage());
        }
    }

    private void pushModeToJs() {
        AgentContext ctx = agentService.getContext();
        String mode = ctx != null ? ctx.getMode().toJsValue() : "build";
        pushToJs("updateMode", escapeJsString(mode));
    }

    /**
     * 用户点击模式徽章触发：直接切换当前活跃会话的模式（不经过 Agent 循环）
     */
    private void setMode(String modeStr) {
        AgentContext ctx = agentService.getContext();
        if (ctx == null) return;
        ctx.setMode(AgentMode.fromString(modeStr));
        agentService.getSessionManager().saveState();
        pushModeToJs();
    }

    private void pushSessionListToJs() {
        List<SessionManager.SessionInfo> sessions = agentService.listSessions();
        String activeId = agentService.getActiveSessionId();

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < sessions.size(); i++) {
            if (i > 0) json.append(",");
            SessionManager.SessionInfo info = sessions.get(i);
            String title = info.getTitle() != null && !info.getTitle().isEmpty()
                    ? info.getTitle() : "\u65b0\u4f1a\u8bdd";
            json.append("{\"id\":")
                    .append(escapeJsString(info.getId()))
                    .append(",\"title\":")
                    .append(escapeJsString(title))
                    .append(",\"active\":")
                    .append(info.getId().equals(activeId))
                    .append("}");
        }
        json.append("]");

        String js = "if(typeof updateSessionList==='function'){updateSessionList(" + json + "," + escapeJsString(activeId) + ");}";
        CefBrowser cef = browser.getCefBrowser();
        if (cef != null) {
            cef.executeJavaScript(js, "taiwei-push", 0);
        }
    }

    private void pushModelListToJs() {
        List<AiAgentSettings.ModelConfig> configs =
                AiAgentSettings.getInstance().getModelConfigs();
        int activeIndex = AiAgentSettings.getInstance().getActiveModelIndex();

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < configs.size(); i++) {
            if (i > 0) json.append(",");
            AiAgentSettings.ModelConfig config = configs.get(i);
            String displayName = config.name.isEmpty() ? config.modelName : config.name;
            json.append("{\"name\":")
                    .append(escapeJsString(displayName))
                    .append(",\"active\":")
                    .append(i == activeIndex)
                    .append("}");
        }
        json.append("]");

        String js = "if(typeof updateModelList==='function'){updateModelList(" + json + "," + activeIndex + ");}";
        CefBrowser cef = browser.getCefBrowser();
        if (cef != null) {
            cef.executeJavaScript(js, "taiwei-push", 0);
        }
    }

    // ========== JS → Java Message Handling ==========

    private void handleJsMessage(String request) {
        try {
            com.google.gson.JsonObject json =
                    com.google.gson.JsonParser.parseString(request).getAsJsonObject();
            String action = json.get("action").getAsString();
            com.google.gson.JsonObject data = json.has("data")
                    ? json.getAsJsonObject("data") : new com.google.gson.JsonObject();

            switch (action) {
                case "sendMessage":
                    String content = data.has("content") ? data.get("content").getAsString() : "";
                    List<ChatMessage.ImageContent> images = null;
                    if (data.has("images") && data.get("images").isJsonArray()
                            && data.getAsJsonArray("images").size() > 0) {
                        // 图片输入前校验当前模型是否支持视觉
                        if (!isActiveModelVisionCapable()) {
                            pushToJs("showNotification",
                                    escapeJsString("Current model does not support image input."));
                        } else {
                            images = new ArrayList<>();
                            for (com.google.gson.JsonElement el : data.getAsJsonArray("images")) {
                                com.google.gson.JsonObject imgObj = el.getAsJsonObject();
                                String base64 = imgObj.get("base64").getAsString();
                                String mimeType = imgObj.get("mimeType").getAsString();
                                images.add(new ChatMessage.ImageContent(base64, mimeType));
                            }
                        }
                    }
                    sendMessage(content, images);
                    break;
                case "createSession":
                    createNewSession();
                    break;
                case "switchSession":
                    switchToSession(data.get("sessionId").getAsString());
                    break;
                case "deleteSession":
                    deleteSession(data.get("sessionId").getAsString());
                    break;
                case "clearChat":
                    clearChat();
                    break;
                case "selectModel":
                    int modelIndex = data.get("index").getAsInt();
                    AiAgentSettings.getInstance().setActiveModelIndex(modelIndex);
                    // 保存到当前会话
                    AgentContext currentCtx = agentService.getContext();
                    if (currentCtx != null) {
                        currentCtx.setModelIndex(modelIndex);
                    }
                    pushVisionCapableToJs();
                    pushTokenBudgetToJs();
                    break;
                case "getSessions":
                    pushSessionListToJs();
                    break;
                case "getModels":
                    pushModelListToJs();
                    break;
                case "runCommand":
                    // 用户点击了危险命令的运行按钮
                    String toolCallId = data.get("toolCallId").getAsString();
                    onUserApproveCommand(toolCallId);
                    break;
                case "stopGeneration":
                    stopGeneration();
                    break;
                case "manualCompress":
                    triggerManualCompress();
                    break;
                case "setMode":
                    setMode(data.get("mode").getAsString());
                    break;
                case "openSkillManager":
                    openSkillManager();
                    break;
                case "listSkills":
                    pushSkillsListToJs(skillManager.listSkills());
                    break;
                case "searchSkills":
                    String query = data.has("query") ? data.get("query").getAsString() : "";
                    pushSkillsListToJs(skillManager.searchSkills(query));
                    break;
                case "viewSkill":
                    pushSkillViewToJs(data.get("name").getAsString());
                    break;
                case "addSkill":
                    addSkill(data.get("fileName").getAsString(), data.get("content").getAsString());
                    break;
                case "removeSkill":
                    removeSkill(data.get("name").getAsString());
                    break;
                case "openMemoryManager":
                    openMemoryManager();
                    break;
                case "openMcpSettings":
                    openMcpSettings();
                    break;
                case "openToolManager":
                    openToolManager();
                    break;
                case "listMemories":
                    String memCategory = data.has("category") ? data.get("category").getAsString() : null;
                    pushMemoriesListToJs(memoryManager.list(parseMemoryCategory(memCategory), 100));
                    break;
                case "searchMemories":
                    String memQuery = data.has("query") ? data.get("query").getAsString() : "";
                    pushMemoriesListToJs(memoryManager.recall(memQuery, 20));
                    break;
                case "rememberMemory":
                    rememberMemory(data);
                    break;
                case "forgetMemory":
                    forgetMemory(data.get("id").getAsString());
                    break;
                case "getMentionSuggestions":
                    String mentionQuery = data.has("query") ? data.get("query").getAsString() : "";
                    pushMentionSuggestionsToJs(mentionQuery);
                    break;
                case "openImage":
                    openImageInBrowser(data);
                    break;
                case "regenerateImage":
                    regenerateImage(data);
                    break;
                default:
                    LOG.warn("Unknown JS action: " + action);
            }
        } catch (Exception e) {
            LOG.error("Error handling JS message: " + request, e);
            pushError("Internal error: " + e.getMessage());
        }
    }

    private boolean isActiveModelVisionCapable() {
        AiAgentSettings.ModelConfig config = AiAgentSettings.getInstance().getActiveModelConfig();
        return config != null && config.visionCapable;
    }

    private void pushVisionCapableToJs() {
        Runnable task = () -> {
            String js = "if(typeof updateVisionCapable==='function'){updateVisionCapable(" + isActiveModelVisionCapable() + ");}";
            CefBrowser cef = browser.getCefBrowser();
            if (cef != null) {
                cef.executeJavaScript(js, "taiwei-push", 0);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    private void pushTokenBudgetToJs() {
        AiAgentSettings.ModelConfig config = AiAgentSettings.getInstance().getActiveModelConfig();
        int contextWindow = config != null && config.contextWindowSize > 0 ? config.contextWindowSize : 200000;
        pushToJs("updateTokenBudget", String.valueOf(contextWindow));
    }

    // ========== Java → JS Push ==========

    private void pushToJs(String func, String data) {
        Runnable task = () -> {
            String js = "if(typeof " + func + "==='function'){" + func + "(" + data + ");}";
            CefBrowser cef = browser.getCefBrowser();
            if (cef != null) {
                cef.executeJavaScript(js, "taiwei-push", 0);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    private void pushContent(String content) {
        synchronized (pendingContentBuffer) {
            pendingContentBuffer.append(content);
        }
        if (!contentFlushExecutor.isShutdown() && flushScheduled.compareAndSet(false, true)) {
            try {
                contentFlushExecutor.schedule(this::flushContentBuffer, CONTENT_FLUSH_WINDOW_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Panel disposed while an agent loop was still streaming; nothing left to render into.
                flushScheduled.set(false);
            }
        }
    }

    /** Scheduled flush: sends the accumulated batch (if any) as a single appendContent call. */
    private void flushContentBuffer() {
        String batch;
        synchronized (pendingContentBuffer) {
            batch = pendingContentBuffer.toString();
            pendingContentBuffer.setLength(0);
        }
        flushScheduled.set(false);
        // A pushContent() between the drain above and set(false) saw flushScheduled==true and
        // didn't schedule; without this re-check its content would sit until the next event.
        boolean leftover;
        synchronized (pendingContentBuffer) {
            leftover = pendingContentBuffer.length() > 0;
        }
        if (leftover && !contentFlushExecutor.isShutdown() && flushScheduled.compareAndSet(false, true)) {
            try {
                contentFlushExecutor.schedule(this::flushContentBuffer, CONTENT_FLUSH_WINDOW_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                flushScheduled.set(false);
            }
        }
        if (!batch.isEmpty()) {
            pushToJs("appendContent", escapeJsString(batch));
        }
    }

    /** Immediately flushes any pending content so onComplete/onError never race a delayed batch. */
    private void flushContentBufferNow() {
        String batch;
        synchronized (pendingContentBuffer) {
            if (pendingContentBuffer.length() == 0) return;
            batch = pendingContentBuffer.toString();
            pendingContentBuffer.setLength(0);
        }
        pushToJs("appendContent", escapeJsString(batch));
    }

    private void pushToolCallStart(String name, String args) {
        return;
    }

    private void pushToolCallEnd(String name, String result) {
        return;
    }

    private void pushComplete() {
        flushContentBufferNow();
        pushToJs("onComplete", "");
    }

    private void pushError(String error) {
        flushContentBufferNow();
        pushToJs("onError", escapeJsString(error));
    }

    private void pushUsageToJs(LlmResponse.Usage usage, long elapsedMs) {
        String json = "{\"usage\":{\"promptTokens\":" + usage.getPromptTokens()
                + ",\"completionTokens\":" + usage.getCompletionTokens()
                + ",\"totalTokens\":" + usage.getTotalTokens() + "}"
                + ",\"elapsedMs\":" + elapsedMs + "}";
        pushToJs("updateTokenUsage", json);
    }

    private void pushHistoryToJs() {
        AgentContext ctx = agentService.getContext();
        if (ctx == null) return;

        List<ChatMessage> messages = ctx.getConversation().getMessages();

        // 第一步：过滤出 user 和 assistant（有内容）消息，合并连续的 assistant
        List<SimpleEntry> displayEntries = new ArrayList<>();
        SimpleEntry pendingAssistant = null;

        for (ChatMessage msg : messages) {
            String role = msg.getRole();
            if ("user".equals(role)) {
                pendingAssistant = null;
                SimpleEntry userEntry = new SimpleEntry("user", msg.getContent());
                // 携带图片数据（data:mimeType;base64,base64Data），供前端重新渲染缩略图
                if (msg.getImageContents() != null && !msg.getImageContents().isEmpty()) {
                    userEntry.images = new ArrayList<>();
                    for (ChatMessage.ImageContent img : msg.getImageContents()) {
                        userEntry.images.add("data:" + img.getMimeType() + ";base64," + img.getBase64Data());
                    }
                }
                displayEntries.add(userEntry);
            } else if ("assistant".equals(role)
                    && msg.getContent() != null && !msg.getContent().isEmpty()) {
                if (pendingAssistant != null) {
                    // 合并到上一条 assistant：追加 content
                    pendingAssistant.content += msg.getContent();
                } else {
                    pendingAssistant = new SimpleEntry("assistant", msg.getContent());
                    displayEntries.add(pendingAssistant);
                }
            }
        }

        // 第二步：构建 JSON 数组
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (SimpleEntry entry : displayEntries) {
            if (!first) json.append(",");
            json.append("{\"role\":")
                    .append(escapeJsString(entry.role))
                    .append(",\"content\":")
                    .append(escapeJsString(entry.content));
            if (entry.images != null && !entry.images.isEmpty()) {
                json.append(",\"images\":[");
                for (int k = 0; k < entry.images.size(); k++) {
                    if (k > 0) json.append(",");
                    json.append("{\"dataUrl\":").append(escapeJsString(entry.images.get(k))).append("}");
                }
                json.append("]");
            }
            json.append("}");
            first = false;
        }

        // 如果当前会话正在处理中，追加流式内容到前端显示
        String activeId = agentService.getActiveSessionId();
        SessionState state = sessionStates.get(activeId);
        boolean isActiveProcessing = state != null && state.isProcessing;
        if (isActiveProcessing && state.accumulatedContent.length() > 0) {
            if (!first) json.append(",");
            json.append("{\"role\":\"assistant\",\"content\":")
                    .append(escapeJsString(state.accumulatedContent.toString()))
                    .append("}");
        }

        json.append("]");

        int totalTokens = 0;
        try {
            String modelName = AiAgentSettings.getInstance().getModel();
            totalTokens = TokenCounter.countTokens(messages, modelName);
        } catch (Exception e) {
            LOG.warn("计算历史 Token 数失败", e);
        }

        pushToJs("loadHistory", escapeJsString(json.toString()) + "," + isActiveProcessing + "," + totalTokens);
    }

    /**
     * 简易键值对，用于构建推送到前端的显示条目
     */
    private static class SimpleEntry {
        final String role;
        String content;
        List<String> images;

        SimpleEntry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static String escapeJsString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                case '&':  sb.append("\\u0026"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append("\"").toString();
    }

    // ========== Pending Command Management ==========

    /**
     * 待审批的命令
     */
    private static class PendingCommand {
        final String toolCallId;
        final String command;
        final boolean isDangerous;

        PendingCommand(String toolCallId, String command, boolean isDangerous) {
            this.toolCallId = toolCallId;
            this.command = command;
            this.isDangerous = isDangerous;
        }
    }

    /**
     * JS 端点击运行按钮后触发
     * 仅发送审批信号，实际命令执行由 AgentService 通过 RunCommandTool 在终端中完成
     */
    private void onUserApproveCommand(String toolCallId) {
        PendingCommand pc = pendingCommands.remove(toolCallId);
        if (pc == null) {
            LOG.warn("未找到待审批的命令: " + toolCallId);
            return;
        }

        // 隐藏运行按钮，显示进度
        pushToJs("hideRunButton", escapeJsString(toolCallId));
        pushToJs("showProgress",
                escapeJsString(toolCallId) + "," + escapeJsString("命令已批准，在终端执行中..."));

        // 发送审批通过信号，AgentService 收到后会自动通过 RunCommandTool 在终端执行
        agentService.getApprovalManager().setResult(toolCallId, "__APPROVED__");
    }

    // ========== Stop Generation ==========

    private void stopGeneration() {
        String sessionId = agentService.getActiveSessionId();
        agentService.stopGeneration(sessionId);
    }

    // ========== Send Message ==========

    private void sendMessage(String text, List<ChatMessage.ImageContent> images) {
        boolean hasText = text != null && !text.trim().isEmpty();
        boolean hasImages = images != null && !images.isEmpty();
        if (!hasText && !hasImages) {
            // Nothing to send. If the frontend already switched to the "thinking" state
            // before invoking us, resolve it now so the UI doesn't get stuck spinning.
            pushComplete();
            return;
        }
        String messageText = hasText ? text : "";

        String sessionId = agentService.getActiveSessionId();
        if (sessionId == null) {
            pushComplete();
            return;
        }

        // 记忆相关的指令（记住/忘了/我上次说的...）在本地直接处理，不进入 LLM 对话流程
        // 纯图片消息没有文本，不可能匹配记忆指令，故仅在有文本时尝试
        if (hasText) {
            Optional<String> memoryReply = memoryCommandHandler.tryHandle(text);
            if (memoryReply.isPresent()) {
                respondWithMemoryCommand(sessionId, text, memoryReply.get());
                return;
            }
        }

        SessionState sessionState = sessionStates.computeIfAbsent(sessionId, k -> new SessionState());
        if (sessionState.isProcessing) {
            pushComplete();
            return;
        }

        sessionState.isProcessing = true;
        sessionState.accumulatedContent = new StringBuilder();
        sessionState.startTime = System.currentTimeMillis();
        sessionState.lastUsage = null;
        sessionState.chatEntries.add(ChatEntry.user(messageText));

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            agentService.sendMessage(sessionId, messageText, images, new AgentService.AgentListener() {

                @Override
                public void onThinking() {
                    // Agent 循环每次调用 LLM 前触发（可能多次），保持前端 thinking 指示器常驻
                    List<ChatEntry> entries = sessionState.chatEntries;
                    if (entries.isEmpty() || entries.get(entries.size() - 1).type != ChatEntry.Type.THINKING) {
                        entries.add(ChatEntry.thinking());
                    }
                    if (sessionId.equals(agentService.getActiveSessionId())) {
                        pushToJs("showThinking", "");
                    }
                }

                @Override
                public void onContent(String content) {
                    sessionState.accumulatedContent.append(content);

                    boolean replaced = false;
                    for (int i = sessionState.chatEntries.size() - 1; i >= 0; i--) {
                        if (sessionState.chatEntries.get(i).type == ChatEntry.Type.THINKING) {
                            sessionState.chatEntries.set(i, ChatEntry.assistant(sessionState.accumulatedContent.toString()));
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced) {
                        boolean foundAssistant = false;
                        for (int i = sessionState.chatEntries.size() - 1; i >= 0; i--) {
                            if (sessionState.chatEntries.get(i).type == ChatEntry.Type.ASSISTANT) {
                                sessionState.chatEntries.get(i).content = sessionState.accumulatedContent.toString();
                                foundAssistant = true;
                                break;
                            }
                        }
                        if (!foundAssistant) {
                            // 首次收到内容，创建 ASSISTANT 条目
                            sessionState.chatEntries.add(ChatEntry.assistant(sessionState.accumulatedContent.toString()));
                        }
                    }

                    // 仅当当前活跃会话是本 listener 对应的会话时才推送到前端
                    if (sessionId.equals(agentService.getActiveSessionId())) {
                        pushContent(content);
                    }
                }

                @Override
                public void onToolCallStart(String toolCallId, String toolName, String arguments) {
                    sessionState.chatEntries.add(ChatEntry.toolCall(toolCallId, toolName, arguments));

                    if (sessionId.equals(agentService.getActiveSessionId())) {
                        pushToJs("showProgress",
                                escapeJsString(toolCallId) + "," + escapeJsString("执行 " + toolName + "..."));
                    }
                }

                @Override
                public void onToolCallEnd(String toolCallId, String toolName, String result) {
                    for (int i = sessionState.chatEntries.size() - 1; i >= 0; i--) {
                        ChatEntry entry = sessionState.chatEntries.get(i);
                        if (entry.type == ChatEntry.Type.TOOL_CALL
                                && toolCallId.equals(entry.toolCallId)
                                && entry.toolResult == null) {
                            entry.toolResult = result;
                            break;
                        }
                    }

                    pushToJs("showProgress",
                            escapeJsString(toolCallId) + "," + escapeJsString("\u2705 \u5b8c\u6210"));
                    // 延迟隐藏进度条，让用户看到完成状态
                    Timer hideTimer = new Timer(300, e -> {
                        pushToJs("hideProgress", escapeJsString(toolCallId));
                    });
                    hideTimer.setRepeats(false);
                    hideTimer.start();

                    // 图像生成结果：在对话中内联展示图像
                    if (result != null && result.contains("\"__type\":\"generated_image\"")) {
                        if (sessionId.equals(agentService.getActiveSessionId())) {
                            pushToJs("showGeneratedImage",
                                    escapeJsString(toolCallId) + "," + escapeJsString(result));
                        }
                    }
                }

                @Override
                public void onCommandApproval(String toolCallId, String command, boolean isDangerous) {
                    sessionState.chatEntries.add(ChatEntry.toolCall(toolCallId, "run_command", command));

                    if (!sessionId.equals(agentService.getActiveSessionId())) return;

                    if (isDangerous) {
                        // 危险命令：显示运行按钮，等待用户点击
                        // Converge the write onto the EDT so it's ordered consistently with the
                        // remove() in onUserApproveCommand(), which also runs off the EDT (JS-bridge thread).
                        SwingUtilities.invokeLater(() ->
                                pendingCommands.put(toolCallId, new PendingCommand(toolCallId, command, true)));
                        pushToJs("showRunButton",
                                escapeJsString(toolCallId) + "," + escapeJsString(command));
                    } else {
                        // 安全命令：显示进度条，直接执行
                        pushToJs("showProgress",
                                escapeJsString(toolCallId) + "," + escapeJsString("执行中..."));
                    }
                }

                @Override
                public void onCommandProgress(String toolCallId, String status) {
                    if (sessionId.equals(agentService.getActiveSessionId())) {
                        pushToJs("showProgress",
                                escapeJsString(toolCallId) + "," + escapeJsString(status));
                    }
                }

                @Override
                public void onCommandResult(String toolCallId, String result) {
                    PendingCommand pc = pendingCommands.remove(toolCallId);
                    boolean isDangerous = pc != null;

                    if (isDangerous) {
                        pushToJs("hideRunButton", escapeJsString(toolCallId));
                    } else {
                        pushToJs("showProgress",
                                escapeJsString(toolCallId) + "," + escapeJsString("\u2705 \u5b8c\u6210"));
                        pushToJs("hideRunButton", escapeJsString(toolCallId));
                        // 延迟隐藏进度条，让用户看到完成状态
                        Timer hideTimer = new Timer(300, e -> {
                            pushToJs("hideProgress", escapeJsString(toolCallId));
                        });
                        hideTimer.setRepeats(false);
                        hideTimer.start();
                    }

                    // 更新 ChatEntry
                    for (int i = sessionState.chatEntries.size() - 1; i >= 0; i--) {
                        ChatEntry entry = sessionState.chatEntries.get(i);
                        if (entry.type == ChatEntry.Type.TOOL_CALL
                                && toolCallId.equals(entry.toolCallId)
                                && entry.toolResult == null) {
                            entry.toolResult = result;
                            break;
                        }
                    }
                }

                @Override
                public void onUsage(LlmResponse.Usage usage) {
                    sessionState.lastUsage = usage;
                }

                @Override
                public void onModeChanged(String mode) {
                    if (sessionId.equals(agentService.getActiveSessionId())) {
                        pushToJs("updateMode", escapeJsString(mode));
                    }
                }

                @Override
                public void onComplete(String fullResponse) {
                    sessionState.isProcessing = false;
                    sessionState.chatEntries.removeIf(e -> e.type == ChatEntry.Type.THINKING);

                    boolean hasAssistant = false;
                    for (ChatEntry entry : sessionState.chatEntries) {
                        if (entry.type == ChatEntry.Type.ASSISTANT) {
                            hasAssistant = true;
                        }
                    }
                    if (!hasAssistant && fullResponse != null && !fullResponse.isEmpty()) {
                        sessionState.chatEntries.add(ChatEntry.assistant(fullResponse));
                    }

                    // 清理待审批命令（converge onto EDT to avoid racing with onUserApproveCommand's remove()）
                    SwingUtilities.invokeLater(pendingCommands::clear);

                    // 推送 Token 统计和耗时到前端
                    long elapsedMs = System.currentTimeMillis() - sessionState.startTime;
                    if (sessionState.lastUsage != null) {
                        pushUsageToJs(sessionState.lastUsage, elapsedMs);
                    }

                    // 更新会话列表（标题可能已改变）
                    SwingUtilities.invokeLater(() -> pushSessionListToJs());

                    pushComplete();
                }

                @Override
                public void onError(String error) {
                    LOG.warn("ChatPanel onError 回调触发 - sessionId=" + sessionId + ", error=" + error);
                    sessionState.isProcessing = false;
                    sessionState.chatEntries.removeIf(e -> e.type == ChatEntry.Type.THINKING);
                    sessionState.chatEntries.add(ChatEntry.error(error));

                    // 清理待审批命令（converge onto EDT to avoid racing with onUserApproveCommand's remove()）
                    SwingUtilities.invokeLater(pendingCommands::clear);

                    pushToJs("clearAllProgress", "");
                    pushToJs("clearAllRunButtons", "");
                    pushError(error);
                    LOG.info("ChatPanel onError 处理完成，已重置 isProcessing 状态");
                }
            });
        });
    }

    /**
     * 记忆指令（记住/忘了/我上次说的...）本地直接生成回复，不调用 LLM
     */
    private void respondWithMemoryCommand(String sessionId, String userText, String reply) {
        SessionState sessionState = sessionStates.computeIfAbsent(sessionId, k -> new SessionState());
        sessionState.chatEntries.add(ChatEntry.user(userText));
        sessionState.chatEntries.add(ChatEntry.assistant(reply));

        if (sessionId.equals(agentService.getActiveSessionId())) {
            pushContent(reply);
            pushComplete();
        }
        pushMemoriesCountToJs();
    }

    // ========== Manual Compress ==========

    private void triggerManualCompress() {
        if (isCompressing) return;
        isCompressing = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            agentService.manualCompress();
        });
    }

    // ========== Session Management ==========

    private void createNewSession() {
        String newId = agentService.createSession();
        sessionStates.put(newId, new SessionState());
        pushSessionListToJs();
        pushToJs("clearMessages", "");
    }

    private void switchToSession(String sessionId) {
        agentService.switchSession(sessionId);

        // 恢复该会话的模式和模型
        AgentContext ctx = agentService.getContext();
        if (ctx != null) {
            // 恢复模型（如果该会话记录过模型索引）
            if (ctx.getModelIndex() >= 0) {
                AiAgentSettings.getInstance().setActiveModelIndex(ctx.getModelIndex());
            }
        }

        pushHistoryToJs();
        pushSessionListToJs();
        pushModeToJs();
        pushModelListToJs();
    }

    private void deleteSession(String sessionId) {
        sessionStates.remove(sessionId);
        agentService.deleteSession(sessionId);
        pushHistoryToJs();
        pushSessionListToJs();
    }

    private void clearChat() {
        agentService.resetConversation();
        String activeId = agentService.getActiveSessionId();
        SessionState state = sessionStates.get(activeId);
        if (state != null) {
            state.chatEntries.clear();
            state.accumulatedContent = new StringBuilder();
        }
        pushToJs("clearMessages", "");
    }

    private void onSettingsChanged() {
        SwingUtilities.invokeLater(() -> {
            pushModelListToJs();
            pushTokenBudgetToJs();
        });
    }

    @Override
    public void dispose() {
        EditorChatBridge.getInstance(project).unregister(this);
        AiAgentSettings.getInstance().removeChangeListener(settingsChangeListener);
        contentFlushExecutor.shutdownNow();
        if (jsQuery != null) {
            Disposer.dispose(jsQuery);
        }
        if (browser != null) {
            Disposer.dispose(browser);
        }
    }

    // ========== Inner Classes ==========

    /**
     * 按会话追踪的状态：包含聊天条目、处理状态和累积内容
     */
    private static class SessionState {
        List<ChatEntry> chatEntries = Collections.synchronizedList(new ArrayList<>());
        volatile boolean isProcessing = false;
        StringBuilder accumulatedContent = new StringBuilder();
        long startTime;
        LlmResponse.Usage lastUsage;
    }

    private static class ChatEntry {
        enum Type { USER, ASSISTANT, TOOL_CALL, THINKING, ERROR }

        final Type type;
        String content;
        String toolCallId;
        String toolName;
        String toolArgs;
        String toolResult;

        private ChatEntry(Type type, String content) {
            this.type = type;
            this.content = content;
        }

        static ChatEntry user(String content) {
            return new ChatEntry(Type.USER, content);
        }

        static ChatEntry assistant(String content) {
            return new ChatEntry(Type.ASSISTANT, content);
        }

        static ChatEntry thinking() {
            return new ChatEntry(Type.THINKING, null);
        }

        static ChatEntry toolCall(String toolCallId, String name, String args) {
            ChatEntry entry = new ChatEntry(Type.TOOL_CALL, null);
            entry.toolCallId = toolCallId;
            entry.toolName = name;
            entry.toolArgs = args;
            return entry;
        }

        static ChatEntry error(String content) {
            return new ChatEntry(Type.ERROR, content);
        }
    }
}
