package com.taiwei.aiagent.agent.context;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析用户消息中的 @ 上下文引用。
 * <p>
 * 支持以下引用类型：
 * <ul>
 *   <li>{@code @相对路径} / {@code @绝对路径} — 引用项目中的文件或目录</li>
 *   <li>{@code @codebase} — 注入项目结构概览</li>
 *   <li>{@code @git} — 注入 Git 上下文（分支、状态、最近提交）</li>
 *   <li>{@code @web} — 提示 AI 使用 web_search 工具搜索网络</li>
 * </ul>
 * 路径不存在的 @ 片段（如邮箱、注解）会被安全忽略。
 */
public final class ContextMentionResolver {

    /** @后面的路径字符：字母数字、中文、路径分隔符、点、横线、下划线 */
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("@([\\w\\p{IsHan}./\\\\-]+)");

    private static final int MAX_MENTIONS = 10;
    private static final int MAX_FILE_CHARS = 20000;
    private static final int MAX_DIR_ENTRIES = 100;
    private static final int MAX_GIT_LOG = 10;
    private static final int MAX_CODEBASE_FILES = 300;

    /** 关键字提及及其描述 */
    private static final Map<String, String> KEYWORD_MENTIONS = new LinkedHashMap<>();

    static {
        KEYWORD_MENTIONS.put("codebase", "项目结构概览");
        KEYWORD_MENTIONS.put("git", "Git 上下文（分支、状态、最近提交）");
        KEYWORD_MENTIONS.put("web", "网络搜索");
    }

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".idea", ".gradle", "build", "out", "target",
            "node_modules", "dist", ".taiwei", ".taiwei-ide-sandbox",
            "__pycache__", ".venv", "venv", ".next", ".nuxt"
    );

    private ContextMentionResolver() {
    }

    // ========== 提及建议 API ==========

    /**
     * 获取所有可用的 @ 提及建议（供前端自动补全使用）
     *
     * @param query 用户已输入的 @ 后面的文本（可为空），用于过滤
     * @return 匹配的提及建议列表
     */
    public static List<MentionSuggestion> getSuggestions(String query) {
        List<MentionSuggestion> result = new ArrayList<>();
        String lower = query != null ? query.toLowerCase() : "";

        for (Map.Entry<String, String> entry : KEYWORD_MENTIONS.entrySet()) {
            if (lower.isEmpty() || entry.getKey().startsWith(lower)) {
                result.add(new MentionSuggestion(entry.getKey(), entry.getValue(), "keyword"));
            }
        }

        if ("file".startsWith(lower) || lower.isEmpty()) {
            result.add(new MentionSuggestion("file", "引用文件（输入路径）", "path"));
        }
        if ("folder".startsWith(lower) || lower.isEmpty()) {
            result.add(new MentionSuggestion("folder", "引用目录（输入路径）", "path"));
        }

        return result;
    }

    // ========== 消息增强（Project 版本） ==========

    /**
     * 解析消息中的 @ 引用并返回附加了上下文内容的消息。
     * 无有效引用时原样返回。
     *
     * @param project     当前项目（可为 null）
     * @param userMessage 原始用户消息
     */
    public static String augment(Project project, String userMessage) {
        String basePath = project != null ? project.getBasePath() : null;
        return doAugment(basePath, project, userMessage);
    }

    /**
     * 解析消息中的 @ 引用（使用 basePath 字符串，供测试使用）
     *
     * @param basePath    项目根目录（可为 null）
     * @param userMessage 原始用户消息
     */
    public static String augment(String basePath, String userMessage) {
        return doAugment(basePath, null, userMessage);
    }

    private static String doAugment(String basePath, Project project, String userMessage) {
        if (userMessage == null || userMessage.indexOf('@') < 0) {
            return userMessage;
        }

        Set<String> mentions = new LinkedHashSet<>();
        Matcher matcher = MENTION_PATTERN.matcher(userMessage);
        while (matcher.find() && mentions.size() < MAX_MENTIONS) {
            mentions.add(matcher.group(1));
        }
        if (mentions.isEmpty()) {
            return userMessage;
        }

        StringBuilder attachments = new StringBuilder();

        for (String mention : mentions) {
            String lower = mention.toLowerCase();

            // 关键字提及
            if ("codebase".equals(lower)) {
                String codebase = resolveCodebase(project);
                if (codebase != null && !codebase.isEmpty()) {
                    attachments.append(codebase);
                }
                continue;
            }
            if ("git".equals(lower)) {
                String gitCtx = resolveGit(basePath);
                if (gitCtx != null && !gitCtx.isEmpty()) {
                    attachments.append(gitCtx);
                }
                continue;
            }
            if ("web".equals(lower)) {
                attachments.append("<web_search_hint>\n")
                        .append("用户请求了网络搜索。请使用 web_search 工具搜索相关信息来回答用户的问题。\n")
                        .append("</web_search_hint>\n");
                continue;
            }

            // 路径引用（文件/目录）
            Path resolved = resolve(basePath, mention);
            if (resolved == null || !Files.exists(resolved)) {
                continue;
            }
            try {
                if (Files.isDirectory(resolved)) {
                    attachments.append("<directory path=\"").append(mention).append("\">\n")
                            .append(listDirectory(resolved))
                            .append("</directory>\n");
                } else if (Files.isRegularFile(resolved)) {
                    String content = Files.readString(resolved, StandardCharsets.UTF_8);
                    if (content.length() > MAX_FILE_CHARS) {
                        content = content.substring(0, MAX_FILE_CHARS)
                                + "\n... [文件过长，已截断，可用 read_file 指定行范围查看其余部分]";
                    }
                    attachments.append("<file path=\"").append(mention).append("\">\n")
                            .append(content);
                    if (!content.endsWith("\n")) {
                        attachments.append("\n");
                    }
                    attachments.append("</file>\n");
                }
            } catch (Exception e) {
                // 单个引用读取失败不影响其余引用
            }
        }

        if (attachments.length() == 0) {
            return userMessage;
        }

        return userMessage
                + "\n\n---\n以下是消息中通过 @ 引用附加的上下文内容（系统自动注入）：\n"
                + attachments;
    }

    // ========== 关键字提及解析 ==========

    /**
     * 解析 @codebase：生成项目结构概览
     */
    private static String resolveCodebase(Project project) {
        if (project == null) return null;
        try {
            return ReadAction.compute(() -> {
                VirtualFile baseDir = project.getBaseDir();
                if (baseDir == null) return null;

                StringBuilder sb = new StringBuilder();
                sb.append("<codebase_overview>\n");
                sb.append("项目根目录: ").append(project.getBasePath()).append("\n\n");
                sb.append("目录结构:\n```\n");
                int[] count = {0};
                buildCodebaseTree(baseDir, "", sb, 0, count);
                sb.append("```\n");
                sb.append("</codebase_overview>\n");
                return sb.toString();
            });
        } catch (Exception e) {
            return null;
        }
    }

    private static void buildCodebaseTree(VirtualFile dir, String prefix, StringBuilder sb, int depth, int[] count) {
        if (count[0] > MAX_CODEBASE_FILES || depth > 4) return;

        VirtualFile[] children = dir.getChildren();
        VirtualFile[] sorted = Arrays.copyOf(children, children.length);
        Arrays.sort(sorted, Comparator
                .comparing((VirtualFile f) -> !f.isDirectory())
                .thenComparing(VirtualFile::getName));

        for (VirtualFile child : sorted) {
            if (count[0] > MAX_CODEBASE_FILES) {
                sb.append(prefix).append("... (已省略其余文件)\n");
                return;
            }
            String name = child.getName();
            if (child.isDirectory()) {
                if (name.startsWith(".") || IGNORED_DIRS.contains(name)) continue;
                sb.append(prefix).append(name).append("/\n");
                count[0]++;
                buildCodebaseTree(child, prefix + "  ", sb, depth + 1, count);
            } else {
                sb.append(prefix).append(name).append("\n");
                count[0]++;
            }
        }
    }

    /**
     * 解析 @git：收集 Git 上下文
     */
    private static String resolveGit(String basePath) {
        if (basePath == null) return null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("<git_context>\n");

            String branch = runGit(basePath, "rev-parse", "--abbrev-ref", "HEAD");
            if (branch != null) {
                sb.append("当前分支: ").append(branch.trim()).append("\n");
            }

            String status = runGit(basePath, "status", "--short");
            if (status != null && !status.trim().isEmpty()) {
                sb.append("\n工作区状态 (git status --short):\n").append(status.trim()).append("\n");
            } else {
                sb.append("\n工作区状态: 干净（无未提交的更改）\n");
            }

            String log = runGit(basePath, "log", "--oneline", "-n", String.valueOf(MAX_GIT_LOG));
            if (log != null && !log.trim().isEmpty()) {
                sb.append("\n最近 ").append(MAX_GIT_LOG).append(" 次提交:\n").append(log.trim()).append("\n");
            }

            String diffStat = runGit(basePath, "diff", "--cached", "--stat");
            if (diffStat != null && !diffStat.trim().isEmpty()) {
                sb.append("\n暂存区变更统计:\n").append(diffStat.trim()).append("\n");
            }

            sb.append("</git_context>\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String runGit(String basePath, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            for (String arg : args) {
                command.add(arg);
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(basePath));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return output.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ========== 路径引用解析 ==========

    private static Path resolve(String basePath, String mention) {
        try {
            Path path = Paths.get(mention);
            if (path.isAbsolute()) {
                return path.normalize();
            }
            if (basePath == null) {
                return null;
            }
            return Paths.get(basePath, mention).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String listDirectory(Path dir) {
        StringBuilder sb = new StringBuilder();
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                entries.add(entry);
                if (entries.size() >= MAX_DIR_ENTRIES) {
                    break;
                }
            }
        } catch (Exception e) {
            return "(目录读取失败: " + e.getMessage() + ")\n";
        }
        entries.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        for (Path entry : entries) {
            sb.append(entry.getFileName().toString());
            if (Files.isDirectory(entry)) {
                sb.append("/");
            }
            sb.append("\n");
        }
        if (entries.size() >= MAX_DIR_ENTRIES) {
            sb.append("... [目录条目过多，已截断]\n");
        }
        return sb.toString();
    }

    /**
     * 提及建议
     */
    public static class MentionSuggestion {
        private final String keyword;
        private final String description;
        private final String type;

        public MentionSuggestion(String keyword, String description, String type) {
            this.keyword = keyword;
            this.description = description;
            this.type = type;
        }

        public String getKeyword() { return keyword; }
        public String getDescription() { return description; }
        public String getType() { return type; }
    }
}
