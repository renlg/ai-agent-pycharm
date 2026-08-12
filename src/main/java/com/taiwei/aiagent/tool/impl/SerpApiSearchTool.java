package com.taiwei.aiagent.tool.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.settings.IqsSettings;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * SerpAPI 网络搜索工具
 * 通过 SerpAPI (https://serpapi.com) 搜索互联网信息
 * 需要配置 SerpAPI Key（在 serpapi.com 注册获取）
 */
public class SerpApiSearchTool implements Tool {

    private static final Logger LOG = Logger.getInstance(SerpApiSearchTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String API_URL = "https://serpapi.com/search.json";
    private static final int TIMEOUT_SECONDS = 15;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.webSearch");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "搜索关键词，支持自然语言查询"
                    }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            // 1. 检查 API Key 配置
            IqsSettings settings = IqsSettings.getInstance();
            String apiKey = settings.getSerpApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return ToolError.of("NOT_CONFIGURED", I18nUtil.getMessage("tool.serp.notConfigured"), I18nUtil.getMessage("tool.serp.configureHint"));
            }

            // 2. 解析参数
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String query = args.get("query").getAsString();
            if (query == null || query.trim().isEmpty()) {
                return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.search.queryEmpty"), I18nUtil.getMessage("tool.hint.provideValidArguments"));
            }

            LOG.debug("Calling SerpAPI search");

            // 3. 构建请求
            HttpUrl url = HttpUrl.parse(API_URL).newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("api_key", apiKey)
                    .addQueryParameter("engine", "google")
                    .addQueryParameter("num", "10")
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            // 4. 执行请求
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 401) {
                        return ToolError.of("AUTHENTICATION_FAILED", I18nUtil.getMessage("tool.serp.invalidKey"), I18nUtil.getMessage("tool.serp.configureHint"));
                    }
                    if (response.code() == 429) {
                        return ToolError.of("RATE_LIMITED", I18nUtil.getMessage("tool.serp.rateLimited"), I18nUtil.getMessage("tool.serp.rateLimitHint"));
                    }
                    return ToolError.of("HTTP_ERROR", I18nUtil.getMessage("tool.search.httpFailed", response.code()), I18nUtil.getMessage("tool.search.retryHint"));
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                return parseResults(responseBody);
            }

        } catch (IOException e) {
            LOG.warn("SerpAPI request failed");
            return ToolError.of("NETWORK_ERROR", I18nUtil.getMessage("tool.search.networkFailed", I18nUtil.getMessage("tool.error.unknown")), I18nUtil.getMessage("tool.search.networkHint"));
        } catch (Exception e) {
            LOG.error("SerpAPI search failed unexpectedly");
            return ToolError.of("INTERNAL_ERROR",
                    I18nUtil.getMessage("tool.search.failed", I18nUtil.getMessage("tool.error.unknown")),
                    I18nUtil.getMessage("tool.search.retryHint"));
        }
    }

    /**
     * 解析 SerpAPI 返回的 JSON，提取有机搜索结果
     */
    private String parseResults(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return I18nUtil.getMessage("tool.search.noResults");
        }

        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            // 提取有机搜索结果
            JsonArray organicResults = json.getAsJsonArray("organic_results");
            if (organicResults == null || organicResults.isEmpty()) {
                // 尝试回答框
                if (json.has("answer_box")) {
                    JsonObject answerBox = json.getAsJsonObject("answer_box");
                    String answer = answerBox.has("answer") ? answerBox.get("answer").getAsString() : "";
                    String title = answerBox.has("title") ? answerBox.get("title").getAsString() : "";
                    if (!answer.isEmpty()) {
                        JsonObject result = new JsonObject();
                        result.addProperty("answer", answer);
                        if (!title.isEmpty()) result.addProperty("title", title);
                        return GSON.toJson(result);
                    }
                }
                return I18nUtil.getMessage("tool.search.noResultsRetry");
            }

            JsonArray results = new JsonArray();
            int count = Math.min(organicResults.size(), 10);
            for (int i = 0; i < count; i++) {
                JsonObject item = organicResults.get(i).getAsJsonObject();
                JsonObject result = new JsonObject();
                result.addProperty("title", getStringOrEmpty(item, "title"));
                result.addProperty("url", getStringOrEmpty(item, "link"));
                result.addProperty("snippet", getStringOrEmpty(item, "snippet"));
                results.add(result);
            }

            JsonObject wrapper = new JsonObject();
            wrapper.add("results", results);
            wrapper.addProperty("count", results.size());

            // 如果有知识面板，也提取
            if (json.has("knowledge_graph")) {
                JsonObject kg = json.getAsJsonObject("knowledge_graph");
                JsonObject kgSummary = new JsonObject();
                kgSummary.addProperty("title", getStringOrEmpty(kg, "title"));
                kgSummary.addProperty("description", getStringOrEmpty(kg, "description"));
                wrapper.add("knowledge_graph", kgSummary);
            }

            return GSON.toJson(wrapper);

        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Failed to parse SerpAPI response", e,
                    I18nUtil.getMessage("tool.search.parseFailed", e.getMessage()), I18nUtil.getMessage("tool.search.retryHint"));
        }
    }

    private String getStringOrEmpty(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }
}
