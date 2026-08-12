package com.taiwei.aiagent.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * JSON-RPC 2.0 / MCP 协议消息构造与解析的公共逻辑，供三种传输实现复用。
 */
final class McpJsonRpc {

    static final String PROTOCOL_VERSION = "2024-11-05";

    private McpJsonRpc() {
    }

    static JsonObject buildRequest(long id, String method, JsonObject params) {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", params != null ? params : new JsonObject());
        return req;
    }

    static JsonObject buildNotification(String method, JsonObject params) {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("method", method);
        req.add("params", params != null ? params : new JsonObject());
        return req;
    }

    static JsonObject buildInitializeParams(String clientName, String clientVersion) {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", clientName);
        clientInfo.addProperty("version", clientVersion);
        params.add("clientInfo", clientInfo);
        return params;
    }

    /** True if obj looks like a JSON-RPC response (has id + result/error), as opposed to a request/notification. */
    static boolean isResponse(JsonObject obj) {
        return obj != null && obj.has("id") && !obj.get("id").isJsonNull()
                && (obj.has("result") || obj.has("error"));
    }

    /** Returns the JSON-RPC error message, or null if the response has no error. */
    static String extractError(JsonObject response) {
        if (response == null) return "empty response";
        if (response.has("error") && !response.get("error").isJsonNull()) {
            JsonElement errElement = response.get("error");
            if (errElement.isJsonObject()) {
                JsonObject err = errElement.getAsJsonObject();
                return err.has("message") && !err.get("message").isJsonNull()
                        ? err.get("message").getAsString() : "Unknown MCP error";
            }
            return errElement.toString();
        }
        return null;
    }

    /** Flattens an MCP tools/call "result" object's content blocks into plain text. */
    static String extractToolResultText(JsonObject result) {
        if (result == null) return "";
        StringBuilder sb = new StringBuilder();
        if (result.has("content") && result.get("content").isJsonArray()) {
            JsonArray content = result.getAsJsonArray("content");
            for (JsonElement el : content) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                if (item.has("text") && !item.get("text").isJsonNull()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(item.get("text").getAsString());
                }
            }
        }
        if (sb.length() == 0) {
            sb.append(result);
        }
        boolean isError = result.has("isError") && !result.get("isError").isJsonNull()
                && result.get("isError").getAsBoolean();
        return isError ? "Error: " + sb : sb.toString();
    }
}
