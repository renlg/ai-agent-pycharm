package com.taiwei.aiagent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses MCP {@code tools/list} results and builds {@code tools/call} params.
 * Shared by all McpClient transport implementations.
 */
final class McpToolListParser {

    private static final String DEFAULT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private McpToolListParser() {
    }

    static List<McpToolInfo> parse(JsonObject result) {
        if (result == null || !result.has("tools") || !result.get("tools").isJsonArray()) {
            return Collections.emptyList();
        }
        List<McpToolInfo> tools = new ArrayList<>();
        for (JsonElement el : result.getAsJsonArray("tools")) {
            if (!el.isJsonObject()) continue;
            JsonObject t = el.getAsJsonObject();
            String name = t.has("name") && !t.get("name").isJsonNull() ? t.get("name").getAsString() : "";
            String description = t.has("description") && !t.get("description").isJsonNull()
                    ? t.get("description").getAsString() : "";
            String inputSchema = t.has("inputSchema") && !t.get("inputSchema").isJsonNull()
                    ? t.get("inputSchema").toString() : DEFAULT_SCHEMA;
            tools.add(new McpToolInfo(name, description, inputSchema));
        }
        return tools;
    }

    /** Returns null if argumentsJson is present but not valid JSON. */
    static JsonObject buildCallParams(Gson gson, String toolName, String argumentsJson) {
        JsonObject argsObj;
        try {
            argsObj = (argumentsJson == null || argumentsJson.isBlank())
                    ? new JsonObject()
                    : gson.fromJson(argumentsJson, JsonObject.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", argsObj != null ? argsObj : new JsonObject());
        return params;
    }

    static JsonArray asArray(JsonElement el) {
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }
}
