package com.taiwei.aiagent.agent.prompt;

import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.agent.AgentMode;
import com.taiwei.aiagent.memory.MemoryManager;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.skill.SkillManager;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Prompt 模板管理器
 * 使用 Velocity 模板引擎渲染系统提示词
 */
public class PromptManager {

    private static final int RELEVANT_MEMORY_LIMIT = 8;
    private static final int AGENTS_MD_MAX_LENGTH = 50000;

    private final Project project;
    private final VelocityEngine velocityEngine;
    private final SkillManager skillManager;
    private final MemoryManager memoryManager;
    private final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

    private volatile String cachedPlatform;
    private volatile String cachedWorkingDir;
    private volatile String cachedIsGitRepo;
    private volatile String cachedModel;

    public PromptManager(Project project) {
        this.project = project;
        this.velocityEngine = createVelocityEngine();
        this.skillManager = SkillManager.getInstance(project);
        this.memoryManager = MemoryManager.getInstance(project);
    }

    private VelocityEngine createVelocityEngine() {
        Properties props = new Properties();
        props.setProperty("resource.loaders", "string");
        props.setProperty("resource.loader.string.class", "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(PromptManager.class.getClassLoader());
            VelocityEngine engine = new VelocityEngine();
            engine.init(props);
            return engine;
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * 生成系统提示词（不注入相关记忆，保证系统提示词在整个会话内字节完全一致，从而命中前缀缓存）
     *
     * @param mode 当前 Agent 模式（Plan/Build），决定提示词中工具能力描述与行为约束
     */
    public String buildSystemPrompt(AgentMode mode) {
        VelocityContext context = new VelocityContext();

        context.put("workingDir", getWorkingDir());
        context.put("isGitRepo", getIsGitRepo());
        context.put("platform", getPlatform());
        context.put("today", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        context.put("model", getModel());

        String skillsContext = buildSkillsContext();
        if (skillsContext != null && !skillsContext.isEmpty()) {
            context.put("skills", skillsContext);
        }

        String rulesContext = buildRulesContext();
        if (rulesContext != null && !rulesContext.isEmpty()) {
            context.put("rules", rulesContext);
        }

        String agentsMd = loadAgentsMd();
        if (agentsMd != null && !agentsMd.isEmpty()) {
            context.put("agentsMd", agentsMd);
        }

        String templateName = mode == AgentMode.PLAN ? "templates/system_prompt_plan.vm" : "templates/system_prompt_build.vm";
        String templateContent = loadTemplateContent(templateName);
        StringWriter writer = new StringWriter();
        velocityEngine.evaluate(context, writer, "system_prompt", templateContent);
        return writer.toString();
    }

    /**
     * 检索与用户消息相关的长期记忆，供调用方追加到当前用户消息末尾。
     * 将记忆从系统提示词移到用户消息尾部，可保证系统提示词字节完全一致，从而命中前缀缓存。
     *
     * @param userMessage 当前用户消息，用于语义检索相关记忆
     * @return 已格式化的记忆上下文字符串；无相关记忆时返回空字符串
     */
    public String buildRelevantMemoryContext(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "";
        String memoryContext = memoryManager.buildPromptContext(userMessage, RELEVANT_MEMORY_LIMIT);
        return memoryContext != null ? memoryContext : "";
    }

    /**
     * @deprecated 直接调用 {@link #buildSystemPrompt(AgentMode)}；记忆内容请通过
     *             {@link #buildRelevantMemoryContext(String)} 获取后追加到用户消息末尾。
     */
    @Deprecated
    public String buildSystemPrompt(AgentMode mode, String userMessage) {
        return buildSystemPrompt(mode);
    }

    /**
     * 生成 /init 命令用于生成 AGENTS.md 的提示词
     *
     * @param scanSummary      项目结构扫描结果（目录树、模块、关键配置文件内容）
     * @param existingAgentsMd 已存在的 AGENTS.md 内容（不存在则为 null）
     */
    public String buildInitPrompt(String scanSummary, String existingAgentsMd) {
        VelocityContext context = new VelocityContext();

        String basePath = project.getBasePath();
        context.put("workingDir", basePath != null ? basePath : "未知");
        context.put("platform", detectPlatform());
        context.put("today", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        context.put("scanSummary", scanSummary != null ? scanSummary : "");

        boolean hasExisting = existingAgentsMd != null && !existingAgentsMd.isEmpty();
        context.put("hasExisting", hasExisting);
        context.put("existingContent", hasExisting ? existingAgentsMd : "");

        String templateContent = loadTemplateContent("templates/init_prompt.vm");
        StringWriter writer = new StringWriter();
        velocityEngine.evaluate(context, writer, "init_prompt", templateContent);
        return writer.toString();
    }

    /**
     * 生成上下文压缩提示词
     *
     * @param conversationContent 待压缩的对话内容（已格式化的角色+内容文本）
     */
    public String buildCompressPrompt(String conversationContent) {
        VelocityContext context = new VelocityContext();
        context.put("conversationContent", conversationContent != null ? conversationContent : "");

        String templateContent = loadTemplateContent("templates/compress_prompt.vm");
        StringWriter writer = new StringWriter();
        velocityEngine.evaluate(context, writer, "compress_prompt", templateContent);
        return writer.toString();
    }

    /**
     * 构建规则上下文：合并全局规则（Settings）和项目级规则（.taiwei/rules.md）
     */
    public String buildRulesContext() {
        StringBuilder sb = new StringBuilder();

        // 1. 全局规则（Settings 中配置）
        String globalRules;
        try {
            globalRules = AiAgentSettings.getInstance().getCustomRules();
        } catch (Exception e) {
            globalRules = "";
        }
        if (globalRules != null && !globalRules.isBlank()) {
            sb.append(globalRules.trim());
        }

        // 2. 项目级规则（.taiwei/rules.md）
        String projectRules = loadProjectRules();
        if (projectRules != null && !projectRules.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(projectRules.trim());
        }

        return sb.toString();
    }

    /**
     * 读取项目级规则文件（.taiwei/rules.md）
     */
    private String loadProjectRules() {
        String basePath = project.getBasePath();
        if (basePath == null) return null;

        Path rulesFile = Paths.get(basePath, ".taiwei", "rules.md");
        if (!Files.exists(rulesFile)) return null;

        try {
            return Files.readString(rulesFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取项目根目录 AGENTS.md（不缓存，每次实时读取，因为 /init 可能在会话中重新生成该文件）
     * 超过 {@link #AGENTS_MD_MAX_LENGTH} 时截断并附加提示
     */
    private String loadAgentsMd() {
        String basePath = project.getBasePath();
        if (basePath == null) return null;

        Path agentsMdFile = Paths.get(basePath, "AGENTS.md");
        if (!Files.exists(agentsMdFile)) return null;

        try {
            String content = Files.readString(agentsMdFile, StandardCharsets.UTF_8);
            if (content.length() > AGENTS_MD_MAX_LENGTH) {
                String truncated = content.substring(0, AGENTS_MD_MAX_LENGTH);
                return truncated + "\n\n[内容已截断：AGENTS.md 文件过大（实际 " + content.length()
                        + " 字符，仅显示前 " + AGENTS_MD_MAX_LENGTH + " 字符）]";
            }
            return content;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建技能上下文，用于注入系统提示词（仅 name/description/tags，完整内容按需加载）
     */
    public String buildSkillsContext() {
        return skillManager.buildSummaryContext();
    }

    private String loadTemplateContent(String resourcePath) {
        return templateCache.computeIfAbsent(resourcePath, path -> {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
                if (is == null) {
                    throw new RuntimeException("Template not found: " + path);
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load template: " + path, e);
            }
        });
    }

    private String getPlatform() {
        String v = cachedPlatform;
        if (v == null) {
            v = detectPlatform();
            cachedPlatform = v;
        }
        return v;
    }

    private String getWorkingDir() {
        String v = cachedWorkingDir;
        if (v == null) {
            String basePath = project.getBasePath();
            v = basePath != null ? basePath : "未知";
            cachedWorkingDir = v;
        }
        return v;
    }

    private String getIsGitRepo() {
        String v = cachedIsGitRepo;
        if (v == null) {
            String basePath = project.getBasePath();
            boolean isGit = basePath != null && new File(basePath, ".git").exists();
            v = isGit ? "Yes" : "No";
            cachedIsGitRepo = v;
        }
        return v;
    }

    private String getModel() {
        String v = cachedModel;
        if (v == null) {
            try {
                v = AiAgentSettings.getInstance().getModel();
            } catch (Exception e) {
                v = "未知";
            }
            if (v == null) v = "未知";
            cachedModel = v;
        }
        return v;
    }

    private String detectPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "macos";
        } else if (osName.contains("linux")) {
            return "linux";
        } else if (osName.contains("win")) {
            return "windows";
        } else {
            return osName;
        }
    }
}
