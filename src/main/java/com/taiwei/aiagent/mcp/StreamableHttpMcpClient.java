package com.taiwei.aiagent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.settings.AiAgentSettings;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 客户端：Streamable HTTP 传输（新版协议）
 * 单一 HTTP POST 端点，每次请求独立发送；响应可以是单个 JSON-RPC 响应，
 * 也可以是 SSE 流（其中包含若干 JSON-RPC 消息，取与请求 id 匹配的那条）。
 */
public class StreamableHttpMcpClient implements McpClient {

    private static final Logger LOG = Logger.getInstance(StreamableHttpMcpClient.class);

    private final AiAgentSettings.McpConfig config;
    private final Gson gson = new Gson();
    private final OkHttpClient httpClient;
    private final AtomicLong idGenerator = new AtomicLong(1);

    private volatile boolean connected = false;
    /** Session id assigned by the server via the Mcp-Session-Id response header, echoed on later requests. */
    private volatile String sessionId;

    public StreamableHttpMcpClient(AiAgentSettings.McpConfig config) {
        this.config = config;
        int timeout = config.timeoutSeconds > 0 ? config.timeoutSeconds : 30;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public synchronized McpInitResult initialize() {
        try {
            JsonObject params = McpJsonRpc.buildInitializeParams("taiwei-ai-agent", "1.0");
            JsonObject response = sendRequest("initialize", params);
            if (response == null) {
                return McpInitResult.failure("MCP server '" + config.name + "' did not respond to initialize");
            }
            String err = McpJsonRpc.extractError(response);
            if (err != null) {
                return McpInitResult.failure(err);
            }

            McpInitResult result = parseInitResult(response);
            sendNotification("notifications/initialized", new JsonObject());
            connected = true;
            return result;
        } catch (Exception e) {
            LOG.warn("Failed to initialize streamable HTTP MCP server '" + config.name + "': " + e.getMessage());
            return McpInitResult.failure("Failed to connect to MCP server: " + e.getMessage());
        }
    }

    private McpInitResult parseInitResult(JsonObject response) {
        JsonObject result = response.getAsJsonObject("result");
        String serverName = config.name;
        String serverVersion = "";
        String protocolVersion = McpJsonRpc.PROTOCOL_VERSION;
        if (result != null) {
            if (result.has("protocolVersion") && !result.get("protocolVersion").isJsonNull()) {
                protocolVersion = result.get("protocolVersion").getAsString();
            }
            if (result.has("serverInfo") && result.get("serverInfo").isJsonObject()) {
                JsonObject info = result.getAsJsonObject("serverInfo");
                if (info.has("name") && !info.get("name").isJsonNull()) serverName = info.get("name").getAsString();
                if (info.has("version") && !info.get("version").isJsonNull()) serverVersion = info.get("version").getAsString();
            }
        }
        return McpInitResult.success(serverName, serverVersion, protocolVersion);
    }

    @Override
    public List<McpToolInfo> listTools() {
        if (!connected) return Collections.emptyList();
        try {
            JsonObject response = sendRequest("tools/list", new JsonObject());
            if (response == null) return Collections.emptyList();
            String err = McpJsonRpc.extractError(response);
            if (err != null) {
                LOG.warn("tools/list failed for '" + config.name + "': " + err);
                return Collections.emptyList();
            }
            return McpToolListParser.parse(response.getAsJsonObject("result"));
        } catch (Exception e) {
            LOG.warn("Failed to list tools for '" + config.name + "': " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String callTool(String toolName, String argumentsJson) {
        if (!connected) return ToolError.of("MCP_NOT_CONNECTED", "MCP server '" + config.name + "' is not connected", "Reconnect or enable the MCP server, then retry.");
        try {
            JsonObject params = McpToolListParser.buildCallParams(gson, toolName, argumentsJson);
            if (params == null) return ToolError.of("INVALID_ARGUMENT", "Invalid MCP tool arguments JSON", "Provide arguments matching the MCP tool schema.");

            JsonObject response = sendRequest("tools/call", params);
            if (response == null) {
                return ToolError.of("MCP_TIMEOUT", "MCP server '" + config.name + "' timed out calling tool '" + toolName + "'", "Retry or increase the MCP server timeout.");
            }
            String err = McpJsonRpc.extractError(response);
            if (err != null) return ToolError.of("MCP_ERROR", err, "Correct the arguments or inspect the MCP server, then retry.");
            return McpJsonRpc.extractToolResultText(response.getAsJsonObject("result"));
        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Failed to call MCP tool '" + toolName + "' on '" + config.name + "'", e,
                    "MCP tool call failed: " + e.getMessage(), "Inspect the MCP server connection and retry.");
        }
    }

    @Override
    public boolean ping() {
        if (!connected) return false;
        try {
            JsonObject response = sendRequest("ping", new JsonObject());
            return response != null && McpJsonRpc.extractError(response) == null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getServerName() {
        return config.name;
    }

    @Override
    public synchronized void close() {
        connected = false;
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    // ========== internal ==========

    private void sendNotification(String method, JsonObject params) throws IOException {
        // Notifications get no JSON-RPC response; fire-and-forget POST.
        Request request = buildRequest(McpJsonRpc.buildNotification(method, params));
        try (Response response = httpClient.newCall(request).execute()) {
            captureSessionId(response);
        }
    }

    private JsonObject sendRequest(String method, JsonObject params) throws IOException {
        long id = idGenerator.getAndIncrement();
        Request request = buildRequest(McpJsonRpc.buildRequest(id, method, params));
        try (Response response = httpClient.newCall(request).execute()) {
            captureSessionId(response);
            if (!response.isSuccessful()) {
                LOG.warn("MCP HTTP request failed: method=" + method + ", HTTP " + response.code());
                return null;
            }
            String contentType = response.header("Content-Type", "");
            String body = response.body() != null ? response.body().string() : "";
            if (body.isBlank()) return null;

            if (contentType != null && contentType.contains("text/event-stream")) {
                return parseSseBody(body, id);
            }
            return gson.fromJson(body, JsonObject.class);
        }
    }

    /** Scans an SSE-formatted response body for the JSON-RPC message matching expectedId. */
    private JsonObject parseSseBody(String body, long expectedId) {
        JsonObject lastMessage = null;
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            try {
                JsonObject obj = gson.fromJson(data, JsonObject.class);
                if (obj == null) continue;
                if (McpJsonRpc.isResponse(obj) && obj.get("id").getAsLong() == expectedId) {
                    return obj;
                }
                lastMessage = obj;
            } catch (JsonSyntaxException ignored) {
            }
        }
        return lastMessage;
    }

    private Request buildRequest(JsonObject payload) {
        Request.Builder builder = new Request.Builder()
                .url(config.url)
                .addHeader("Accept", "application/json, text/event-stream")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")));
        if (sessionId != null) {
            builder.addHeader("Mcp-Session-Id", sessionId);
        }
        if (config.headers != null) {
            for (AiAgentSettings.HeaderEntry h : config.headers) {
                if (h.key != null && !h.key.isEmpty()) {
                    builder.addHeader(h.key, h.value == null ? "" : h.value);
                }
            }
        }
        return builder.build();
    }

    private void captureSessionId(Response response) {
        String header = response.header("Mcp-Session-Id");
        if (header != null && !header.isBlank()) {
            sessionId = header;
        }
    }
}
