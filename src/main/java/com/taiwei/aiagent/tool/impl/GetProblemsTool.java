package com.taiwei.aiagent.tool.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码问题诊断工具
 * 通过 IDE 的 DaemonCodeAnalyzer 获取文件的实时编译错误和警告，
 * 秒级返回结构化结果，无需运行完整的 gradle/maven 编译。
 */
public class GetProblemsTool implements Tool {

    private static final Logger LOG = Logger.getInstance(GetProblemsTool.class);

    /** 等待代码分析完成的最长时间 */
    private static final long ANALYSIS_WAIT_TIMEOUT_MS = 15000;
    private static final long ANALYSIS_POLL_INTERVAL_MS = 200;
    private static final int MAX_PROBLEMS = 100;

    private final Project project;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public GetProblemsTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "get_problems";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.getProblems");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "要检查的文件路径（绝对路径或相对项目根目录的路径）。不传则检查所有已打开的文件"
                    },
                    "severity": {
                      "type": "string",
                      "enum": ["error", "warning"],
                      "description": "最低严重级别：error 只返回错误（默认），warning 同时返回警告"
                    }
                  }
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String path = args.has("path") ? args.get("path").getAsString() : null;
            String severityArg = args.has("severity") ? args.get("severity").getAsString() : "error";
            HighlightSeverity minSeverity = "warning".equalsIgnoreCase(severityArg)
                    ? HighlightSeverity.WARNING
                    : HighlightSeverity.ERROR;

            List<VirtualFile> targets = collectTargetFiles(path);
            if (targets.isEmpty()) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("analyzed_files", 0);
                output.put("total", 0);
                output.put("problems", List.of());
                output.put("note", path != null ? I18nUtil.getMessage("tool.problems.fileUnavailable", path) : I18nUtil.getMessage("tool.problems.noOpenFiles"));
                return gson.toJson(output);
            }

            List<Map<String, Object>> problems = new ArrayList<>();
            List<String> unfinished = new ArrayList<>();

            for (VirtualFile vFile : targets) {
                Document document = ReadAction.compute(() ->
                        FileDocumentManager.getInstance().getDocument(vFile));
                if (document == null) {
                    continue;
                }
                // DaemonCodeAnalyzer 只分析已在编辑器中打开的文件；未打开的目标文件先以
                // 非聚焦方式打开以触发分析（file_replace 编辑过的文件通常已被 Diff 模块打开）
                ensureOpenInEditor(vFile);
                if (!waitForAnalysis(vFile, document)) {
                    unfinished.add(relativePath(vFile));
                }
                collectHighlights(vFile, document, minSeverity, problems);
                if (problems.size() >= MAX_PROBLEMS) {
                    break;
                }
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("analyzed_files", targets.size());
            output.put("min_severity", minSeverity == HighlightSeverity.ERROR ? "error" : "warning");
            output.put("total", problems.size());
            output.put("problems", problems);
            if (!unfinished.isEmpty()) {
                output.put("note", I18nUtil.getMessage("tool.problems.incomplete", unfinished));
            }
            return gson.toJson(output);

        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Failed to collect code problems", e,
                    I18nUtil.getMessage("tool.problems.failed", e.getMessage()), I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    /**
     * 收集要检查的文件：指定 path 时为单个文件，否则为所有已打开的文件
     */
    private List<VirtualFile> collectTargetFiles(String path) {
        Set<VirtualFile> result = new LinkedHashSet<>();
        if (path != null && !path.isEmpty()) {
            Path resolved = resolvePath(path);
            if (Files.isRegularFile(resolved)) {
                VirtualFile vFile = LocalFileSystem.getInstance()
                        .refreshAndFindFileByPath(resolved.toString().replace('\\', '/'));
                if (vFile != null) {
                    result.add(vFile);
                }
            }
        } else {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                for (VirtualFile vFile : FileEditorManager.getInstance(project).getOpenFiles()) {
                    if (vFile.isValid() && !vFile.isDirectory()) {
                        result.add(vFile);
                    }
                }
            });
        }
        return new ArrayList<>(result);
    }

    private void ensureOpenInEditor(VirtualFile vFile) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            FileEditorManager fem = FileEditorManager.getInstance(project);
            if (!fem.isFileOpen(vFile)) {
                fem.openFile(vFile, false);
            }
        });
    }

    /**
     * 轮询等待 DaemonCodeAnalyzer 对该文件的错误分析结束
     *
     * @return 分析在超时前完成返回 true
     */
    private boolean waitForAnalysis(VirtualFile vFile, Document document) {
        long deadline = System.currentTimeMillis() + ANALYSIS_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Boolean finished = ReadAction.compute(() -> {
                PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
                if (psiFile == null) {
                    return Boolean.TRUE; // 非代码文件，无需等待
                }
                return DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(psiFile);
            });
            if (Boolean.TRUE.equals(finished)) {
                return true;
            }
            try {
                Thread.sleep(ANALYSIS_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void collectHighlights(VirtualFile vFile, Document document,
                                   HighlightSeverity minSeverity, List<Map<String, Object>> problems) {
        String relPath = relativePath(vFile);
        List<Map<String, Object>> collected = ReadAction.compute(() -> {
            List<Map<String, Object>> list = new ArrayList<>();
            List<HighlightInfo> infos = DaemonCodeAnalyzerImpl.getHighlights(document, minSeverity, project);
            for (HighlightInfo info : infos) {
                if (info.getDescription() == null || info.getDescription().isEmpty()) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("file", relPath);
                int offset = Math.min(info.getStartOffset(), Math.max(0, document.getTextLength() - 1));
                entry.put("line", document.getLineNumber(Math.max(0, offset)) + 1);
                entry.put("severity", info.getSeverity().getName().toLowerCase());
                entry.put("message", info.getDescription());
                list.add(entry);
            }
            return list;
        });
        for (Map<String, Object> entry : collected) {
            if (problems.size() >= MAX_PROBLEMS) {
                return;
            }
            problems.add(entry);
        }
    }

    private String relativePath(VirtualFile vFile) {
        try {
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir != null) {
                String rel = VfsUtilCore.getRelativePath(vFile, baseDir);
                if (rel != null) {
                    return rel;
                }
            }
        } catch (Exception ignored) {
        }
        return vFile.getPath();
    }

    private Path resolvePath(String filePath) {
        Path path = Paths.get(filePath);
        if (path.isAbsolute()) {
            return path;
        }
        String basePath = project.getBasePath();
        if (basePath != null) {
            return Paths.get(basePath, filePath);
        }
        return path;
    }
}
