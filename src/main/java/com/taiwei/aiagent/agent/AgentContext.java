package com.taiwei.aiagent.agent;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.agent.prompt.PromptManager;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.openai.OpenAiLlmClient;
import com.taiwei.aiagent.model.Conversation;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.tool.ToolRegistry;
import com.taiwei.aiagent.tool.impl.BrowserTool;
import com.taiwei.aiagent.tool.impl.DdgSearchTool;
import com.taiwei.aiagent.tool.impl.FileReadTool;
import com.taiwei.aiagent.tool.impl.FileReplaceTool;
import com.taiwei.aiagent.tool.impl.FileWriteTool;
import com.taiwei.aiagent.tool.impl.FindReferencesTool;
import com.taiwei.aiagent.tool.impl.FindSymbolTool;
import com.taiwei.aiagent.tool.impl.GoToDefinitionTool;
import com.taiwei.aiagent.tool.impl.ImageGenerationTool;
import com.taiwei.aiagent.tool.impl.LoadSkillTool;
import com.taiwei.aiagent.tool.impl.MemorySearchTool;
import com.taiwei.aiagent.tool.impl.MemoryTool;
import com.taiwei.aiagent.tool.impl.RunCommandTool;
import com.taiwei.aiagent.tool.impl.SearchCodeTool;
import com.taiwei.aiagent.tool.impl.SerpApiSearchTool;
import com.taiwei.aiagent.tool.impl.TodoPlanTool;
import com.taiwei.aiagent.tool.impl.WebSearchTool;

/**
 * Agent 会话上下文
 * 持有一次 Agent 会话所需的所有组件：LLM 客户端、工具注册表、对话历史等
 */
public class AgentContext {

    private final Project project;
    private final Conversation conversation;
    private final ToolRegistry toolRegistry;
    private final PromptManager promptManager;
    private volatile AgentMode mode = AgentMode.BUILD;
    /** 会话级模型索引，-1 表示使用全局默认 */
    private int modelIndex = -1;

    private static final Logger LOG = Logger.getInstance(AgentContext.class);

    /**
     * Agent 循环最大迭代次数（防止无限循环）
     */
    private static final int MAX_ITERATIONS = 50;

    private LlmClient cachedClient;
    private String cachedBaseUrl;
    private String cachedApiKey;
    private String cachedModel;

    /**
     * Number of in-flight LLM requests currently using {@link #cachedClient}.
     * Bracketed by {@link #beginLlmRequest()}/{@link #endLlmRequest()} around every
     * chat()/chatStream() call site in AgentService.
     */
    private final java.util.concurrent.atomic.AtomicInteger activeRequestCount = new java.util.concurrent.atomic.AtomicInteger(0);
    /**
     * Clients that were replaced (model/config switch) while a request was still in flight.
     * Closed once the in-flight request count drops back to zero, or on dispose().
     */
    private final java.util.List<LlmClient> staleClients = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 该会话自创建以来累计消耗的 Token 数（用于按轮次计算增量用量，避免前端重复累加）
     */
    private int cumulativePromptTokens = 0;
    private int cumulativeCompletionTokens = 0;
    private int cumulativeTotalTokens = 0;

    /** Session-scoped stop flag */
    private volatile boolean stopped = false;
    /** The LLM client being used by this session's current generation */
    private volatile LlmClient activeLlmClient = null;
    /** RunCommandTool instances currently active in this session */
    private final java.util.concurrent.ConcurrentHashMap<String, RunCommandTool> activeRunCommandTools =
        new java.util.concurrent.ConcurrentHashMap<>();

    public AgentContext(Project project) {
        this.project = project;

        // 初始化 Prompt 管理器
        this.promptManager = new PromptManager(project);

        // 创建对话（带系统提示词）
        this.conversation = new Conversation(promptManager.buildSystemPrompt(mode));

        // 注册工具
        this.toolRegistry = new ToolRegistry();
        registerDefaultTools();
        wireConversationAwareTools();
    }

    /**
     * 从已恢复的 Conversation 创建上下文（用于从磁盘恢复会话）
     */
    public AgentContext(Project project, Conversation conversation) {
        this.project = project;
        this.promptManager = new PromptManager(project);
        this.conversation = conversation;
        this.toolRegistry = new ToolRegistry();
        registerDefaultTools();
        wireConversationAwareTools();
        // 恢复的会话可能未携带系统提示词（历史持久化数据 systemPrompt 为 null），补齐当前模式对应的系统提示词
        conversation.updateSystemPrompt(promptManager.buildSystemPrompt(mode));
    }

    /**
     * 为需要读取会话历史的工具（如图像生成工具读取最近一条带图用户消息作为参考图）注入 Conversation 引用。
     * buildDefaultTools() 是静态方法（供 ToolManagerDialog 复用，无实际会话），因此在此单独补齐。
     */
    private void wireConversationAwareTools() {
        com.taiwei.aiagent.tool.Tool tool = toolRegistry.getTool("generate_image");
        if (tool instanceof ImageGenerationTool imageTool) {
            imageTool.setConversationSupplier(() -> conversation);
        }
    }

    /**
     * 根据当前设置创建 LLM 客户端，并关闭旧的缓存客户端
     */
    private synchronized LlmClient createLlmClient() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String baseUrl = settings.getBaseUrl();
        String apiKey = settings.getApiKey();
        String model = settings.getModel();
        int activeIndex = settings.getActiveModelIndex();
        LOG.debug("Creating LLM client - activeIndex=" + activeIndex
                + ", baseUrl=" + baseUrl + ", model=" + model);

        retireClient(cachedClient);

        LlmClient client = new OpenAiLlmClient(baseUrl, apiKey, model);
        cachedClient = client;
        cachedBaseUrl = baseUrl;
        cachedApiKey = apiKey;
        cachedModel = model;
        return client;
    }

    /**
     * Retire a replaced LLM client. If no request is currently in flight it is closed
     * immediately; otherwise closing is deferred (see {@link #endLlmRequest()}) so an
     * in-progress chat()/chatStream() call isn't cut off mid-stream.
     */
    private void retireClient(LlmClient client) {
        if (client == null) return;
        if (activeRequestCount.get() > 0) {
            staleClients.add(client);
            // The last in-flight request may have finished between the check above and the add:
            // its endLlmRequest() saw an empty staleClients list, so nobody else would ever close
            // this client. Re-check and drain if so.
            if (activeRequestCount.get() <= 0) {
                drainStaleClients();
            }
        } else {
            client.close();
        }
    }

    private void drainStaleClients() {
        for (LlmClient stale : staleClients) {
            if (staleClients.remove(stale)) {
                stale.close();
            }
        }
    }

    /**
     * Mark the start of a request that will use {@link #getLlmClient()}'s return value.
     * Must be paired with {@link #endLlmRequest()} in a finally block.
     */
    public void beginLlmRequest() {
        activeRequestCount.incrementAndGet();
    }

    /**
     * Mark the end of a request started via {@link #beginLlmRequest()}. Once the last
     * in-flight request completes, any clients retired in the meantime are closed.
     */
    public void endLlmRequest() {
        if (activeRequestCount.decrementAndGet() <= 0 && !staleClients.isEmpty()) {
            drainStaleClients();
        }
    }

    /**
     * 切换模型（使缓存失效，下次 getLlmClient() 时重建）
     */
    public synchronized void switchModel(int modelIndex) {
        this.modelIndex = modelIndex;
        AiAgentSettings settings = AiAgentSettings.getInstance();
        settings.setActiveModelIndex(modelIndex);
        retireClient(cachedClient);
        cachedClient = null;
        cachedBaseUrl = null;
        cachedApiKey = null;
        cachedModel = null;
    }

    /**
     * 获取会话级模型索引
     */
    public int getModelIndex() {
        return modelIndex;
    }

    /**
     * 设置会话级模型索引（不触发全局设置变更）
     */
    public void setModelIndex(int modelIndex) {
        this.modelIndex = modelIndex;
    }

    /**
     * 累加本轮 Token 用量到会话累计值，返回累加前的累计值（用于计算本轮增量）
     */
    public synchronized int[] addAndGetPreviousCumulativeUsage(int promptTokens, int completionTokens, int totalTokens) {
        int[] previous = {cumulativePromptTokens, cumulativeCompletionTokens, cumulativeTotalTokens};
        cumulativePromptTokens += promptTokens;
        cumulativeCompletionTokens += completionTokens;
        cumulativeTotalTokens += totalTokens;
        return previous;
    }

    /**
     * 注册默认工具
     */
    private void registerDefaultTools() {
        for (com.taiwei.aiagent.tool.Tool tool : buildDefaultTools(project)) {
            toolRegistry.register(tool);
        }
    }

    /**
     * 构建当前项目下的完整默认工具列表（内置工具 + 已启用的 MCP 工具）
     * 供工具注册以及设置页的工具管理面板复用，不受用户启用/禁用设置过滤
     */
    public static java.util.List<com.taiwei.aiagent.tool.Tool> buildDefaultTools(Project project) {
        java.util.List<com.taiwei.aiagent.tool.Tool> result = new java.util.ArrayList<>();
        result.add(new FileReadTool(project));
        result.add(new FileWriteTool(project));
        result.add(new FileReplaceTool(project));
        result.add(new SearchCodeTool(project));
        if (com.taiwei.aiagent.util.JavaPluginAvailability.isJavaPluginAvailable()) {
            result.add(new FindSymbolTool(project));
            result.add(new FindReferencesTool(project));
            result.add(new GoToDefinitionTool(project));
        }
        result.add(new RunCommandTool(project));
        result.add(new LoadSkillTool(project));
        result.add(new TodoPlanTool());
        result.add(new MemoryTool(project));
        result.add(new MemorySearchTool(project));
        result.add(new BrowserTool(project));
        result.add(new ImageGenerationTool());

        AiAgentSettings settings = AiAgentSettings.getInstance();
        if ("ALIYUN_IQS".equals(settings.getSearchEngineType())) {
            result.add(new WebSearchTool());
        } else if ("SERPAPI".equals(settings.getSearchEngineType())) {
            result.add(new SerpApiSearchTool());
        } else {
            result.add(new DdgSearchTool());
        }

        for (com.taiwei.aiagent.mcp.McpToolAdapter mcpTool
                : com.taiwei.aiagent.mcp.McpManager.getInstance(project).getActiveTools()) {
            result.add(mcpTool);
        }
        return result;
    }

    /**
     * 重置对话（清空历史，保留系统提示词）
     */
    public void resetConversation() {
        conversation.clear();
    }

    public void setStopped(boolean stopped) {
        this.stopped = stopped;
    }

    public boolean isStopped() {
        return stopped;
    }

    public LlmClient getActiveLlmClient() {
        return activeLlmClient;
    }

    public void setActiveLlmClient(LlmClient client) {
        this.activeLlmClient = client;
    }

    /**
     * Stop this session's generation: set flag, cancel LLM client, stop all running command tools.
     */
    public void stop() {
        this.stopped = true;
        LlmClient client = this.activeLlmClient;
        if (client != null) {
            client.cancel();
        }
        for (RunCommandTool tool : activeRunCommandTools.values()) {
            tool.stop();
        }
        activeRunCommandTools.clear();
    }

    public void registerRunCommandTool(String toolCallId, RunCommandTool tool) {
        activeRunCommandTools.put(toolCallId, tool);
    }

    public void removeRunCommandTool(String toolCallId) {
        activeRunCommandTools.remove(toolCallId);
    }

    // ========== Getters ==========

    public Project getProject() {
        return project;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * 获取当前 Agent 模式
     */
    public AgentMode getMode() {
        return mode;
    }

    /**
     * 切换 Agent 模式（Plan/Build），并重建系统提示词写入对话历史
     */
    public void setMode(AgentMode mode) {
        this.mode = mode;
        conversation.updateSystemPrompt(promptManager.buildSystemPrompt(mode));
    }

    /**
     * 获取当前模式下可用的工具列表（Plan 模式过滤掉修改性工具）
     */
    public java.util.List<com.taiwei.aiagent.tool.Tool> getToolsForMode() {
        return toolRegistry.getToolsForMode(mode);
    }

    /**
     * 动态获取 LLM 客户端
     * 配置未变时返回缓存实例，配置变更时重建并关闭旧客户端
     * Synchronized to prevent a race where two threads simultaneously find no cached client
     * and both call createLlmClient(), leaving one holding a closed connection pool.
     */
    public synchronized LlmClient getLlmClient() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String baseUrl = settings.getBaseUrl();
        String apiKey = settings.getApiKey();
        String model = settings.getModel();

        if (cachedClient != null
                && java.util.Objects.equals(baseUrl, cachedBaseUrl)
                && java.util.Objects.equals(apiKey, cachedApiKey)
                && java.util.Objects.equals(model, cachedModel)) {
            return cachedClient;
        }

        return createLlmClient();
    }

    /**
     * Close the cached LLM client and release its resources.
     * Called when the owning AgentService is disposed on project close.
     */
    public synchronized void dispose() {
        if (cachedClient != null) {
            cachedClient.close();
            cachedClient = null;
        }
        drainStaleClients();
    }

    public PromptManager getPromptManager() {
        return promptManager;
    }

    public int getMaxIterations() {
        return MAX_ITERATIONS;
    }
}
