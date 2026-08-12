package com.taiwei.aiagent.tool.impl;

import com.aliyun.iqs20241111.Client;
import com.aliyun.iqs20241111.models.UnifiedSearchInput;
import com.aliyun.iqs20241111.models.UnifiedSearchRequest;
import com.aliyun.iqs20241111.models.UnifiedSearchResponse;
import com.aliyun.teaopenapi.models.Config;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.settings.IqsSettings;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

/**
 * 网络搜索工具
 * Agent 可以通过此工具调用阿里云 IQS 搜索互联网信息
 */
public class WebSearchTool implements Tool {

    private static final Logger LOG = Logger.getInstance(WebSearchTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.webSearchAdvanced");
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
                    },
                    "time_range": {
                      "type": "string",
                      "description": "时间范围过滤，可选值：OneDay（一天内）、OneWeek（一周内）、OneMonth（一月内）、OneYear（一年内）。不传则不过滤"
                    },
                    "categories": {
                      "type": "string",
                      "description": "查询分类（可选，多个用逗号分隔。支持：finance金融、law法律、medical医疗、internet互联网、tax税务、news_province新闻省级、news_center新闻中央）"
                    }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            // 1. 检查配置
            IqsSettings settings = IqsSettings.getInstance();
            if (!settings.isConfigured()) {
                return ToolError.of("NOT_CONFIGURED", I18nUtil.getMessage("tool.iqs.notConfigured"), I18nUtil.getMessage("tool.iqs.configureHint"));
            }

            // 2. 解析参数
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String query = args.get("query").getAsString();
            String timeRange = args.has("time_range") ? args.get("time_range").getAsString() : null;
            String categories = args.has("categories") ? args.get("categories").getAsString() : null;

            if (query == null || query.trim().isEmpty()) {
                return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.search.queryEmpty"), I18nUtil.getMessage("tool.hint.provideValidArguments"));
            }

            // 3. 创建 IQS 客户端
            Config config = new Config();
            config.setAccessKeyId(settings.getAccessKeyId());
            config.setAccessKeySecret(settings.getAccessKeySecret());
            config.setEndpoint(settings.getEndpoint());
            Client client = new Client(config);

            // 4. 构建请求
            UnifiedSearchInput input = new UnifiedSearchInput();
            input.setQuery(query);
            input.setEngineType("LiteAdvanced");
            if (timeRange != null && !timeRange.trim().isEmpty()) {
                input.setTimeRange(timeRange);
            }
            if (categories != null && !categories.trim().isEmpty()) {
                input.setCategory(categories);
            }
            UnifiedSearchRequest request = new UnifiedSearchRequest();
            request.setBody(input);

            // 5. 调用搜索
            LOG.debug("Calling Aliyun IQS search");
            UnifiedSearchResponse response = client.unifiedSearch(request);

            // 6. 将结果转换为 JSON 字符串返回
            if (response == null || response.getBody() == null) {
                return I18nUtil.getMessage("tool.search.noResults");
            }

            return GSON.toJson(response.getBody());

        } catch (com.aliyun.tea.TeaException e) {
            LOG.warn("IQS request failed");
            String message = e.getMessage();
            if (message != null && message.contains("InvalidAccessKeyId")) {
                return ToolError.of("AUTHENTICATION_FAILED", I18nUtil.getMessage("tool.iqs.invalidId"), I18nUtil.getMessage("tool.iqs.configureHint"));
            } else if (message != null && message.contains("SignatureDoesNotMatch")) {
                return ToolError.of("AUTHENTICATION_FAILED", I18nUtil.getMessage("tool.iqs.invalidSecret"), I18nUtil.getMessage("tool.iqs.configureHint"));
            } else if (message != null && message.contains("Forbidden")) {
                return ToolError.of("PERMISSION_DENIED", I18nUtil.getMessage("tool.iqs.forbidden"), I18nUtil.getMessage("tool.iqs.permissionHint"));
            } else if (message != null && (message.contains("Timeout") || message.contains("timed out"))) {
                return ToolError.of("NETWORK_TIMEOUT", I18nUtil.getMessage("tool.search.timeout"), I18nUtil.getMessage("tool.search.networkHint"));
            }
            return ToolError.of("API_ERROR", I18nUtil.getMessage("tool.error.unknown"), I18nUtil.getMessage("tool.search.retryHint"));

        } catch (Exception e) {
            LOG.error("IQS search failed unexpectedly");
            return ToolError.of("INTERNAL_ERROR",
                    I18nUtil.getMessage("tool.search.failed", I18nUtil.getMessage("tool.error.unknown")),
                    I18nUtil.getMessage("tool.search.retryHint"));
        }
    }
}
