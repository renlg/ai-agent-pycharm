package com.taiwei.aiagent.tool.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Query;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引用查询工具
 * Agent 可以通过此工具查询项目中指定符号（类/方法/字段）的所有引用位置
 * 利用 IDEA 的 Find Usages API (ReferencesSearch) 实现精确引用定位
 */
public class FindReferencesTool implements Tool {

    private static final Logger LOG = Logger.getInstance(FindReferencesTool.class);

    private final Project project;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public FindReferencesTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "find_references";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.findReferences");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "symbol_name": {
                      "type": "string",
                      "description": "要查询引用的符号名称（方法名、类名或字段名）"
                    },
                    "symbol_kind": {
                      "type": "string",
                      "description": "符号类型：class（类）、method（方法）、field（字段），留空则搜索所有类型"
                    },
                    "file_pattern": {
                      "type": "string",
                      "description": "引用文件过滤（glob 模式，如 '*.java'，可选）"
                    },
                    "max_results": {
                      "type": "integer",
                      "description": "最大返回结果数（默认 50）"
                    }
                  },
                  "required": ["symbol_name"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        if (!com.taiwei.aiagent.util.JavaPluginAvailability.isJavaPluginAvailable()) {
            return ToolError.of("DEPENDENCY_UNAVAILABLE", I18nUtil.getMessage("tool.javaPlugin.unavailable"),
                    I18nUtil.getMessage("tool.javaPlugin.useSearchCode"));
        }
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String symbolName = args.get("symbol_name").getAsString();
            String symbolKind = args.has("symbol_kind") ? args.get("symbol_kind").getAsString() : null;
            String filePattern = args.has("file_pattern") ? args.get("file_pattern").getAsString() : null;
            int maxResults = args.has("max_results") ? args.get("max_results").getAsInt() : 50;

            return ReadAction.compute(() -> {
                GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
                PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
                List<PsiElement> targetElements = new ArrayList<>();

                // 1. 先根据名称和类型找到目标 PSI 元素
                if (symbolKind == null || symbolKind.isEmpty() || symbolKind.equals("method")) {
                    for (PsiMethod method : cache.getMethodsByName(symbolName, scope)) {
                        targetElements.add(method);
                    }
                }
                if ((symbolKind == null || symbolKind.isEmpty() || symbolKind.equals("class")) && targetElements.size() < 10) {
                    for (PsiClass cls : cache.getClassesByName(symbolName, scope)) {
                        targetElements.add(cls);
                    }
                }
                if ((symbolKind == null || symbolKind.isEmpty() || symbolKind.equals("field")) && targetElements.size() < 10) {
                    for (PsiField field : cache.getFieldsByName(symbolName, scope)) {
                        targetElements.add(field);
                    }
                }

                if (targetElements.isEmpty()) {
                    return ToolError.of("SYMBOL_NOT_FOUND", I18nUtil.getMessage("tool.references.notFound", symbolName),
                            I18nUtil.getMessage("tool.javaPlugin.useSearchCode"));
                }

                // 2. 对每个目标元素搜索引用
                List<Map<String, Object>> results = new ArrayList<>();
                for (PsiElement target : targetElements) {
                    if (results.size() >= maxResults) break;

                    Query<PsiReference> query = ReferencesSearch.search(target, scope);
                    for (PsiReference ref : query) {
                        if (results.size() >= maxResults) break;

                        Map<String, Object> entry = buildReferenceEntry(ref, target);
                        if (entry != null) {
                            // 文件过滤
                            if (filePattern != null && !filePattern.isEmpty()) {
                                String fileName = (String) entry.get("file");
                                if (fileName != null) {
                                    String regex = filePattern
                                            .replace(".", "\\.")
                                            .replace("*", ".*")
                                            .replace("?", ".");
                                    if (!fileName.matches(regex) && !fileName.endsWith(filePattern.replace("*", ""))) {
                                        continue;
                                    }
                                }
                            }
                            results.add(entry);
                        }
                    }
                }

                // 构建统一 JSON 输出
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("symbol_name", symbolName);
                output.put("symbol_type", symbolKind != null ? symbolKind : "any");
                output.put("total", results.size());
                output.put("results", results);

                return gson.toJson(output);
            });

        } catch (Throwable e) {
            return ToolError.unexpected(LOG, "Reference search failed", e,
                    I18nUtil.getMessage("tool.references.failed", e.getMessage()), I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    /**
     * 构建引用位置的结构化条目
     */
    private Map<String, Object> buildReferenceEntry(PsiReference ref, PsiElement target) {
        try {
            PsiElement element = ref.getElement();
            // 找到引用所在的最小方法或类作为上下文
            PsiFile psiFile = element.getContainingFile();
            if (psiFile == null) return null;

            VirtualFile vFile = psiFile.getVirtualFile();
            if (vFile == null) return null;

            String relativePath = VfsUtilCore.getRelativePath(vFile, project.getBaseDir());
            if (relativePath == null) return null;

            int lineNumber = 1;
            try {
                lineNumber = PsiDocumentManager.getInstance(project)
                        .getDocument(psiFile)
                        .getLineNumber(element.getTextOffset()) + 1;
            } catch (Exception ignored) {
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", relativePath);
            entry.put("line", lineNumber);
            entry.put("symbol_type", getTargetType(target));

            // 引用所在的上下文（方法/类名）
            PsiElement context = PsiTreeUtil.getParentOfType(element, PsiMethod.class, PsiClass.class);
            if (context instanceof PsiMethod) {
                PsiClass containingClass = ((PsiMethod) context).getContainingClass();
                String ctx = (containingClass != null ? containingClass.getName() + "." : "")
                        + ((PsiMethod) context).getName();
                entry.put("context", ctx);
            } else if (context instanceof PsiClass) {
                entry.put("context", ((PsiClass) context).getName());
            } else {
                entry.put("context", "");
            }

            // 引用行上下文预览
            entry.put("preview_lines", extractPreviewLines(psiFile, lineNumber, 2));

            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取目标符号的类型字符串
     */
    private String getTargetType(PsiElement element) {
        if (element instanceof PsiMethod) return "method";
        if (element instanceof PsiClass) return "class";
        if (element instanceof PsiField) return "field";
        return "other";
    }

    /**
     * 提取指定行上下文的预览行
     */
    private List<String> extractPreviewLines(PsiFile psiFile, int lineNumber, int contextLines) {
        List<String> preview = new ArrayList<>();
        try {
            com.intellij.openapi.editor.Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
            if (document != null) {
                int totalLines = document.getLineCount();
                int start = Math.max(0, lineNumber - 1 - contextLines);
                int end = Math.min(totalLines, lineNumber - 1 + contextLines + 1);
                for (int i = start; i < end; i++) {
                    String line = document.getText().substring(
                            document.getLineStartOffset(i),
                            document.getLineEndOffset(i)
                    );
                    preview.add((i == lineNumber - 1 ? ">>> " : "    ") + line);
                }
            }
        } catch (Exception ignored) {
        }
        return preview;
    }
}
