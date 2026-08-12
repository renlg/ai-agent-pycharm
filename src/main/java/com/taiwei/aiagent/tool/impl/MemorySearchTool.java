package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.memory.MemoryManager;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

/**
 * 记忆搜索工具
 * 混合检索（关键词 + 向量语义 RRF 融合）已保存的长期记忆
 */
public class MemorySearchTool implements Tool {

    private static final Logger LOG = Logger.getInstance(MemorySearchTool.class);

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;
    private static final double DEFAULT_MIN_RELEVANCE = 0.15;

    private final Project project;

    public MemorySearchTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "search_memory";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.searchMemory");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "搜索内容，如 \\"用户喜欢的饮料\\""
                    },
                    "category": {
                      "type": "string",
                      "enum": ["fact", "preference", "context", "command"],
                      "description": "记忆分类过滤（可选）：fact-事实，preference-偏好，context-上下文，command-常用命令"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "最多返回条数（可选，默认 5，最大 20）"
                    },
                    "minRelevance": {
                      "type": "number",
                      "description": "最低相关度阈值（可选，默认 0.15，范围 0-1）：低于该值的记忆会被过滤，调高更严格、调低更宽松"
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
            if (!args.has("query")) {
                return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.search.queryRequired"),
                        I18nUtil.getMessage("tool.hint.provideValidArguments"));
            }
            String query = args.get("query").getAsString().trim();
            if (query.isEmpty()) {
                return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.search.queryEmpty"),
                        I18nUtil.getMessage("tool.hint.provideValidArguments"));
            }
            String category = args.has("category") ? args.get("category").getAsString() : null;
            int limit = args.has("limit") ? args.get("limit").getAsInt() : DEFAULT_LIMIT;
            limit = Math.max(1, Math.min(limit, MAX_LIMIT));
            double minRelevance = args.has("minRelevance") ? args.get("minRelevance").getAsDouble() : DEFAULT_MIN_RELEVANCE;
            minRelevance = Math.max(0.0, Math.min(minRelevance, 1.0));

            String result = MemoryManager.getInstance(project).hybridSearch(query, category, limit, minRelevance);
            return result.isBlank() ? I18nUtil.getMessage("tool.memory.noResults") : result;

        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Failed to search memory", e,
                    I18nUtil.getMessage("tool.memory.searchFailed", e.getMessage()),
                    I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    @Override
    public boolean isMutating() {
        return false;
    }
}
