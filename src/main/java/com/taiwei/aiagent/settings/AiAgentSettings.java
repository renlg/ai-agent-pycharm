package com.taiwei.aiagent.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Agent 插件配置
 * 支持多模型配置，可自由切换
 */
@State(
        name = "AiAgentSettings",
        storages = @Storage("ai-agent-settings.xml")
)
public class AiAgentSettings implements PersistentStateComponent<AiAgentSettings.State> {

    private State state = new State();
    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Guards all reads/writes of `state`'s mutable collections (modelConfigs, mcpConfigs, etc.).
    // This is an application-level service: settings-panel edits happen on the EDT while the
    // Agent loop reads model config from pooled background threads, so plain field access is unsafe.
    private final Object stateLock = new Object();

    public static AiAgentSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiAgentSettings.class);
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    public void fireSettingsChanged() {
        // Converge listener invocation onto the EDT so UI-updating listeners (e.g. ChatPanel's)
        // don't race with the background thread that triggered the settings change.
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable listener : changeListeners) {
                listener.run();
            }
        });
    }

    @Override
    public @Nullable State getState() {
        // Return a deep copy so callers (IntelliJ's serialization framework, or anything else)
        // can't mutate our internal state out from under concurrent readers/writers.
        synchronized (stateLock) {
            State persisted = deepCopyState(state);
            for (int i = 0; i < persisted.modelConfigs.size(); i++) {
                ModelConfig runtime = state.modelConfigs.get(i);
                ModelConfig stored = persisted.modelConfigs.get(i);
                runtime.encryptedApiKey = SecretEncryption.encryptedForStorage(
                        runtime.apiKey, runtime.encryptedApiKey);
                stored.encryptedApiKey = runtime.encryptedApiKey;
                stored.apiKey = null;
            }
            return persisted;
        }
    }

    @Override
    public void loadState(@NotNull State state) {
        synchronized (stateLock) {
            for (ModelConfig config : state.modelConfigs) {
                if (config.encryptedApiKey != null && !config.encryptedApiKey.isEmpty()) {
                    config.apiKey = SecretEncryption.decrypt(config.encryptedApiKey);
                } else {
                    config.apiKey = config.apiKey != null ? config.apiKey : "";
                    config.encryptedApiKey = SecretEncryption.encrypt(config.apiKey);
                }
            }
            this.state = state;
        }
    }

    private static State deepCopyState(State src) {
        State copy = new State();
        copy.modelConfigs.clear();
        for (ModelConfig mc : src.modelConfigs) {
            copy.modelConfigs.add(mc.copy());
        }
        copy.activeModelIndex = src.activeModelIndex;
        copy.maxTokens = src.maxTokens;
        copy.temperature = src.temperature;
        copy.dangerousCommands = new ArrayList<>(src.dangerousCommands);
        copy.searchEngineType = src.searchEngineType;
        copy.disabledSkills = new java.util.LinkedHashSet<>(src.disabledSkills);
        copy.disabledTools = new java.util.LinkedHashSet<>(src.disabledTools);
        copy.mcpConfigs = new ArrayList<>();
        for (McpConfig mcp : src.mcpConfigs) {
            copy.mcpConfigs.add(mcp.copy());
        }
        copy.completionEnabled = src.completionEnabled;
        copy.gitCommitReviewEnabled = src.gitCommitReviewEnabled;
        copy.inlineActionEnabled = src.inlineActionEnabled;
        copy.bypassHostnameVerification = src.bypassHostnameVerification;
        copy.customRules = src.customRules;
        return copy;
    }

    // ========== 模型列表操作 ==========

    public List<ModelConfig> getModelConfigs() {
        synchronized (stateLock) {
            return new ArrayList<>(state.modelConfigs);
        }
    }

    public void setModelConfigs(List<ModelConfig> configs) {
        synchronized (stateLock) {
            state.modelConfigs = new ArrayList<>(configs);
        }
    }

    public void addModelConfig(ModelConfig config) {
        synchronized (stateLock) {
            state.modelConfigs.add(config);
        }
    }

    public void removeModelConfig(int index) {
        synchronized (stateLock) {
            if (index >= 0 && index < state.modelConfigs.size()) {
                state.modelConfigs.remove(index);
                // 如果删除的是当前选中的，重置为第一个
                if (state.activeModelIndex >= state.modelConfigs.size()) {
                    state.activeModelIndex = Math.max(0, state.modelConfigs.size() - 1);
                }
            }
        }
    }

    public void updateModelConfig(int index, ModelConfig config) {
        synchronized (stateLock) {
            if (index >= 0 && index < state.modelConfigs.size()) {
                state.modelConfigs.set(index, config);
            }
        }
    }

    public ModelConfig getActiveModelConfig() {
        synchronized (stateLock) {
            if (state.modelConfigs.isEmpty()) {
                return new ModelConfig(); // 返回默认
            }
            int idx = state.activeModelIndex;
            if (idx < 0 || idx >= state.modelConfigs.size()) {
                idx = 0;
                state.activeModelIndex = 0;
            }
            return state.modelConfigs.get(idx).copy();
        }
    }

    public int getActiveModelIndex() {
        synchronized (stateLock) {
            return state.activeModelIndex;
        }
    }

    public void setActiveModelIndex(int index) {
        synchronized (stateLock) {
            if (index >= 0 && index < state.modelConfigs.size()) {
                state.activeModelIndex = index;
            } else {
                // 索引无效时打印警告，方便排查
                com.intellij.openapi.diagnostic.Logger.getInstance(AiAgentSettings.class)
                        .warn("setActiveModelIndex 失败: index=" + index + ", 模型数量=" + state.modelConfigs.size());
            }
        }
    }

    // ========== 全局参数 ==========

    public int getMaxTokens() {
        synchronized (stateLock) {
            return state.maxTokens;
        }
    }

    public void setMaxTokens(int maxTokens) {
        synchronized (stateLock) {
            state.maxTokens = maxTokens;
        }
    }

    public double getTemperature() {
        synchronized (stateLock) {
            return state.temperature;
        }
    }

    public void setTemperature(double temperature) {
        synchronized (stateLock) {
            state.temperature = temperature;
        }
    }

    // ========== 便捷方法（兼容旧代码） ==========

    public String getBaseUrl() {
        return getActiveModelConfig().baseUrl;
    }

    public String getApiKey() {
        return getActiveModelConfig().apiKey;
    }

    public String getModel() {
        return getActiveModelConfig().modelName;
    }

    // ========== 危险命令配置 ==========

    public List<String> getDangerousCommands() {
        synchronized (stateLock) {
            // Defensive copy: callers iterate this (e.g. isDangerousCommand on agent threads)
            // while the settings panel may replace/mutate the backing list on the EDT.
            return new ArrayList<>(state.dangerousCommands);
        }
    }

    public void setDangerousCommands(List<String> commands) {
        synchronized (stateLock) {
            state.dangerousCommands = new ArrayList<>(commands);
        }
    }

    // ========== 搜索引擎配置 ==========

    public String getSearchEngineType() {
        synchronized (stateLock) {
            return state.searchEngineType;
        }
    }

    public void setSearchEngineType(String searchEngineType) {
        synchronized (stateLock) {
            state.searchEngineType = searchEngineType;
        }
    }

    // ========== 禁用的 Skill 列表 ==========

    public java.util.Set<String> getDisabledSkills() {
        synchronized (stateLock) {
            return new java.util.LinkedHashSet<>(state.disabledSkills);
        }
    }

    public boolean isSkillEnabled(String skillName) {
        synchronized (stateLock) {
            return !state.disabledSkills.contains(skillName);
        }
    }

    public void setSkillEnabled(String skillName, boolean enabled) {
        synchronized (stateLock) {
            if (enabled) {
                state.disabledSkills.remove(skillName);
            } else {
                state.disabledSkills.add(skillName);
            }
        }
        fireSettingsChanged();
    }

    // ========== 禁用的工具列表 ==========

    public java.util.Set<String> getDisabledTools() {
        synchronized (stateLock) {
            return new java.util.LinkedHashSet<>(state.disabledTools);
        }
    }

    public boolean isToolEnabled(String toolName) {
        synchronized (stateLock) {
            return !state.disabledTools.contains(toolName);
        }
    }

    public void setToolEnabled(String toolName, boolean enabled) {
        synchronized (stateLock) {
            if (enabled) {
                state.disabledTools.remove(toolName);
            } else {
                state.disabledTools.add(toolName);
            }
        }
        fireSettingsChanged();
    }

    // ========== 功能开关 ==========

    public boolean isCompletionEnabled() {
        synchronized (stateLock) {
            return state.completionEnabled;
        }
    }

    public void setCompletionEnabled(boolean enabled) {
        synchronized (stateLock) {
            state.completionEnabled = enabled;
        }
    }

    public boolean isGitCommitReviewEnabled() {
        synchronized (stateLock) {
            return state.gitCommitReviewEnabled;
        }
    }

    public void setGitCommitReviewEnabled(boolean enabled) {
        synchronized (stateLock) {
            state.gitCommitReviewEnabled = enabled;
        }
    }

    public boolean isInlineActionEnabled() {
        synchronized (stateLock) {
            return state.inlineActionEnabled;
        }
    }

    public void setInlineActionEnabled(boolean enabled) {
        synchronized (stateLock) {
            state.inlineActionEnabled = enabled;
        }
    }

    public boolean isBypassHostnameVerificationEnabled() {
        synchronized (stateLock) {
            return state.bypassHostnameVerification;
        }
    }

    public void setBypassHostnameVerificationEnabled(boolean enabled) {
        synchronized (stateLock) {
            state.bypassHostnameVerification = enabled;
        }
    }

    // ========== 自定义规则 ==========

    public String getCustomRules() {
        synchronized (stateLock) {
            return state.customRules;
        }
    }

    public void setCustomRules(String rules) {
        synchronized (stateLock) {
            state.customRules = rules != null ? rules : "";
        }
    }

    // ========== MCP 服务器配置 ==========

    public List<McpConfig> getMcpConfigs() {
        synchronized (stateLock) {
            return new ArrayList<>(state.mcpConfigs);
        }
    }

    public void setMcpConfigs(List<McpConfig> configs) {
        synchronized (stateLock) {
            state.mcpConfigs = new ArrayList<>(configs);
        }
    }

    public void addMcpConfig(McpConfig config) {
        synchronized (stateLock) {
            state.mcpConfigs.add(config);
        }
    }

    public void removeMcpConfig(int index) {
        synchronized (stateLock) {
            if (index >= 0 && index < state.mcpConfigs.size()) {
                state.mcpConfigs.remove(index);
            }
        }
    }

    public void updateMcpConfig(int index, McpConfig config) {
        synchronized (stateLock) {
            if (index >= 0 && index < state.mcpConfigs.size()) {
                state.mcpConfigs.set(index, config);
            }
        }
    }

    /**
     * 单个模型配置
     */
    public static class ModelConfig {
        public String name = "qwen3-max";          // 显示名称
        public String baseUrl = "";
        public String apiKey = "";
        /** Encrypted value used only by XML persistence; apiKey remains the runtime/UI value. */
        public String encryptedApiKey = "";
        public String modelName = "qwen3-max";
        public int compressionThreshold = 75;
        public boolean visionCapable = true;
        public int recentTurnsToKeep = 2;          // 压缩时保留的最近对话轮数
        public int contextWindowSize = 200000;     // 模型上下文窗口大小，用于压缩阈值计算
        public double temperature = 0.3;           // 模型温度参数
        public int maxTokens = 8192;               // 最大输出 Token 数

        public ModelConfig() {}

        public ModelConfig(String name, String baseUrl, String apiKey, String modelName) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.modelName = modelName;
            this.visionCapable = true;
        }

        public ModelConfig(String name, String baseUrl, String apiKey, String modelName, int compressionThreshold) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.modelName = modelName;
            this.compressionThreshold = compressionThreshold;
            this.visionCapable = true;
        }

        public ModelConfig(String name, String baseUrl, String apiKey, String modelName, int compressionThreshold, boolean visionCapable) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.modelName = modelName;
            this.compressionThreshold = compressionThreshold;
            this.visionCapable = visionCapable;
        }

        public ModelConfig copy() {
            ModelConfig c = new ModelConfig(name, baseUrl, apiKey, modelName, compressionThreshold, visionCapable);
            c.encryptedApiKey = this.encryptedApiKey;
            c.recentTurnsToKeep = this.recentTurnsToKeep;
            c.contextWindowSize = this.contextWindowSize;
            c.temperature = this.temperature;
            c.maxTokens = this.maxTokens;
            return c;
        }
    }

    /**
     * 通用键值对（用于 MCP 请求头 / 环境变量的 XML 持久化）
     */
    public static class HeaderEntry {
        public String key = "";
        public String value = "";

        public HeaderEntry() {}

        public HeaderEntry(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public HeaderEntry copy() {
            return new HeaderEntry(key, value);
        }
    }

    /**
     * 单个 MCP 服务器配置
     */
    public static class McpConfig {
        public String id = java.util.UUID.randomUUID().toString();
        public String name = "";
        public boolean enabled = true;
        public com.taiwei.aiagent.mcp.McpTransportType transportType = com.taiwei.aiagent.mcp.McpTransportType.STDIO;

        /** STDIO 传输：子进程命令、参数、环境变量 */
        public String command = "";
        public List<String> args = new ArrayList<>();
        public List<HeaderEntry> env = new ArrayList<>();

        /** SSE / STREAMABLE_HTTP 传输：服务地址、自定义请求头 */
        public String url = "";
        public List<HeaderEntry> headers = new ArrayList<>();

        /** 单次请求超时时间（秒） */
        public int timeoutSeconds = 30;

        /** 该服务器下被禁用的工具名称列表（MCP 原始工具名，不带前缀） */
        public List<String> disabledTools = new ArrayList<>();

        public McpConfig() {}

        public McpConfig copy() {
            McpConfig c = new McpConfig();
            c.id = id;
            c.name = name;
            c.enabled = enabled;
            c.transportType = transportType;
            c.command = command;
            c.args = new ArrayList<>(args);
            c.env = new ArrayList<>();
            for (HeaderEntry e : env) c.env.add(e.copy());
            c.url = url;
            c.headers = new ArrayList<>();
            for (HeaderEntry h : headers) c.headers.add(h.copy());
            c.timeoutSeconds = timeoutSeconds;
            c.disabledTools = new ArrayList<>(disabledTools);
            return c;
        }
    }

    /**
     * 配置状态（会被序列化到 XML）
     */
    public static class State {
        /**
         * 模型配置列表
         */
        public List<ModelConfig> modelConfigs = new ArrayList<>();

        /**
         * 当前选中的模型索引
         */
        public int activeModelIndex = 0;

        /**
         * 最大输出 Token 数
         */
        public int maxTokens = 8192;

        /**
         * 温度参数（0-2）
         */
        public double temperature = 0.7;

        /**
         * 危险命令模式列表（匹配到任意模式的命令需要用户手动确认执行）
         */
        public List<String> dangerousCommands = new ArrayList<>(java.util.Arrays.asList(
                "rm -rf", "mkfs.", "dd if=", ":(){", "shutdown", "reboot", "halt",
                "poweroff", "init 0", "init 6", "fdisk", "format", "mv /", "chmod -R 777 /",
                "wget http", "curl -o", "> /dev/sda", "| sh", "eval ", "source /dev"
        ));

        /**
         * 搜索引擎类型：LOW_COST（DuckDuckGo 免费）或 ALIYUN_IQS（阿里云 IQS）
         */
        public String searchEngineType = "LOW_COST";

        /**
         * 已禁用的 Skill 名称集合（禁用的 Skill 不会被注入系统提示词）
         */
        public java.util.Set<String> disabledSkills = new java.util.LinkedHashSet<>();

        /**
         * 已禁用的工具名称集合（禁用的工具不会提供给 LLM 调用）
         */
        public java.util.Set<String> disabledTools = new java.util.LinkedHashSet<>();

        /**
         * MCP（Model Context Protocol）服务器配置列表
         */
        public List<McpConfig> mcpConfigs = new ArrayList<>();

        /**
         * 是否启用自动补全
         */
        public boolean completionEnabled = true;

        /**
         * 是否启用 Git 提交评审（生成提交信息）
         */
        public boolean gitCommitReviewEnabled = true;

        /**
         * 是否启用 Inline Action（选中文本后显示浮动操作栏）
         */
        public boolean inlineActionEnabled = true;

        /**
         * 是否跳过 LLM API 请求的 TLS 主机名校验（默认关闭，仅用于自签名证书等特殊场景）
         */
        public boolean bypassHostnameVerification = false;

        /**
         * 用户自定义规则（全局），会注入到系统提示词中
         */
        public String customRules = "";

        public State() {
            // 默认添加一个模型
            modelConfigs.add(new ModelConfig());
        }
    }
}
