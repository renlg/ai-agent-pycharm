package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 搜索代码工具（IntelliJ 索引加速版）
 * 优先使用 PsiSearchHelper 单词索引缩小候选文件范围，再做正则/文本匹配
 */
public class SearchCodeTool implements Tool {

    private static final Logger LOG = Logger.getInstance(SearchCodeTool.class);

    private final Project project;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // minimum word length for index pre-filtering
    private static final int MIN_WORD_LEN = 4;

    public SearchCodeTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "search_code";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.searchCode");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "搜索关键词或正则表达式"
                    },
                    "file_pattern": {
                      "type": "string",
                      "description": "文件名过滤（glob 模式，如 '*.java'、'*.xml'，可选）"
                    },
                    "max_results": {
                      "type": "integer",
                      "description": "最大返回结果数（默认 50）"
                    }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String query = args.get("query").getAsString();
            String filePattern = args.has("file_pattern") ? args.get("file_pattern").getAsString() : null;
            int maxResults = args.has("max_results") ? args.get("max_results").getAsInt() : 50;

            Pattern pattern;
            try {
                pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
            }

            final Pattern searchPattern = pattern;
            final String indexWord = extractIndexWord(query);

            return ReadAction.compute(() -> {
                VirtualFile baseDir = project.getBaseDir();
                if (baseDir == null) {
                    return ToolError.of("PROJECT_PATH_UNAVAILABLE", I18nUtil.getMessage("tool.project.pathUnavailable"),
                            I18nUtil.getMessage("tool.hint.openProject"));
                }

                List<Map<String, Object>> results = new ArrayList<>();

                if (indexWord != null) {
                    searchWithIndex(indexWord, searchPattern, filePattern, maxResults, results, baseDir);
                } else {
                    searchInDirectory(baseDir, searchPattern, filePattern, maxResults, results);
                }

                Map<String, Object> output = new LinkedHashMap<>();
                output.put("query", query);
                output.put("index_word", indexWord != null ? indexWord : "(none — full scan)");
                output.put("total", results.size());
                output.put("results", results);

                return gson.toJson(output);
            });

        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Code search failed", e,
                    I18nUtil.getMessage("tool.codeSearch.failed", e.getMessage()), I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    /**
     * Extract the longest alphanumeric word (≥ MIN_WORD_LEN) from the query for index pre-filtering.
     * Returns null if no suitable word is found.
     */
    private String extractIndexWord(String query) {
        Matcher m = Pattern.compile("[A-Za-z][A-Za-z0-9_]{" + (MIN_WORD_LEN - 1) + ",}").matcher(query);
        String longest = null;
        while (m.find()) {
            String word = m.group();
            if (longest == null || word.length() > longest.length()) {
                longest = word;
            }
        }
        return longest;
    }

    /**
     * Index-accelerated search: use PsiSearchHelper to find only files containing the index word,
     * then do full pattern matching within those files.
     */
    private void searchWithIndex(String indexWord, Pattern pattern, String filePattern,
                                 int maxResults, List<Map<String, Object>> results, VirtualFile baseDir) {
        PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        helper.processAllFilesWithWord(indexWord, scope, psiFile -> {
            if (results.size() >= maxResults) return false;

            VirtualFile vFile = psiFile.getVirtualFile();
            if (vFile == null) return true;

            // File pattern filter
            if (filePattern != null && !matchGlob(vFile.getName(), filePattern)) return true;

            // Skip generated / build outputs that are outside the project source tree but in scope
            String path = vFile.getPath();
            if (path.contains("/build/") || path.contains("/node_modules/")) return true;

            Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
            if (document == null) return true;

            matchInDocument(document, vFile, pattern, maxResults, results, baseDir);
            return results.size() < maxResults;
        }, false /* case-insensitive pre-filter */);
    }

    /**
     * Fallback VFS traversal for queries without an extractable index word (e.g., pure symbols like ".*").
     */
    private void searchInDirectory(VirtualFile dir, Pattern pattern, String filePattern,
                                   int maxResults, List<Map<String, Object>> results) {
        VfsUtilCore.visitChildrenRecursively(dir, new VirtualFileVisitor<Void>() {
            @Override
            public boolean visitFile(VirtualFile file) {
                if (results.size() >= maxResults) return false;

                if (file.isDirectory()) {
                    String name = file.getName();
                    return !name.startsWith(".") && !name.equals("build") && !name.equals("node_modules");
                }

                if (filePattern != null && !matchGlob(file.getName(), filePattern)) return true;
                if (file.getLength() > 1_000_000) return true;

                try {
                    String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
                    matchInText(content, file, pattern, maxResults, results, dir.getParent() != null ? dir.getParent() : dir);
                } catch (Exception ignored) {
                }
                return true;
            }
        });
    }

    private void matchInDocument(Document document, VirtualFile vFile, Pattern pattern,
                                 int maxResults, List<Map<String, Object>> results, VirtualFile baseDir) {
        String content = document.getText();
        String[] lines = content.split("\n");
        String relativePath = VfsUtilCore.getRelativePath(vFile, baseDir);
        if (relativePath == null) relativePath = vFile.getPath();

        for (int i = 0; i < lines.length; i++) {
            if (results.size() >= maxResults) break;
            if (pattern.matcher(lines[i]).find()) {
                results.add(buildEntry(relativePath, i, lines));
            }
        }
    }

    private void matchInText(String content, VirtualFile vFile, Pattern pattern,
                             int maxResults, List<Map<String, Object>> results, VirtualFile baseDir) {
        String[] lines = content.split("\n");
        String relativePath = VfsUtilCore.getRelativePath(vFile, baseDir);
        if (relativePath == null) relativePath = vFile.getPath();

        for (int i = 0; i < lines.length; i++) {
            if (results.size() >= maxResults) break;
            if (pattern.matcher(lines[i]).find()) {
                results.add(buildEntry(relativePath, i, lines));
            }
        }
    }

    private Map<String, Object> buildEntry(String relativePath, int lineIndex, String[] lines) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("file", relativePath);
        entry.put("line", lineIndex + 1);

        List<String> preview = new ArrayList<>();
        int start = Math.max(0, lineIndex - 2);
        int end = Math.min(lines.length, lineIndex + 3);
        for (int j = start; j < end; j++) {
            preview.add((j == lineIndex ? ">>> " : "    ") + lines[j]);
        }
        entry.put("preview_lines", preview);
        return entry;
    }

    private boolean matchGlob(String fileName, String glob) {
        String regex = glob
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return fileName.matches(regex);
    }
}
