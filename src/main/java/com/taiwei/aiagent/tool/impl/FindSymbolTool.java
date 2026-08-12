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
import java.util.regex.Pattern;

/**
 * 符号搜索工具
 * Agent 可以通过此工具在项目中按符号名称（类/方法/字段）精确查找定义位置
 * 利用 IntelliJ PSI API 实现高效符号级搜索
 */
public class FindSymbolTool implements Tool {

    private static final Logger LOG = Logger.getInstance(FindSymbolTool.class);

    private final Project project;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public FindSymbolTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "find_symbol";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.findSymbol");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "要搜索的符号名称（类名、方法名或字段名）"
                    },
                    "symbol_kind": {
                      "type": "string",
                      "description": "符号类型：class（类）、method（方法）、field（字段），留空则搜索所有类型"
                    },
                    "file_pattern": {
                      "type": "string",
                      "description": "文件名过滤（glob 模式，如 '*.java'，可选）"
                    },
                    "max_results": {
                      "type": "integer",
                      "description": "最大返回结果数（默认 30）"
                    }
                  },
                  "required": ["query"]
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
            String query = args.get("query").getAsString();
            String symbolKind = args.has("symbol_kind") ? args.get("symbol_kind").getAsString() : null;
            String filePattern = args.has("file_pattern") ? args.get("file_pattern").getAsString() : null;
            int maxResults = args.has("max_results") ? args.get("max_results").getAsInt() : 30;

            // 编译文件 glob 模式
            Pattern fileFilter = null;
            if (filePattern != null && !filePattern.isEmpty()) {
                String regex = filePattern
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".");
                fileFilter = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            }

            final Pattern finalFileFilter = fileFilter;
            final int finalMaxResults = maxResults;

            return ReadAction.compute(() -> {
                GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
                PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
                List<Map<String, Object>> results = new ArrayList<>();

                if (symbolKind == null || symbolKind.isEmpty() || symbolKind.equals("class")) {
                    PsiClass[] classes = cache.getClassesByName(query, scope);
                    for (PsiClass cls : classes) {
                        if (results.size() >= finalMaxResults) break;
                        Map<String, Object> entry = buildSymbolEntry(cls, "class", finalFileFilter);
                        if (entry != null) results.add(entry);
                    }
                }

                if ((symbolKind == null || symbolKind.isEmpty() || symbolKind.equals("method")) && results.size() < finalMaxResults) {
                    PsiMethod[] methods = cache.getMethodsByName(query, scope);
                    for (PsiMethod method : methods) {
                        if (results.size() >= finalMaxResults) break;
                        Map<String, Object> entry = buildSymbolEntry(method, "method", finalFileFilter);
                        if (entry != null) results.add(entry);
                    }
                }

                if ((symbolKind == null || symbolKind.isEmpty() || symbolKind.equals("field")) && results.size() < finalMaxResults) {
                    PsiField[] fields = cache.getFieldsByName(query, scope);
                    for (PsiField field : fields) {
                        if (results.size() >= finalMaxResults) break;
                        Map<String, Object> entry = buildSymbolEntry(field, "field", finalFileFilter);
                        if (entry != null) results.add(entry);
                    }
                }

                // 构建统一 JSON 输出
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("symbol", query);
                output.put("symbol_type", symbolKind != null ? symbolKind : "any");
                output.put("total", results.size());
                output.put("results", results);

                return gson.toJson(output);
            });

        } catch (Throwable e) {
            return ToolError.unexpected(LOG, "Symbol search failed", e,
                    I18nUtil.getMessage("tool.symbol.failed", e.getMessage()), I18nUtil.getMessage("tool.javaPlugin.useSearchCode"));
        }
    }

    /**
     * 构建统一的结构化结果条目
     */
    private Map<String, Object> buildSymbolEntry(PsiElement element, String symbolType, Pattern fileFilter) {
        PsiFile psiFile = element.getContainingFile();
        if (psiFile == null) return null;

        VirtualFile vFile = psiFile.getVirtualFile();
        if (vFile == null) return null;

        // 文件过滤
        if (fileFilter != null && !fileFilter.matcher(vFile.getName()).matches()) {
            return null;
        }

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
        entry.put("symbol_type", symbolType);

        // 提取签名和所在类
        String signature = extractSignature(element);
        entry.put("signature", signature);

        String containingClass = extractContainingClass(element);
        entry.put("containing_class", containingClass);

        // 提取上下文预览行
        entry.put("preview_lines", extractPreviewLines(psiFile, lineNumber, 3));

        return entry;
    }

    /**
     * 提取符号的签名
     */
    private String extractSignature(PsiElement element) {
        try {
            if (element instanceof PsiMethod) {
                PsiMethod method = (PsiMethod) element;
                StringBuilder sb = new StringBuilder();
                // 修饰符
                PsiModifierList modifiers = method.getModifierList();
                if (modifiers != null) {
                    for (String modifier : PsiModifier.MODIFIERS) {
                        if (modifiers.hasModifierProperty(modifier)) {
                            sb.append(modifier).append(" ");
                        }
                    }
                }
                // 返回类型
                PsiType returnType = method.getReturnType();
                if (returnType != null) {
                    sb.append(returnType.getPresentableText()).append(" ");
                }
                // 方法名 + 参数
                sb.append(method.getName()).append("(");
                PsiParameter[] params = method.getParameterList().getParameters();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getType().getPresentableText()).append(" ").append(params[i].getName());
                }
                sb.append(")");
                return sb.toString();
            } else if (element instanceof PsiField) {
                PsiField field = (PsiField) element;
                return (field.getType() != null ? field.getType().getPresentableText() + " " : "")
                        + field.getName();
            } else if (element instanceof PsiClass) {
                PsiClass cls = (PsiClass) element;
                String qName = cls.getQualifiedName();
                return qName != null ? qName : cls.getName();
            }
        } catch (Exception ignored) {
        }
        return element.getText().substring(0, Math.min(element.getTextLength(), 120));
    }

    /**
     * 提取符号所在的类全限定名
     */
    private String extractContainingClass(PsiElement element) {
        try {
            PsiElement parent = element.getParent();
            while (parent != null) {
                if (parent instanceof PsiClass) {
                    String qName = ((PsiClass) parent).getQualifiedName();
                    if (qName != null) return qName;
                }
                parent = parent.getParent();
            }
            // 如果 element 本身就是类
            if (element instanceof PsiClass) {
                String qName = ((PsiClass) element).getQualifiedName();
                if (qName != null) return qName;
            }
        } catch (Exception ignored) {
        }
        return "";
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
