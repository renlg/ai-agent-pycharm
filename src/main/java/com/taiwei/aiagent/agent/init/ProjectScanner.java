package com.taiwei.aiagent.agent.init;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 项目结构扫描器
 * 为 /init 命令收集目录树、模块结构与关键构建/依赖文件内容，供 LLM 生成 AGENTS.md 使用
 */
public final class ProjectScanner {

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".idea", ".gradle", "build", "out", "target",
            "node_modules", "dist", ".taiwei", ".taiwei-ide-sandbox"
    );

    private static final String[] KEY_FILE_NAMES = {
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "pom.xml", "package.json", "go.mod", "requirements.txt", "pyproject.toml", "Cargo.toml"
    };

    private static final int MAX_DEPTH = 4;
    private static final int MAX_TREE_ENTRIES = 500;
    private static final int MAX_FILE_PREVIEW_CHARS = 3000;

    private ProjectScanner() {
    }

    /**
     * 扫描项目，返回供 LLM 使用的结构化文本摘要
     */
    public static String scan(Project project) {
        return ReadAction.compute(() -> {
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir == null) {
                return "(无法获取项目根目录)";
            }

            StringBuilder sb = new StringBuilder();

            sb.append("### 模块结构\n");
            sb.append(scanModules(project));
            sb.append("\n");

            sb.append("### 目录结构（已忽略构建产物/依赖目录，最多显示 ").append(MAX_TREE_ENTRIES).append(" 条）\n");
            sb.append("```\n");
            buildTree(baseDir, "", sb, 0, new int[]{0});
            sb.append("```\n\n");

            sb.append("### 关键构建/依赖文件内容\n");
            sb.append(scanKeyFiles(project, baseDir));

            return sb.toString();
        });
    }

    private static String scanModules(Project project) {
        Module[] modules = ModuleManager.getInstance(project).getModules();
        if (modules.length == 0) {
            return "(未检测到模块)\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Module module : modules) {
            sb.append("- ").append(module.getName());
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            VirtualFile[] sourceRoots = rootManager.getSourceRoots();
            if (sourceRoots.length > 0) {
                List<String> names = new ArrayList<>();
                for (VirtualFile root : sourceRoots) {
                    names.add(root.getName());
                }
                sb.append(" (源码根: ").append(String.join(", ", names)).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void buildTree(VirtualFile dir, String prefix, StringBuilder sb, int depth, int[] count) {
        if (count[0] > MAX_TREE_ENTRIES) {
            return;
        }
        VirtualFile[] children = dir.getChildren();
        VirtualFile[] sorted = Arrays.copyOf(children, children.length);
        Arrays.sort(sorted, Comparator
                .comparing((VirtualFile f) -> !f.isDirectory())
                .thenComparing(VirtualFile::getName));

        for (VirtualFile child : sorted) {
            if (count[0] > MAX_TREE_ENTRIES) {
                sb.append(prefix).append("... (已达显示上限，省略其余文件)\n");
                return;
            }
            String name = child.getName();
            if (child.isDirectory()) {
                if (name.startsWith(".") || IGNORED_DIRS.contains(name)) {
                    continue;
                }
                sb.append(prefix).append(name).append("/\n");
                count[0]++;
                if (depth < MAX_DEPTH) {
                    buildTree(child, prefix + "  ", sb, depth + 1, count);
                }
            } else {
                sb.append(prefix).append(name).append("\n");
                count[0]++;
            }
        }
    }

    private static String scanKeyFiles(Project project, VirtualFile baseDir) {
        StringBuilder sb = new StringBuilder();
        for (String name : KEY_FILE_NAMES) {
            VirtualFile file = baseDir.findChild(name);
            if (file != null && !file.isDirectory()) {
                sb.append("#### ").append(name).append("\n```\n");
                sb.append(readPreview(file));
                sb.append("\n```\n\n");
            }
        }

        VirtualFile pluginXml = baseDir.findFileByRelativePath("src/main/resources/META-INF/plugin.xml");
        if (pluginXml != null) {
            sb.append("#### src/main/resources/META-INF/plugin.xml\n```\n");
            sb.append(readPreview(pluginXml));
            sb.append("\n```\n\n");
        }

        if (sb.length() == 0) {
            return "(未检测到常见的构建/依赖文件)\n";
        }
        return sb.toString();
    }

    private static String readPreview(VirtualFile file) {
        try {
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            if (content.length() > MAX_FILE_PREVIEW_CHARS) {
                return content.substring(0, MAX_FILE_PREVIEW_CHARS) + "\n... [内容过长，已截断]";
            }
            return content;
        } catch (Exception e) {
            return "(读取失败: " + e.getMessage() + ")";
        }
    }
}
