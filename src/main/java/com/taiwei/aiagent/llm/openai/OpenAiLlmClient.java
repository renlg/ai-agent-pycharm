package com.taiwei.aiagent.llm.openai;

import com.google.gson.*;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.llm.LlmStreamListener;
import com.taiwei.aiagent.llm.TokenCounter;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.util.I18nUtil;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI 兼容的 LLM 客户端实现
 * 支持 OpenAI、DeepSeek、通义千问等兼容 OpenAI 接口的模型
 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger LOG = Logger.getInstance(OpenAiLlmClient.class);

    private static final Map<String, Integer> MODEL_CONTEXT_WINDOWS = Map.ofEntries(
            Map.entry("qwen3-max", 128000),
            Map.entry("deepseek-chat", 128000),
            Map.entry("deepseek-v3", 128000),
            Map.entry("deepseek-reasoner", 128000),
            Map.entry("gpt-4o", 128000),
            Map.entry("gpt-4o-mini", 128000),
            Map.entry("claude-sonnet-4", 200000),
            Map.entry("claude-opus-4", 200000),
            Map.entry("claude-fable-5", 1000000)
    );

    private static final int DEFAULT_CONTEXT_WINDOW = 128000;
    private static final int CACHE_MAX_SIZE = 32;
    private static final long CACHE_TTL_MS = 3_000L;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private volatile EventSource currentEventSource;
    private final Map<String, CacheEntry> completionCache;

    // ========== LRU cache types ==========

    private static class ToolCallRecord {
        final String id, name, args;
        ToolCallRecord(String id, String name, String args) {
            this.id = id; this.name = name; this.args = args;
        }
    }

    private static class CacheEntry {
        final String lastUserMessage;
        final LlmResponse response;             // non-null for chat() entries
        final String streamContent;             // non-null for chatStream() entries
        final List<ToolCallRecord> streamToolCalls;
        final LlmResponse.Usage streamUsage;
        final long cachedAt;

        CacheEntry(String lastUserMessage, LlmResponse response) {
            this.lastUserMessage = lastUserMessage;
            this.response = response;
            this.streamContent = null;
            this.streamToolCalls = null;
            this.streamUsage = null;
            this.cachedAt = System.currentTimeMillis();
        }

        CacheEntry(String lastUserMessage, String streamContent,
                   List<ToolCallRecord> streamToolCalls, LlmResponse.Usage streamUsage) {
            this.lastUserMessage = lastUserMessage;
            this.response = null;
            this.streamContent = streamContent;
            this.streamToolCalls = streamToolCalls;
            this.streamUsage = streamUsage;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }

        boolean isStreamEntry() {
            return response == null;
        }
    }

    public OpenAiLlmClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.apiKey = apiKey;
        this.model = model;
        LOG.debug("OpenAiLlmClient created: baseUrl=" + this.baseUrl + ", model=" + model);
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS);
        if (AiAgentSettings.getInstance().isBypassHostnameVerificationEnabled()) {
            LOG.warn("已启用跳过主机名校验，LLM API 请求将不校验 TLS 证书主机名");
            clientBuilder.hostnameVerifier((hostname, session) -> true);
        }
        this.httpClient = clientBuilder.build();
        this.gson = new GsonBuilder().create();
        this.completionCache = Collections.synchronizedMap(
                new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                        return size() > CACHE_MAX_SIZE;
                    }
                }
        );
    }

    @Override
    public LlmResponse chat(List<ChatMessage> messages, List<Tool> tools) {
        if (messages.isEmpty()) {
            return LlmResponse.error(I18nUtil.getMessage("llm.messagesEmpty"));
        }

        String prefixKey = buildPrefixKey(messages, tools);
        String lastContent = getLastMessageContent(messages);

        synchronized (completionCache) {
            CacheEntry cached = completionCache.get(prefixKey);
            if (cached != null && !cached.isExpired() && !cached.isStreamEntry()) {
                if (lastContent.equals(cached.lastUserMessage)) {
                    LOG.debug("LRU cache hit (chat) key=" + prefixKey);
                    return cached.response;
                }
                LOG.debug("LRU cache invalidated (chat) - last message changed, key=" + prefixKey);
                completionCache.remove(prefixKey);
            }
        }

        LOG.debug("LRU cache miss (chat) - calling API, model=" + model);
        JsonObject requestBody = buildRequestBody(messages, tools, false);
        Request request = buildRequest(requestBody);

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                return LlmResponse.error("HTTP " + response.code() + ": " + body);
            }
            LlmResponse result = parseResponse(body);
            if (result.isSuccess()) {
                if (result.getUsage() == null) {
                    result.setUsage(TokenCounter.estimate(messages, result.getContent()));
                }
                synchronized (completionCache) {
                    completionCache.put(prefixKey, new CacheEntry(lastContent, result));
                }
                LOG.debug("LRU cache stored (chat) key=" + prefixKey);
            }
            return result;
        } catch (IOException e) {
            LOG.warn("LLM request failed: " + e.getMessage());
            return LlmResponse.error(I18nUtil.getMessage("llm.requestFailed", e.getMessage()));
        }
    }

    @Override
    public void chatStream(List<ChatMessage> messages, List<Tool> tools, LlmStreamListener listener) {
        if (messages.isEmpty()) {
            listener.onError(I18nUtil.getMessage("llm.messagesEmpty"), null);
            return;
        }

        String prefixKey = buildPrefixKey(messages, tools);
        String lastContent = getLastMessageContent(messages);

        synchronized (completionCache) {
            CacheEntry cached = completionCache.get(prefixKey);
            if (cached != null && !cached.isExpired() && cached.isStreamEntry()) {
                if (lastContent.equals(cached.lastUserMessage)) {
                    LOG.debug("LRU cache hit (stream) key=" + prefixKey);
                    replayStreamCache(cached, listener);
                    return;
                }
                LOG.debug("LRU cache invalidated (stream) - last message changed, key=" + prefixKey);
                completionCache.remove(prefixKey);
            }
        }

        LOG.debug("LRU cache miss (stream) - calling API, model=" + model + ", messages=" + messages.size()
                + ", tools=" + (tools != null ? tools.size() : 0));

        // Wrap listener to accumulate the response for caching on completion
        StringBuilder contentAccum = new StringBuilder();
        List<ToolCallRecord> toolCallAccum = new ArrayList<>();
        LlmResponse.Usage[] usageHolder = {null};

        LlmStreamListener accumListener = new LlmStreamListener() {
            @Override
            public void onContent(String delta) {
                contentAccum.append(delta);
                listener.onContent(delta);
            }

            @Override
            public void onToolCall(String toolCallId, String functionName, String arguments) {
                toolCallAccum.add(new ToolCallRecord(toolCallId, functionName, arguments));
                listener.onToolCall(toolCallId, functionName, arguments);
            }

            @Override
            public void onUsage(LlmResponse.Usage usage) {
                usageHolder[0] = usage;
                listener.onUsage(usage);
            }

            @Override
            public void onComplete() {
                if (usageHolder[0] == null) {
                    LlmResponse.Usage estimated = TokenCounter.estimate(messages, contentAccum.toString());
                    usageHolder[0] = estimated;
                    listener.onUsage(estimated);
                }
                synchronized (completionCache) {
                    completionCache.put(prefixKey, new CacheEntry(
                            lastContent, contentAccum.toString(), toolCallAccum, usageHolder[0]));
                }
                LOG.debug("LRU cache stored (stream) key=" + prefixKey);
                listener.onComplete();
            }

            @Override
            public void onError(String error, Throwable throwable) {
                // Do not cache error responses
                listener.onError(error, throwable);
            }
        };

        JsonObject requestBody = buildRequestBody(messages, tools, true);
        Request request = buildRequest(requestBody);
        doStreamRequest(request, accumListener);
    }

    private void replayStreamCache(CacheEntry cached, LlmStreamListener listener) {
        if (cached.streamContent != null && !cached.streamContent.isEmpty()) {
            listener.onContent(cached.streamContent);
        }
        if (cached.streamToolCalls != null) {
            for (ToolCallRecord tc : cached.streamToolCalls) {
                listener.onToolCall(tc.id, tc.name, tc.args);
            }
        }
        if (cached.streamUsage != null) {
            listener.onUsage(cached.streamUsage);
        }
        listener.onComplete();
    }

    private void doStreamRequest(Request request, LlmStreamListener listener) {
        EventSource.Factory factory = EventSources.createFactory(httpClient);
        EventSource eventSource = factory.newEventSource(request, new EventSourceListener() {

            private final Map<Integer, StringBuilder> toolCallIdMap = new ConcurrentHashMap<>();
            private final Map<Integer, StringBuilder> toolCallNameMap = new ConcurrentHashMap<>();
            private final Map<Integer, StringBuilder> toolCallArgsMap = new ConcurrentHashMap<>();
            private final AtomicInteger chunkCount = new AtomicInteger(0);

            @Override
            public void onOpen(EventSource eventSource, Response response) {
                LOG.debug("SSE opened - HTTP " + response.code() + ", model=" + model);
                currentEventSource = eventSource;
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                LOG.debug("SSE onEvent 收到数据: " + (data.length() > 500 ? data.substring(0, 500) + "..." : data));
                chunkCount.incrementAndGet();

                if ("[DONE]".equals(data)) {
                    LOG.debug("chatStream completed - model=" + model + ", chunks=" + chunkCount.get());
                    // 如果有累积的工具调用，回调
                    flushToolCalls(listener);
                    listener.onComplete();
                    return;
                }

                try {
                    JsonObject chunk = gson.fromJson(data, JsonObject.class);
                    if (chunk == null) return;

                    // 检查 API 错误响应（很多 OpenAI 兼容 API 在流式模式下通过 SSE 事件返回错误）
                    if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                        JsonObject errorObj = chunk.getAsJsonObject("error");
                        String errorMsg = errorObj.has("message") ? errorObj.get("message").getAsString() : I18nUtil.getMessage("llm.unknownApiError");
                        LOG.warn("SSE API error: " + errorMsg);
                        listener.onError(I18nUtil.getMessage("llm.apiError", errorMsg), null);
                        // Stop consuming the stream: without this, later chunks / [DONE] would keep
                        // arriving and could fire onComplete after onError on the same listener.
                        eventSource.cancel();
                        return;
                    }

                    // 解析 usage（通常在最后一个 chunk 中，choices 可能为空）
                    if (chunk.has("usage") && !chunk.get("usage").isJsonNull()) {
                        JsonObject usageObj = chunk.getAsJsonObject("usage");
                        LlmResponse.Usage usage = new LlmResponse.Usage();
                        usage.setPromptTokens(usageObj.get("prompt_tokens").getAsInt());
                        usage.setCompletionTokens(usageObj.get("completion_tokens").getAsInt());
                        usage.setTotalTokens(usageObj.get("total_tokens").getAsInt());
                        listener.onUsage(usage);
                    }

                    if (!chunk.has("choices") || chunk.get("choices").isJsonNull()) return;
                    JsonArray choices = chunk.getAsJsonArray("choices");
                    if (choices == null || choices.size() == 0) return;

                    JsonElement firstChoice = choices.get(0);
                    if (firstChoice == null || firstChoice.isJsonNull()) return;
                    JsonObject choiceObj = firstChoice.getAsJsonObject();
                    if (choiceObj == null || !choiceObj.has("delta") || choiceObj.get("delta").isJsonNull()) return;
                    JsonObject delta = choiceObj.getAsJsonObject("delta");
                    if (delta == null) return;

                    // 处理内容增量
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String content = delta.get("content").getAsString();
                        if (content != null && !content.isEmpty()) {
                            listener.onContent(content);
                        }
                    }

                    // 处理工具调用增量
                    if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                        JsonArray toolCalls = delta.getAsJsonArray("tool_calls");
                        if (toolCalls != null) {
                            for (JsonElement elem : toolCalls) {
                                if (elem == null || elem.isJsonNull()) continue;
                                JsonObject tc = elem.getAsJsonObject();
                                if (!tc.has("index") || tc.get("index").isJsonNull()) continue;
                                int index = tc.get("index").getAsInt();

                                if (tc.has("id") && !tc.get("id").isJsonNull()) {
                                    toolCallIdMap.computeIfAbsent(index, k -> new StringBuilder())
                                            .append(tc.get("id").getAsString());
                                }
                                if (tc.has("function") && !tc.get("function").isJsonNull()) {
                                    JsonObject fn = tc.getAsJsonObject("function");
                                    if (fn.has("name") && !fn.get("name").isJsonNull()) {
                                        toolCallNameMap.computeIfAbsent(index, k -> new StringBuilder())
                                                .append(fn.get("name").getAsString());
                                    }
                                    if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                                        toolCallArgsMap.computeIfAbsent(index, k -> new StringBuilder())
                                                .append(fn.get("arguments").getAsString());
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // A single malformed chunk shouldn't abort the whole stream; skip it.
                    // If nothing useful ever arrives, the caller reports "no content" at [DONE].
                    LOG.warn("Skipping malformed streaming chunk: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                StringBuilder msg = new StringBuilder(I18nUtil.getMessage("llm.streamFailed"));
                if (response != null) {
                    msg.append(", HTTP ").append(response.code());
                    try {
                        String body = response.body() != null ? response.body().string() : "";
                        if (!body.isEmpty()) {
                            msg.append(", body: ").append(body);
                        }
                    } catch (IOException ignored) {}
                    response.close();
                }
                if (t != null) {
                    msg.append(", error: ").append(t.getMessage());
                }
                LOG.warn(msg.toString());
                listener.onError(msg.toString(), t);
            }

            private void flushToolCalls(LlmStreamListener listener) {
                for (Map.Entry<Integer, StringBuilder> entry : toolCallIdMap.entrySet()) {
                    int index = entry.getKey();
                    String id = entry.getValue().toString();
                    String name = toolCallNameMap.containsKey(index) ? toolCallNameMap.get(index).toString() : "";
                    String args = toolCallArgsMap.containsKey(index) ? toolCallArgsMap.get(index).toString() : "";
                    listener.onToolCall(id, name, args);
                }
            }
        });
        currentEventSource = eventSource;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty() && model != null && !model.isEmpty();
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public void cancel() {
        EventSource es = currentEventSource;
        if (es != null) {
            es.cancel();
            currentEventSource = null;
        }
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    @Override
    public int getContextWindowSize() {
        return MODEL_CONTEXT_WINDOWS.getOrDefault(model, DEFAULT_CONTEXT_WINDOW);
    }

    // ========== Cache helpers ==========

    /**
     * Builds a stable string key representing all messages except the last, plus generation config.
     * Uses a 64-bit hash (two independent 32-bit hashes combined) to keep the key compact while
     * minimising collision risk for a 32-entry cache.
     */
    private String buildPrefixKey(List<ChatMessage> messages, List<Tool> tools) {
        AiAgentSettings.ModelConfig cfg = AiAgentSettings.getInstance().getActiveModelConfig();
        int maxTokens = cfg != null ? cfg.maxTokens : AiAgentSettings.getInstance().getMaxTokens();
        double temperature = cfg != null ? cfg.temperature : 0.3;

        StringBuilder sb = new StringBuilder();
        sb.append(model).append('\0').append(maxTokens).append('\0').append(temperature).append('\0');

        int limit = messages.size() - 1; // exclude last message from prefix
        for (int i = 0; i < limit; i++) {
            ChatMessage msg = messages.get(i);
            sb.append(msg.getRole()).append('\1');
            if (msg.getContent() != null) sb.append(msg.getContent());
            if (msg.getToolCalls() != null) {
                for (ChatMessage.ToolCall tc : msg.getToolCalls()) {
                    sb.append('\3').append(tc.getId()).append('\4')
                      .append(tc.getFunction().getName()).append('\4')
                      .append(tc.getFunction().getArguments());
                }
            }
            if (msg.getToolCallId() != null) sb.append('\3').append(msg.getToolCallId());
            sb.append('\2');
        }

        if (tools != null) {
            for (Tool t : tools) {
                sb.append('\5').append(t.getName()).append('\6');
            }
        }

        String prefix = sb.toString();
        int h1 = prefix.hashCode();
        // Second independent hash using a large-prime seed (FNV-inspired)
        long h2 = 1125899906842597L;
        for (int i = 0; i < prefix.length(); i++) {
            h2 = h2 * 31 + prefix.charAt(i);
        }
        return h1 + ":" + (int) h2;
    }

    private String getLastMessageContent(List<ChatMessage> messages) {
        ChatMessage last = messages.get(messages.size() - 1);
        return last.getContent() != null ? last.getContent() : "";
    }

    // ========== 内部方法 ==========

    private Request buildRequest(JsonObject requestBody) {
        return new Request.Builder()
                .url(baseUrl + "chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();
    }

    private JsonObject buildRequestBody(List<ChatMessage> messages, List<Tool> tools, boolean stream) {
        AiAgentSettings.ModelConfig activeConfig = AiAgentSettings.getInstance().getActiveModelConfig();

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", stream);
        body.addProperty("max_tokens", activeConfig != null ? activeConfig.maxTokens : AiAgentSettings.getInstance().getMaxTokens());
        body.addProperty("temperature", activeConfig != null ? activeConfig.temperature : 0.3);

        if (stream) {
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            body.add("stream_options", streamOptions);
        }

        // 消息列表
        JsonArray messagesArray = new JsonArray();
        for (ChatMessage msg : messages) {
            // 跳过无效的 assistant 消息（既无 content 也无 tool_calls）
            if ("assistant".equals(msg.getRole())
                    && (msg.getContent() == null || msg.getContent().isEmpty())
                    && (msg.getToolCalls() == null || msg.getToolCalls().length == 0)) {
                continue;
            }

            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole());

            if (msg.hasImages()) {
                JsonArray contentArray = new JsonArray();
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("type", "text");
                    textPart.addProperty("text", msg.getContent());
                    contentArray.add(textPart);
                }
                for (ChatMessage.ImageContent img : msg.getImageContents()) {
                    JsonObject imagePart = new JsonObject();
                    imagePart.addProperty("type", "image_url");
                    JsonObject imageUrlObj = new JsonObject();
                    imageUrlObj.addProperty("url", "data:" + img.getMimeType() + ";base64," + img.getBase64Data());
                    imagePart.add("image_url", imageUrlObj);
                    contentArray.add(imagePart);
                }
                msgObj.add("content", contentArray);
            } else if (msg.getContent() != null) {
                // tool 角色消息的 content 不能为空字符串，否则 API 会返回 "Invalid value: ''" 错误
                String content = msg.getContent();
                if ("tool".equals(msg.getRole()) && content.isEmpty()) {
                    content = "(no output)";
                }
                msgObj.addProperty("content", content);
            }

            // 工具调用
            if (msg.getToolCalls() != null) {
                JsonArray tcArray = new JsonArray();
                for (ChatMessage.ToolCall tc : msg.getToolCalls()) {
                    JsonObject tcObj = new JsonObject();
                    tcObj.addProperty("id", tc.getId());
                    tcObj.addProperty("type", tc.getType());
                    JsonObject fnObj = new JsonObject();
                    fnObj.addProperty("name", tc.getFunction().getName());
                    fnObj.addProperty("arguments", tc.getFunction().getArguments());
                    tcObj.add("function", fnObj);
                    tcArray.add(tcObj);
                }
                msgObj.add("tool_calls", tcArray);
            }

            // 工具调用 ID
            if (msg.getToolCallId() != null) {
                msgObj.addProperty("tool_call_id", msg.getToolCallId());
            }

            messagesArray.add(msgObj);
        }
        body.add("messages", messagesArray);

        // 工具定义
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArray = new JsonArray();
            for (Tool tool : tools) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("type", "function");
                JsonObject fnDef = new JsonObject();
                fnDef.addProperty("name", tool.getName());
                fnDef.addProperty("description", tool.getDescription());
                fnDef.add("parameters", gson.fromJson(tool.getParametersSchema(), JsonObject.class));
                toolObj.add("function", fnDef);
                toolsArray.add(toolObj);
            }
            body.add("tools", toolsArray);
        }

        return body;
    }

    private LlmResponse parseResponse(String body) {
        try {
            JsonObject json = gson.fromJson(body, JsonObject.class);

            // 检查错误
            if (json.has("error")) {
                JsonObject error = json.getAsJsonObject("error");
                String msg = error.has("message") ? error.get("message").getAsString() : I18nUtil.getMessage("tool.error.unknown");
                return LlmResponse.error(msg);
            }

            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return LlmResponse.error(I18nUtil.getMessage("llm.choicesMissing"));
            }

            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");

            String content = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : "";

            // 解析工具调用
            ChatMessage.ToolCall[] toolCalls = null;
            if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
                JsonArray tcArray = message.getAsJsonArray("tool_calls");
                toolCalls = new ChatMessage.ToolCall[tcArray.size()];
                for (int i = 0; i < tcArray.size(); i++) {
                    JsonObject tc = tcArray.get(i).getAsJsonObject();
                    ChatMessage.ToolCall tcObj = new ChatMessage.ToolCall();
                    tcObj.setId(tc.get("id").getAsString());
                    tcObj.setType(tc.has("type") ? tc.get("type").getAsString() : "function");
                    JsonObject fn = tc.getAsJsonObject("function");
                    ChatMessage.FunctionCall fc = new ChatMessage.FunctionCall();
                    fc.setName(fn.get("name").getAsString());
                    fc.setArguments(fn.get("arguments").getAsString());
                    tcObj.setFunction(fc);
                    toolCalls[i] = tcObj;
                }
            }

            LlmResponse response;
            if (toolCalls != null && toolCalls.length > 0) {
                response = LlmResponse.successWithToolCalls(toolCalls, content);
            } else {
                response = LlmResponse.success(content);
            }

            // 解析 usage
            if (json.has("usage")) {
                JsonObject usageObj = json.getAsJsonObject("usage");
                LlmResponse.Usage usage = new LlmResponse.Usage();
                usage.setPromptTokens(usageObj.get("prompt_tokens").getAsInt());
                usage.setCompletionTokens(usageObj.get("completion_tokens").getAsInt());
                usage.setTotalTokens(usageObj.get("total_tokens").getAsInt());
                response.setUsage(usage);
            }

            response.setRawResponse(body);
            return response;

        } catch (Exception e) {
            LOG.warn("Failed to parse LLM response: " + e.getMessage());
            return LlmResponse.error(I18nUtil.getMessage("llm.parseFailed", e.getMessage()));
        }
    }
}
