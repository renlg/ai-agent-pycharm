package com.taiwei.aiagent.tool.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跳转到定义工具
 * 使用 IntelliJ PSI 索引查找符号声明，返回文件路径、行号和完整声明源码
 */
public class GoToDefinitionTool implements Tool {

    private static final Logger LOG = Logger.getInstance(GoToDefinitionTool.class);

    private final Project project;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public GoToDefinitionTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "go_to_definition";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.goToDefinition");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "symbol": {
                      "type": "string",
                      "description": "要查找定义的符号名称（类名、方法名或字段名）"
                    },
                    "symbol_kind": {
                      "type": "string",
                      "description": "符号类型：class、method、field，留空则搜索所有类型"
                    },
                    "file_hint": {
                      "type": "string",
                      "description": "可选：文件路径提示（部分路径即可），用于在多个候选中优先返回该文件中的定义"
                    }
                  },
                  "required": ["symbol"]
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
            String symbol = args.get("symbol").getAsString();
            String symbolKind = args.has("symbol_kind") ? args.get("symbol_kind").getAsString() : null;
            String fileHint = args.has("file_hint") ? args.get("file_hint").getAsString() : null;

            return ReadAction.compute(() -> {
                GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
                PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
                List<PsiElement> candidates = new ArrayList<>();

                boolean searchAll = symbolKind == null || symbolKind.isEmpty();

                if (searchAll || "class".equals(symbolKind)) {
                    for (PsiClass cls : cache.getClassesByName(symbol, scope)) {
                        candidates.add(cls);
                    }
                }
                if ((searchAll || "method".equals(symbolKind)) && candidates.isEmpty()) {
                    for (PsiMethod method : cache.getMethodsByName(symbol, scope)) {
                        candidates.add(method);
                    }
                }
                if ((searchAll || "field".equals(symbolKind)) && candidates.isEmpty()) {
                    for (PsiField field : cache.getFieldsByName(symbol, scope)) {
                        candidates.add(field);
                    }
                }

                if (candidates.isEmpty()) {
                    return ToolError.of("SYMBOL_NOT_FOUND", I18nUtil.getMessage("tool.definition.notFound", symbol),
                            I18nUtil.getMessage("tool.definition.notFoundHint"));
                }

                // Prefer the candidate in the hinted file when hint is provided
                PsiElement best = null;
                if (fileHint != null && !fileHint.isEmpty()) {
                    for (PsiElement e : candidates) {
                        PsiFile f = e.getContainingFile();
                        if (f != null && f.getVirtualFile() != null) {
                            if (f.getVirtualFile().getPath().contains(fileHint)) {
                                best = e;
                                break;
                            }
                        }
                    }
                }
                if (best == null) best = candidates.get(0);

                PsiFile psiFile = best.getContainingFile();
                if (psiFile == null) {
                    return ToolError.of("DEFINITION_FILE_UNAVAILABLE", I18nUtil.getMessage("tool.definition.fileUnavailable"),
                            I18nUtil.getMessage("tool.javaPlugin.useSearchCode"));
                }

                VirtualFile vFile = psiFile.getVirtualFile();
                String relativePath = vFile != null
                        ? VfsUtilCore.getRelativePath(vFile, project.getBaseDir())
                        : psiFile.getName();
                if (relativePath == null && vFile != null) relativePath = vFile.getPath();

                int lineNumber = 1;
                try {
                    lineNumber = PsiDocumentManager.getInstance(project)
                            .getDocument(psiFile)
                            .getLineNumber(best.getTextOffset()) + 1;
                } catch (Exception ignored) {
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("symbol", symbol);
                result.put("file", relativePath);
                result.put("line", lineNumber);
                result.put("total_candidates", candidates.size());
                if (candidates.size() > 1) {
                    List<String> otherFiles = new ArrayList<>();
                    for (PsiElement c : candidates) {
                        if (c == best) continue;
                        PsiFile cf = c.getContainingFile();
                        if (cf != null && cf.getVirtualFile() != null) {
                            String rp = VfsUtilCore.getRelativePath(cf.getVirtualFile(), project.getBaseDir());
                            otherFiles.add(rp != null ? rp : cf.getVirtualFile().getPath());
                        }
                    }
                    if (!otherFiles.isEmpty()) result.put("other_definitions", otherFiles);
                }
                result.put("source_code", best.getText());

                return gson.toJson(result);
            });

        } catch (Throwable e) {
            return ToolError.unexpected(LOG, "Go to definition failed", e,
                    I18nUtil.getMessage("tool.definition.failed", e.getMessage()), I18nUtil.getMessage("tool.javaPlugin.useSearchCode"));
        }
    }
}
