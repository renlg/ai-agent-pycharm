package com.taiwei.aiagent.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.agent.context.ContextMentionResolver;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.llm.LlmStreamListener;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.tool.ToolRegistry;
import com.taiwei.aiagent.tool.impl.RunCommandTool;
import com.taiwei.aiagent.util.TokenCounter;
import com.taiwei.aiagent.util.I18nUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Agent 核心服务
 * 实现 Agent 循环：接收用户消息 → 调用 LLM → 执行工具 → 循环直到得到最终回答
 * 支持多会话管理
 * 支持危险命令审批流程和 IntelliJ Terminal API 执行
 */
public class AgentService implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentService.class);

    private final SessionManager sessionManager;
    private final ApprovalManager approvalManager = new ApprovalManager();
    private final Project project;
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-agent-tool-" + r.hashCode());
        t.setDaemon(true);
        return t;
    });

    /**
     * 命令审批管理器
     */
    public static class ApprovalManager {
        private final Map<String, CommandApproval> pendingApprovals = new ConcurrentHashMap<>();

        public static class CommandApproval {
            final CountDownLatch latch = new CountDownLatch(1);
            volatile boolean approved = false;
            volatile String result = null;
        }

        public CommandApproval register(String toolCallId) {
            CommandApproval ca = new CommandApproval();
            pendingApprovals.put(toolCallId, ca);
            return ca;
        }

        // NOTE: approval is granted via setResult() (which releases the latch). A separate
        // approve() that only set the flag without counting down the latch used to exist here;
        // any caller using it would have left the agent loop blocked until the 300s timeout.

        public void setResult(String toolCallId, String result) {
            CommandApproval ca = pendingApprovals.remove(toolCallId);
            if (ca != null) {
                ca.approved = true;
                ca.result = result;
                ca.latch.countDown();
            }
        }

        public void reject(String toolCallId) {
            CommandApproval ca = pendingApprovals.remove(toolCallId);
            if (ca != null) {
                ca.approved = false;
                ca.latch.countDown();
            }
        }
    }

    public ApprovalManager getApprovalManager() {
        return approvalManager;
    }

    /**
     * Agent 事件监听器
     */
    public interface AgentListener {
        /** LLM 开始思考 */
        void onThinking();

        /** 收到文本内容片段 */
        void onContent(String content);

        /** 开始调用工具 */
        void onToolCallStart(String toolCallId, String toolName, String arguments);

        /** 工具调用完成 */
        void onToolCallEnd(String toolCallId, String toolName, String result);

        /** 命令需要审批（危险命令） */
        default void onCommandApproval(String toolCallId, String command, boolean isDangerous) {}

        /** 命令执行进度更新 */
        default void onCommandProgress(String toolCallId, String status) {}

        /** 命令执行结果 */
        default void onCommandResult(String toolCallId, String result) {}

        /** Agent 模式已切换（"plan" / "build"） */
        default void onModeChanged(String mode) {}

        /** Agent 完成回答 */
        void onComplete(String fullResponse);

        /** Token 使用统计（多次迭代累加后在 onComplete 前调用） */
        default void onUsage(LlmResponse.Usage usage) {}

        /** 发生错误 */
        void onError(String error);
    }

    /**
     * 压缩事件监听器
     */
    public interface CompressionListener {
        void onCompressed(int beforeTokens, int afterTokens);
        default void onCompressionStarted() {}
        default void onCompressionFailed(String reason) {}
    }

    private volatile CompressionListener compressionListener;

    public void setCompressionListener(CompressionListener listener) {
        this.compressionListener = listener;
    }

    public AgentService(Project project) {
        this.project = project;
        this.sessionManager = new SessionManager(project);
        this.sessionManager.loadFromDisk();
    }

    /**
     * 获取当前活跃会话的上下文（向后兼容）
     */
    public AgentContext getContext() {
        return sessionManager.getActiveContext();
    }

    /**
     * 获取 SessionManager
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * 创建新会话
     */
    public String createSession() {
        return sessionManager.createSession();
    }

    /**
     * 切换到指定会话
     */
    public void switchSession(String sessionId) {
        sessionManager.switchSession(sessionId);
    }

    /**
     * 获取当前活跃会话 ID
     */
    public String getActiveSessionId() {
        return sessionManager.getActiveSessionId();
    }

    /**
     * 列出所有会话
     */
    public List<SessionManager.SessionInfo> listSessions() {
        return sessionManager.listSessions();
    }

    /**
     * 删除指定会话
     */
    public void deleteSession(String sessionId) {
        sessionManager.deleteSession(sessionId);
    }

    /**
     * 发送用户消息并执行 Agent 循环（使用当前活跃会话）
     */
    public void sendMessage(String userMessage, AgentListener listener) {
        sendMessage(null, userMessage, listener);
    }

    /**
     * 发送用户消息并执行 Agent 循环
     * 拦截 /plan、/build、/init 斜杠命令，不进入正常的 LLM 对话流程
     */
    public void sendMessage(String sessionId, String userMessage, AgentListener listener) {
        sendMessage(sessionId, userMessage, null, listener);
    }

    /**
     * 发送带图片的用户消息并执行 Agent 循环（视觉输入）
     */
    public void sendMessage(String sessionId, String userMessage, List<ChatMessage.ImageContent> images, AgentListener listener) {
        AgentContext ctx;
        if (sessionId != null && !sessionId.isEmpty()) {
            sessionManager.getOrCreateSession(sessionId);
            ctx = sessionManager.getContext(sessionId);
        } else {
            ctx = sessionManager.getActiveContext();
        }

        String trimmed = userMessage == null ? "" : userMessage.trim();

        if ("/plan".equalsIgnoreCase(trimmed) || "/build".equalsIgnoreCase(trimmed)) {
            handleModeSwitch(ctx, "/plan".equalsIgnoreCase(trimmed) ? AgentMode.PLAN : AgentMode.BUILD, listener);
            sessionManager.saveState();
            return;
        }

        if ("/init".equalsIgnoreCase(trimmed)) {
            handleInitCommand(ctx, listener);
            sessionManager.saveState();
            return;
        }

        // 解析 @ 提及，增强用户消息（附加文件内容、项目结构、Git 上下文等）
        String augmentedMessage = ContextMentionResolver.augment(project, userMessage);

        // 检索与本条消息相关的长期记忆，追加到用户消息末尾（而非系统提示词）
        // 这样系统提示词在整个会话内保持字节完全一致，能持续命中前缀缓存（DeepSeek/通义/OpenAI）
        String memoryContext = ctx.getPromptManager().buildRelevantMemoryContext(augmentedMessage);
        String userContent = (memoryContext != null && !memoryContext.isEmpty())
                ? augmentedMessage + I18nUtil.getMessage("agent.memory.contextHeader") + memoryContext
                : augmentedMessage;

        // 添加用户消息到对话历史（使用增强后的消息，包含 @ 引用的上下文及相关记忆）
        ctx.getConversation().addUserMessage(userContent, images);

        // 更新系统提示词（不再注入 memory，保证前缀缓存命中）
        ctx.getConversation().updateSystemPrompt(ctx.getPromptManager().buildSystemPrompt(ctx.getMode()));

        ctx.setStopped(false);
        ctx.setActiveLlmClient(ctx.getLlmClient());

        listener.onThinking();

        // 启动 Agent 循环
        executeAgentLoop(ctx, listener);

        // 消息处理完成后持久化
        ctx.setActiveLlmClient(null);
        sessionManager.saveState();
    }

    /**
     * 处理 /plan、/build 模式切换命令：不发送给 LLM，直接切换模式并提示用户
     */
    private void handleModeSwitch(AgentContext ctx, AgentMode newMode, AgentListener listener) {
        ctx.setMode(newMode);
        listener.onModeChanged(newMode.toJsValue());
        String msg = I18nUtil.getMessage(newMode == AgentMode.PLAN ? "agent.mode.plan" : "agent.mode.build");
        listener.onThinking();
        listener.onContent(msg);
        listener.onComplete(msg);
    }

    /**
     * 处理 /init 命令：扫描项目结构，交给 LLM 生成 AGENTS.md 内容，并直接写入项目根目录
     */
    private void handleInitCommand(AgentContext ctx, AgentListener listener) {
        listener.onThinking();
        try {
            String basePath = project.getBasePath();
            if (basePath == null) {
                listener.onError(I18nUtil.getMessage("agent.init.noProjectPath"));
                return;
            }

            java.nio.file.Path agentsMdPath = java.nio.file.Paths.get(basePath, "AGENTS.md");
            String existing = null;
            if (java.nio.file.Files.exists(agentsMdPath)) {
                try {
                    existing = java.nio.file.Files.readString(agentsMdPath, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    LOG.warn("Failed to read existing AGENTS.md: " + e.getMessage());
                    listener.onContent(I18nUtil.getMessage("agent.init.readExistingFailed", e.getMessage()));
                }
            }

            String scanSummary = com.taiwei.aiagent.agent.init.ProjectScanner.scan(project);

            String initPrompt = ctx.getPromptManager().buildInitPrompt(scanSummary, existing);

            List<ChatMessage> request = new ArrayList<>();
            request.add(ChatMessage.system(I18nUtil.getMessage("agent.init.systemPrompt")));
            request.add(ChatMessage.user(initPrompt));

            // 在调用 LLM 之前先把用户的 /init 记入历史，确保后续调用失败时该消息不会丢失
            ctx.getConversation().addUserMessage("/init");

            ctx.setStopped(false);
            LlmClient llmClient = ctx.getLlmClient();
            ctx.setActiveLlmClient(llmClient);

            ctx.beginLlmRequest();
            LlmResponse response;
            try {
                response = llmClient.chat(request, null);
            } finally {
                ctx.endLlmRequest();
            }
            ctx.setActiveLlmClient(null);

            if (response == null || !response.isSuccess() || response.getContent() == null || response.getContent().isEmpty()) {
                String err = response != null ? response.getErrorMessage() : I18nUtil.getMessage("agent.llm.noContent");
                ctx.getConversation().addAssistantMessage(I18nUtil.getMessage("agent.init.failureNotice", err));
                listener.onError(I18nUtil.getMessage("agent.init.generateFailed", err));
                return;
            }

            String agentsMdContent = response.getContent().trim();
            java.nio.file.Files.writeString(agentsMdPath, agentsMdContent, java.nio.charset.StandardCharsets.UTF_8);
            // The file was written behind the VFS's back; refresh so it shows up (and is current)
            // in the project tree / open editors without a manual sync.
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                            .refreshAndFindFileByIoFile(agentsMdPath.toFile()));

            String summary = I18nUtil.getMessage(existing != null ? "agent.init.updated" : "agent.init.generated", agentsMdPath, agentsMdContent);
            ctx.getConversation().addAssistantMessage(summary);

            if (response.getUsage() != null) {
                listener.onUsage(response.getUsage());
            }
            listener.onContent(summary);
            listener.onComplete(summary);
        } catch (Exception e) {
            LOG.error("/init 执行失败", e);
            ctx.getConversation().addAssistantMessage(I18nUtil.getMessage("agent.init.failureNotice", e.getMessage()));
            listener.onError(I18nUtil.getMessage("agent.init.failed", e.getMessage()));
        }
    }

    /**
     * 检查命令是否匹配危险命令模式
     */
    public boolean isDangerousCommand(String command) {
        List<String> patterns = AiAgentSettings.getInstance().getDangerousCommands();
        String lower = command.toLowerCase().trim();
        for (String pattern : patterns) {
            if (lower.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 停止当前正在进行的生成
     * 同时停止 LLM 调用和正在执行的工具调用
     */
    public void stopGeneration(String sessionId) {
        AgentContext ctx = sessionManager.getContext(sessionId);
        if (ctx != null) {
            ctx.stop();
        }
    }

    /**
     * 检查是否已停止
     */
    public boolean isStopped() {
        AgentContext ctx = sessionManager.getActiveContext();
        return ctx != null && ctx.isStopped();
    }

    /**
     * Called by IntelliJ when the project closes.
     * Shuts down the tool-executor thread pool and closes all cached LLM clients
     * so their underlying HTTP connection pools are not leaked.
     */
    @Override
    public void dispose() {
        toolExecutor.shutdownNow();
        for (SessionManager.SessionInfo info : sessionManager.listSessions()) {
            AgentContext ctx = sessionManager.getContext(info.getId());
            if (ctx != null) {
                ctx.dispose();
            }
        }
        sessionManager.dispose();
    }

    /**
     * Agent 核心循环（流式）
     * 使用 SSE 流式调用 LLM，实时推送内容片段到前端
     * 工具调用在流结束后统一处理，然后继续循环
     * run_command 工具使用 IntelliJ Terminal API + 危险命令审批
     */
    private void executeAgentLoop(AgentContext context, AgentListener listener) {
        LlmClient llmClient = context.getLlmClient();
        // Bracket the whole loop: llmClient is reused across iterations (which can span minutes
        // while waiting on dangerous-command approval), so it must not be closed out from under
        // us if the user switches models/settings mid-loop.
        context.beginLlmRequest();
        try {
            executeAgentLoopInternal(context, listener, llmClient);
        } finally {
            context.endLlmRequest();
        }
    }

    private void executeAgentLoopInternal(AgentContext context, AgentListener listener, LlmClient llmClient) {
        LOG.debug("Agent loop started: model=" + llmClient.getModelName() + ", mode=" + context.getMode());
        ToolRegistry registry = context.getToolRegistry();
        List<Tool> tools = context.getToolsForMode();
        int maxIterations = context.getMaxIterations();

        StringBuilder fullResponse = new StringBuilder();
        int[] totalUsage = {0, 0, 0}; // promptTokens, completionTokens, totalTokens
        int[] lastPromptTokens = {0}; // 最近一次 LLM 返回的实际 promptTokens，用于压缩决策

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            if (context.isStopped()) {
                LOG.debug("Agent loop stopped by user");
                LlmResponse.Usage usage = buildAccumulatedUsage(context, totalUsage);
                if (usage != null) listener.onUsage(usage);
                listener.onComplete(fullResponse.length() > 0 ? fullResponse.toString() : I18nUtil.getMessage("agent.stopped"));
                return;
            }
            LOG.debug("Agent loop iteration " + (iteration + 1));

            // 每次调用 LLM 前都通知 UI 进入思考状态（不只是第一次迭代）
            listener.onThinking();

            checkAndCompress(context, llmClient, lastPromptTokens[0], listener);

            final StringBuilder iterContent = new StringBuilder();
            final List<ChatMessage.ToolCall> iterToolCalls = new ArrayList<>();
            final String[] iterError = {null};
            final CountDownLatch latch = new CountDownLatch(1);

            llmClient.chatStream(
                    context.getConversation().getMessages(),
                    tools,
                    new LlmStreamListener() {
                        @Override
                        public void onContent(String delta) {
                            iterContent.append(delta);
                            listener.onContent(delta);
                        }

                        @Override
                        public void onToolCall(String toolCallId, String functionName, String arguments) {
                            ChatMessage.ToolCall tc = new ChatMessage.ToolCall();
                            tc.setId(toolCallId);
                            tc.setType("function");
                            ChatMessage.FunctionCall fc = new ChatMessage.FunctionCall();
                            fc.setName(functionName);
                            fc.setArguments(arguments);
                            tc.setFunction(fc);
                            iterToolCalls.add(tc);
                        }

                        @Override
                        public void onUsage(LlmResponse.Usage usage) {
                            totalUsage[0] += usage.getPromptTokens();
                            totalUsage[1] += usage.getCompletionTokens();
                            totalUsage[2] += usage.getTotalTokens();
                            // 记录 LLM 返回的实际 promptTokens，供下一轮压缩决策使用
                            lastPromptTokens[0] = usage.getPromptTokens();
                        }

                        @Override
                        public void onComplete() {
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error, Throwable throwable) {
                            iterError[0] = error;
                            latch.countDown();
                        }
                    }
            );

            try {
                // 使用超时等待，防止 onFailure 未被调用导致线程永久阻塞
                boolean completed = latch.await(180, java.util.concurrent.TimeUnit.SECONDS);
                if (!completed) {
                    LOG.warn("LLM response timed out at iteration " + (iteration + 1) + " (180s)");
                    llmClient.cancel();
                    recordInterruptedTurn(context, iterContent.toString(), I18nUtil.getMessage("agent.llm.timeoutReason"));
                    listener.onError(I18nUtil.getMessage("agent.llm.timeout"));
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordInterruptedTurn(context, iterContent.toString(), I18nUtil.getMessage("agent.interrupted"));
                listener.onError(I18nUtil.getMessage("agent.interrupted"));
                return;
            }

            // 用户点击停止：cancel 会触发 onFailure，但应视为正常结束
            if (context.isStopped()) {
                LOG.debug("Agent loop stopped by user");
                LlmResponse.Usage usage = buildAccumulatedUsage(context, totalUsage);
                if (usage != null) listener.onUsage(usage);
                listener.onComplete(fullResponse.length() > 0 ? fullResponse.toString() : I18nUtil.getMessage("agent.stopped"));
                return;
            }

            if (iterError[0] != null) {
                LOG.warn("Streaming request failed at iteration " + (iteration + 1) + ": " + iterError[0]);
                recordInterruptedTurn(context, iterContent.toString(), iterError[0]);
                listener.onError(iterError[0]);
                return;
            }

            // 情况1: LLM 返回了工具调用
            if (!iterToolCalls.isEmpty()) {
                String content = iterContent.length() > 0 ? iterContent.toString() : null;

                context.getConversation().addAssistantToolCalls(
                        iterToolCalls.toArray(new ChatMessage.ToolCall[0]), content);

                if (content != null && !content.isEmpty()) {
                    fullResponse.append(content);
                }

                // 先在调用线程触发所有工具的 onToolCallStart（用于 UI 更新）
                for (ChatMessage.ToolCall toolCall : iterToolCalls) {
                    String toolName = toolCall.getFunction().getName();
                    String args = toolCall.getFunction().getArguments();
                    String toolCallId = toolCall.getId();
                    listener.onToolCallStart(toolCallId, toolName, args);
                    LOG.debug("Calling tool: " + toolName);
                }

                // 异步并行执行所有工具调用
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                Map<String, String> toolResults = new ConcurrentHashMap<>();
                for (ChatMessage.ToolCall toolCall : iterToolCalls) {
                    String toolName = toolCall.getFunction().getName();
                    String args = toolCall.getFunction().getArguments();
                    String toolCallId = toolCall.getId();

                    if (!registry.isToolAllowed(toolName, context.getMode())) {
                        // Distinguish the three reject reasons: a blanket "Plan mode" message for an
                        // unknown or user-disabled tool sends the LLM into a useless /build retry loop.
                        String err;
                        if (registry.getTool(toolName) == null) {
                            err = ToolError.of("TOOL_NOT_FOUND", I18nUtil.getMessage("agent.tool.notFound", toolName), I18nUtil.getMessage("agent.tool.useAvailable"));
                        } else if (!AiAgentSettings.getInstance().isToolEnabled(toolName)) {
                            err = ToolError.of("TOOL_DISABLED", I18nUtil.getMessage("agent.tool.disabled", toolName), I18nUtil.getMessage("agent.tool.useAlternative"));
                        } else {
                            err = ToolError.of("TOOL_NOT_ALLOWED", I18nUtil.getMessage("agent.tool.planDisabled", toolName), I18nUtil.getMessage("agent.tool.switchBuild"));
                        }
                        listener.onToolCallEnd(toolCallId, toolName, err);
                        toolResults.put(toolCallId, err);
                        continue;
                    }

                    if ("run_command".equals(toolName)) {
                        futures.add(CompletableFuture.runAsync(() ->
                                handleRunCommand(context, toolCall, listener, toolResults), toolExecutor));
                    } else {
                        futures.add(CompletableFuture.runAsync(() -> {
                            if (context.isStopped()) return; // Fix 6: 停止检查
                            try {
                                Tool tool = registry.getTool(toolName);
                                String result;
                                if (tool != null) {
                                    try {
                                        result = tool.execute(args);
                                    } catch (Throwable e) {
                                        result = ToolError.unexpected(LOG, "Tool " + toolName + " failed", e,
                                                I18nUtil.getMessage("agent.tool.executionFailed", toolName, e.getMessage()), I18nUtil.getMessage("agent.tool.retryHint"));
                                        listener.onContent(I18nUtil.getMessage("agent.tool.executionNotice", toolName, e.getMessage()));
                                    }
                                } else {
                                    result = ToolError.of("TOOL_NOT_FOUND", I18nUtil.getMessage("agent.tool.notFound", toolName), I18nUtil.getMessage("agent.tool.useAvailable"));
                                }
                                listener.onToolCallEnd(toolCallId, toolName, result);
                                LOG.debug("Tool completed: " + toolName);
                                toolResults.put(toolCallId, result);
                            } catch (Throwable e) {
                                // Fix 2: 确保任何异常都有结果记录
                                LOG.error("Tool callback failed: " + toolName, e);
                                String errResult = ToolError.of("INTERNAL_ERROR", I18nUtil.getMessage("agent.tool.internalFailed", toolName, e.getMessage()), I18nUtil.getMessage("agent.tool.retryHint"));
                                listener.onToolCallEnd(toolCallId, toolName, errResult);
                                listener.onContent(I18nUtil.getMessage("agent.tool.internalNotice", toolName, e.getMessage()));
                                toolResults.put(toolCallId, errResult);
                            }
                        }, toolExecutor));
                    }
                }

                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (Exception e) {
                    LOG.error("Failed while waiting for asynchronous tools", e);
                    listener.onContent(I18nUtil.getMessage("agent.tool.batchFailed", e.getMessage()));
                }

                // Fix 5: 按 LLM 输出的 tool_calls 顺序添加结果
                for (ChatMessage.ToolCall toolCall : iterToolCalls) {
                    String result = toolResults.get(toolCall.getId());
                    if (result != null) {
                        context.getConversation().addToolResult(truncateToolResult(result), toolCall.getId());
                    }
                }

                // 全部工具执行完成后，检查停止标志
                if (context.isStopped()) {
                    LOG.debug("Agent loop stopped after tool execution");
                    LlmResponse.Usage usage = buildAccumulatedUsage(context, totalUsage);
                    if (usage != null) listener.onUsage(usage);
                    listener.onComplete(fullResponse.length() > 0 ? fullResponse.toString() : I18nUtil.getMessage("agent.stopped"));
                    return;
                }

                continue;
            }

            // 情况2: LLM 返回了纯文本回答（Agent 循环结束）
            String content = iterContent.length() > 0 ? iterContent.toString() : null;
            if (content != null) {
                fullResponse.append(content);
                context.getConversation().addAssistantMessage(content);
            } else {
                LOG.warn("No content received at agent iteration " + (iteration + 1));
                listener.onError(I18nUtil.getMessage("agent.llm.noContentHint"));
                return;
            }

            LlmResponse.Usage usage = buildAccumulatedUsage(context, totalUsage);
            if (usage != null) listener.onUsage(usage);
            listener.onComplete(fullResponse.toString());
            LOG.debug("Agent loop completed after " + (iteration + 1) + " iterations");
            return;
        }

        // 达到最大迭代次数
        String msg = I18nUtil.getMessage("agent.maxIterations", maxIterations);
        listener.onContent(msg);
        fullResponse.append(msg);
        context.getConversation().addAssistantMessage(msg.trim());
        LlmResponse.Usage usage = buildAccumulatedUsage(context, totalUsage);
        if (usage != null) listener.onUsage(usage);
        listener.onComplete(fullResponse.toString());
    }

    /**
     * 将本轮已产生的部分助手输出（若有）+ 失败原因写入会话历史，
     * 确保超时/异常/流中断不会让本轮用户消息和已生成的部分回复凭空消失——
     * 下一轮对话仍能在完整历史（含失败记录）基础上继续，而不是从一个"假装什么都没发生"的历史重新开始。
     */
    private void recordInterruptedTurn(AgentContext context, String partialContent, String error) {
        String notice = (partialContent != null && !partialContent.isEmpty())
                ? partialContent + I18nUtil.getMessage("agent.responseInterrupted", error)
                : I18nUtil.getMessage("agent.responseFailed", error);
        context.getConversation().addAssistantMessage(notice);
    }

    /**
     * 构建本轮（增量）Token 用量并计入会话累计值。
     * totalUsage 是本次 executeAgentLoop 调用内各次 LLM 请求的用量之和（本轮增量），
     * 这里将其计入会话累计值后原样返回，前端据此进行 += 累加，避免重复计数。
     */
    private static LlmResponse.Usage buildAccumulatedUsage(AgentContext context, int[] totalUsage) {
        if (totalUsage[2] == 0) return null;
        context.addAndGetPreviousCumulativeUsage(totalUsage[0], totalUsage[1], totalUsage[2]);
        LlmResponse.Usage usage = new LlmResponse.Usage();
        usage.setPromptTokens(totalUsage[0]);
        usage.setCompletionTokens(totalUsage[1]);
        usage.setTotalTokens(totalUsage[2]);
        return usage;
    }

    // 工具结果截断阈值（近似 token 数）：超过该值时仅保留首尾各半
    private static final int TOOL_RESULT_MAX_TOKENS = 8000;
    private static final int TOOL_RESULT_HEAD_TOKENS = 4000;
    private static final int TOOL_RESULT_TAIL_TOKENS = 4000;
    // 粗略近似：1 token ≈ 4 字符（不引入外部分词依赖）
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * 工具结果截断闸门：所有工具类型（run_command、MCP、浏览器、文件读取等）的结果
     * 在写入会话历史前统一经过此方法，避免超长输出（如构建日志）无限制占用上下文 token。
     * 超出阈值时保留头部 + 尾部，中间插入明确的截断提示。
     */
    private static String truncateToolResult(String result) {
        if (result == null || result.isEmpty()) {
            return result;
        }
        int totalTokens = (int) Math.ceil(result.length() / (double) CHARS_PER_TOKEN);
        if (totalTokens <= TOOL_RESULT_MAX_TOKENS) {
            return result;
        }

        int headChars = TOOL_RESULT_HEAD_TOKENS * CHARS_PER_TOKEN;
        int tailChars = TOOL_RESULT_TAIL_TOKENS * CHARS_PER_TOKEN;
        if (headChars + tailChars >= result.length()) {
            return result;
        }

        String head = result.substring(0, headChars);
        String tail = result.substring(result.length() - tailChars);
        String notice = "\n\n[... truncated — original result was " + totalTokens
                + " tokens, showing first and last portions ...]\n\n";

        LOG.debug("Truncated tool result from " + totalTokens + " to "
                + (TOOL_RESULT_HEAD_TOKENS + TOOL_RESULT_TAIL_TOKENS) + " tokens");

        return head + notice + tail;
    }

    /**
     * 处理 run_command 命令执行
     * 使用 IDEA 内置终端执行命令（通过 RunCommandTool），用户可以在终端中实时看到命令和输出
     */
    private void handleRunCommand(AgentContext context, ChatMessage.ToolCall toolCall, AgentListener listener, Map<String, String> toolResults) {
        String toolCallId = toolCall.getId();
        String toolName = toolCall.getFunction().getName();
        String args = toolCall.getFunction().getArguments();

        try {
            // a. 解析命令参数
            JsonObject jsonArgs = JsonParser.parseString(args).getAsJsonObject();
            String command = jsonArgs.get("command").getAsString();

            // b. 检查是否危险命令
            boolean dangerous = isDangerousCommand(command);

            // c. 通知监听器
            listener.onCommandApproval(toolCallId, command, dangerous);

            // d. 对于危险命令：等待用户审批（轮询检查停止标志）
            if (dangerous) {
                ApprovalManager.CommandApproval ca = approvalManager.register(toolCallId);
                try {
                    // 使用轮询方式等待审批，以便能响应停止信号
                    boolean released = false;
                    long approvalTimeout = 300;
                    long elapsed = 0;
                    while (elapsed < approvalTimeout) {
                        if (context.isStopped()) {
                            approvalManager.reject(toolCallId);
                            String err = ToolError.of("USER_STOPPED", I18nUtil.getMessage("tool.command.stopped"), I18nUtil.getMessage("tool.command.retryHint"));
                            listener.onToolCallEnd(toolCallId, toolName, err);
                            listener.onCommandResult(toolCallId, err);
                            toolResults.put(toolCallId, err);
                            return;
                        }
                        released = ca.latch.await(1, TimeUnit.SECONDS);
                        if (released) break;
                        elapsed++;
                    }
                    if (!released) {
                        approvalManager.reject(toolCallId);
                        String err = ToolError.of("APPROVAL_TIMEOUT", I18nUtil.getMessage("agent.command.approvalTimeout"), I18nUtil.getMessage("agent.command.approvalRetry"));
                        listener.onToolCallEnd(toolCallId, toolName, err);
                        listener.onCommandResult(toolCallId, err);
                        toolResults.put(toolCallId, err);
                        return;
                    }
                    if (!ca.approved) {
                        String err = ToolError.of("USER_DENIED", I18nUtil.getMessage("agent.command.cancelled"), I18nUtil.getMessage("agent.command.alternativeHint"));
                        listener.onToolCallEnd(toolCallId, toolName, err);
                        listener.onCommandResult(toolCallId, err);
                        toolResults.put(toolCallId, err);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    approvalManager.reject(toolCallId);
                    String err = ToolError.of("INTERRUPTED", I18nUtil.getMessage("agent.command.approvalInterrupted"), I18nUtil.getMessage("tool.hint.retry"));
                    listener.onToolCallEnd(toolCallId, toolName, err);
                    listener.onCommandResult(toolCallId, err);
                    toolResults.put(toolCallId, err);
                    return;
                }
            }

            // 执行前再次检查停止标志
            if (context.isStopped()) {
                String err = ToolError.of("USER_STOPPED", I18nUtil.getMessage("tool.command.stopped"), I18nUtil.getMessage("tool.command.retryHint"));
                listener.onToolCallEnd(toolCallId, toolName, err);
                listener.onCommandResult(toolCallId, err);
                toolResults.put(toolCallId, err);
                return;
            }

            // e. 通过 RunCommandTool 在 IDEA 终端中执行命令（安全命令直接执行，危险命令审批后执行）
            listener.onCommandProgress(toolCallId, I18nUtil.getMessage("agent.command.starting"));
            RunCommandTool runCommandTool = new RunCommandTool(project);
            context.registerRunCommandTool(toolCallId, runCommandTool);
            try {
                String result = runCommandTool.execute(args);
                listener.onCommandProgress(toolCallId, I18nUtil.getMessage("agent.command.completed"));
                listener.onCommandResult(toolCallId, result);
                listener.onToolCallEnd(toolCallId, toolName, result);
                toolResults.put(toolCallId, result);
            } finally {
                context.removeRunCommandTool(toolCallId);
            }

        } catch (Exception e) {
            LOG.error("Failed to process run_command", e);
            String err = ToolError.of("INTERNAL_ERROR", I18nUtil.getMessage("agent.command.failed", e.getMessage()), I18nUtil.getMessage("tool.hint.retry"));
            listener.onToolCallEnd(toolCallId, toolName, err);
            listener.onCommandResult(toolCallId, err);
            toolResults.put(toolCallId, err);
        }
    }

    /**
     * 重置当前活跃会话
     */
    public void resetConversation() {
        getContext().resetConversation();
    }

    // ========== 上下文压缩 ==========

    /**
     * 检查是否需要压缩，如果需要则执行压缩
     * 始终实时计算当前消息的 Token 数，不依赖可能过时的 lastPromptTokens
     * 使用模型的 contextWindowSize（上下文窗口大小）而非 maxTokens（最大输出 Token）计算阈值
     *
     * @param lastPromptTokens 上一轮 LLM 返回的实际 promptTokens（已弃用，保留参数兼容性）
     */
    private void checkAndCompress(AgentContext context, LlmClient llmClient, int lastPromptTokens, AgentListener listener) {
        AiAgentSettings.ModelConfig config = AiAgentSettings.getInstance().getActiveModelConfig();
        int threshold = config.compressionThreshold;
        int contextWindowSize = config.contextWindowSize > 0 ? config.contextWindowSize : 128000;

        List<ChatMessage> messages = context.getConversation().getMessages();

        // 始终实时计算 Token 数，确保准确性
        int totalTokens = TokenCounter.countTokens(messages, llmClient.getModelName())
                + estimateImageTokens(messages);
        LOG.debug("Current context token estimate: " + totalTokens);

        int thresholdTokens = (int) (contextWindowSize * threshold / 100.0);

        if (totalTokens > thresholdTokens) {
            LOG.debug("Context compression threshold exceeded: " + totalTokens + " > " + thresholdTokens);
            compressConversation(context, llmClient, totalTokens, listener);
        }
    }


    /**
     * 估算消息列表中所有图片消耗的 Token 数
     */
    private int estimateImageTokens(List<ChatMessage> messages) {
        int imageTokens = 0;
        for (ChatMessage msg : messages) {
            if (msg.getImageContents() != null) {
                for (ChatMessage.ImageContent img : msg.getImageContents()) {
                    // 尺寸未知，使用启发式估算
                    imageTokens += TokenCounter.estimateImageTokens(0, 0, img.getMimeType());
                }
            }
        }
        return imageTokens;
    }

    /**
     * 执行上下文压缩：摘要压缩，失败则回退到优先级丢弃
     */
    private void compressConversation(AgentContext context, LlmClient llmClient, int beforeTokens, AgentListener listener) {
        compressConversationInternal(context, llmClient, beforeTokens, listener, true);
    }

    /**
     * 内部压缩实现
     *
     * P0: 修复 UI 脱节问题 — 通过 CompressionListener 回调通知前端
     * P1: 改进压缩提示词 — 使用结构化输出
     * P1: 智能回退压缩 — 按消息优先级排序后丢弃
     * P1: 循环压缩直到低于阈值
     * P2: 保留图片消息的原始上下文
     */
    private void compressConversationInternal(AgentContext context, LlmClient llmClient, int beforeTokens,
                                               AgentListener listener, boolean notifyStart) {
        if (notifyStart) {
            notifyCompressionStarted();
        }

        List<ChatMessage> messages = context.getConversation().getMessages();

        int systemIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).getRole())) {
                systemIdx = i;
                break;
            }
        }

        int recentStart = findRecentMessagesStart(messages);
        if (recentStart <= systemIdx + 1) {
            LOG.warn("Not enough old messages to compress");
            notifyCompressionFailed(I18nUtil.getMessage("agent.compression.notEnoughMessages"));
            return;
        }

        List<ChatMessage> olderMessages = new ArrayList<>(messages.subList(systemIdx + 1, recentStart));
        List<ChatMessage> recentMessages = new ArrayList<>(messages.subList(recentStart, messages.size()));

        StringBuilder oldContent = new StringBuilder();
        for (ChatMessage msg : olderMessages) {
            String content = msg.getContent();
            if (content != null && !content.isEmpty()) {
                oldContent.append("[").append(msg.getRole()).append("]: ").append(content).append("\n\n");
            }
        }

        boolean wasStopped = context.isStopped();
        context.setStopped(false);

        try {
            List<ChatMessage> summaryRequest = new ArrayList<>();
            String compressPrompt = context.getPromptManager().buildCompressPrompt(oldContent.toString());
            summaryRequest.add(ChatMessage.system(compressPrompt));
            summaryRequest.add(ChatMessage.user(I18nUtil.getMessage("agent.compression.summaryRequest")));

            LlmResponse summaryResponse = llmClient.chat(summaryRequest, null);

            if (summaryResponse != null && summaryResponse.isSuccess()
                    && summaryResponse.getContent() != null
                    && !summaryResponse.getContent().isEmpty()) {
                String summary = summaryResponse.getContent();

                List<ChatMessage> newMessages = new ArrayList<>();
                newMessages.add(ChatMessage.system(I18nUtil.getMessage("agent.compression.summaryHeader") + summary));

                // P2: 保留携带图片的历史消息，同时附带原始文本上下文
                for (ChatMessage older : olderMessages) {
                    if (older.hasImages()) {
                        String originalText = older.getContent();
                        String contextNote = (originalText != null && !originalText.isEmpty())
                                ? I18nUtil.getMessage("agent.compression.imageQuestion", truncateText(originalText, 100))
                                : I18nUtil.getMessage("agent.compression.imageOnly");
                        ChatMessage imageWithContext = ChatMessage.user(contextNote);
                        imageWithContext.setImageContents(older.getImageContents());
                        newMessages.add(imageWithContext);
                    }
                }
                newMessages.addAll(recentMessages);

                context.getConversation().replaceMessages(newMessages);

                int afterTokens = TokenCounter.countTokens(context.getConversation().getMessages(), llmClient.getModelName());

                // P1: 检查是否仍超过阈值，需要再次压缩
                AiAgentSettings.ModelConfig config = AiAgentSettings.getInstance().getActiveModelConfig();
                int contextWindowSize = config.contextWindowSize > 0 ? config.contextWindowSize : 128000;
                int thresholdTokens = (int) (contextWindowSize * config.compressionThreshold / 100.0);

                if (afterTokens > thresholdTokens && !olderMessages.isEmpty()) {
                    LOG.debug("Context still exceeds threshold after compression; compressing again");
                    compressConversationInternal(context, llmClient, afterTokens, listener, false);
                    return;
                }

                int savedPercent = beforeTokens > 0 ? (int) ((1.0 - (double) afterTokens / beforeTokens) * 100) : 0;
                LOG.debug("Context compression completed: " + beforeTokens + " -> " + afterTokens + " tokens (saved " + savedPercent + "%)");
                notifyCompression(beforeTokens, afterTokens);
            } else {
                String errMsg = I18nUtil.getMessage("agent.compression.summaryFailed");
                LOG.warn(errMsg);
                notifyCompressionFailed(errMsg);
                fallbackCompress(context, llmClient, olderMessages, recentMessages, beforeTokens);
            }
        } catch (Exception e) {
            String errMsg = e.getMessage();
            LOG.error("Context compression failed unexpectedly", e);
            notifyCompressionFailed(errMsg);
            fallbackCompress(context, llmClient, olderMessages, recentMessages, beforeTokens);
        } finally {
            context.setStopped(wasStopped);
        }
    }

    /**
     * 截断文本到指定长度
     */
    private String truncateText(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 回退压缩：按优先级保留消息（P1 修复）
     *
     * 优先级（从高到低）:
     * 1. tool 结果消息（包含重要的工具执行结果）
     * 2. assistant 消息中包含代码块的
     * 3. 其他 assistant 消息
     * 4. user 消息
     */
    private void fallbackCompress(AgentContext context, LlmClient llmClient, List<ChatMessage> olderMessages,
                                  List<ChatMessage> recentMessages, int beforeTokens) {
        int keepCount = Math.max(1, olderMessages.size() / 2);

        List<ChatMessage> sortedOlder = new ArrayList<>(olderMessages);
        sortedOlder.sort((a, b) -> {
            int pa = getMessagePriority(a);
            int pb = getMessagePriority(b);
            if (pa != pb) return pb - pa;
            return olderMessages.indexOf(a) - olderMessages.indexOf(b);
        });

        List<ChatMessage> keptOlder = new ArrayList<>();
        for (int i = 0; i < Math.min(keepCount, sortedOlder.size()); i++) {
            keptOlder.add(sortedOlder.get(i));
        }
        keptOlder.sort((a, b) -> olderMessages.indexOf(a) - olderMessages.indexOf(b));

        List<ChatMessage> newMessages = new ArrayList<>();
        newMessages.addAll(keptOlder);
        newMessages.addAll(recentMessages);

        context.getConversation().replaceMessages(newMessages);

        int afterTokens = TokenCounter.countTokens(context.getConversation().getMessages(), llmClient.getModelName());
        int savedPercent = beforeTokens > 0 ? (int) ((1.0 - (double) afterTokens / beforeTokens) * 100) : 0;

        LOG.debug("Fallback compression completed: " + beforeTokens + " -> " + afterTokens + " tokens (saved " + savedPercent + "%)");
        notifyCompression(beforeTokens, afterTokens);
    }

    /**
     * 获取消息优先级（用于回退压缩排序）
     */
    private int getMessagePriority(ChatMessage msg) {
        String role = msg.getRole();
        String content = msg.getContent();

        if ("tool".equals(role)) {
            return 100;
        }
        if ("assistant".equals(role)) {
            if (content != null && content.contains("```")) {
                return 80;
            }
            return 60;
        }
        if ("user".equals(role)) {
            return 40;
        }
        return 20;
    }

    /**
     * 找到最近N轮对话的起始位置（P2 修复：可配置保留轮数）
     *
     * 默认保留最近 2 轮，可通过 ModelConfig.recentTurnsToKeep 配置。
     * 如果对话总轮数较少（<=3 轮），则只保留最后 1 轮以便有内容可压缩。
     */
    private int findRecentMessagesStart(List<ChatMessage> messages) {
        AiAgentSettings.ModelConfig config = AiAgentSettings.getInstance().getActiveModelConfig();
        int turnsToKeep = config.recentTurnsToKeep;

        int totalUserMessages = 0;
        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole())) totalUserMessages++;
        }

        if (totalUserMessages <= 3) {
            turnsToKeep = 1;
        }

        int userCount = 0;
        int recentStart = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                userCount++;
                if (userCount == turnsToKeep) {
                    recentStart = i;
                    break;
                }
            }
        }

        if (totalUserMessages <= turnsToKeep && messages.size() - recentStart > 4) {
            recentStart = messages.size() - 4;
        }

        return recentStart;
    }

    /**
     * 通知前端压缩结果
     */
    private void notifyCompression(int beforeTokens, int afterTokens) {
        CompressionListener listener = this.compressionListener;
        if (listener != null) {
            listener.onCompressed(beforeTokens, afterTokens);
        }
    }

    /**
     * 通知前端压缩开始（P2: 进度提示）
     */
    private void notifyCompressionStarted() {
        CompressionListener listener = this.compressionListener;
        if (listener != null) {
            listener.onCompressionStarted();
        }
    }

    /**
     * 通知前端压缩失败（P0: 确保 UI 状态恢复）
     */
    private void notifyCompressionFailed(String reason) {
        CompressionListener listener = this.compressionListener;
        if (listener != null) {
            listener.onCompressionFailed(reason);
        }
    }

    /**
     * 手动触发压缩（供 ChatPanel 调用）
     */
    public void manualCompress() {
        AgentContext ctx = getContext();
        if (ctx == null) {
            notifyCompressionFailed(I18nUtil.getMessage("agent.compression.noSession"));
            return;
        }

        LlmClient llmClient = ctx.getLlmClient();
        List<ChatMessage> messages = ctx.getConversation().getMessages();
        int beforeTokens = TokenCounter.countTokens(messages, llmClient.getModelName());

        ctx.beginLlmRequest();
        try {
            compressConversation(ctx, llmClient, beforeTokens, new AgentListener() {
                @Override public void onThinking() {}
                @Override public void onContent(String content) { LOG.debug("Compression notice: " + content); }
                @Override public void onToolCallStart(String toolCallId, String toolName, String arguments) {}
                @Override public void onToolCallEnd(String toolCallId, String toolName, String result) {}
                @Override public void onComplete(String fullResponse) {}
                @Override public void onError(String error) { LOG.warn("Manual compression failed: " + error); }
            });
        } finally {
            ctx.endLlmRequest();
        }
    }
}
