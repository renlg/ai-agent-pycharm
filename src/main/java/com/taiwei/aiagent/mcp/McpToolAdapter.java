package com.taiwei.aiagent.mcp;

import com.taiwei.aiagent.tool.Tool;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 将单个 MCP 工具适配为 Agent {@link Tool}，委托 execute() 给对应的 {@link McpClient}
 */
public class McpToolAdapter implements Tool {

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^a-zA-Z0-9_]");

    private final McpToolInfo toolInfo;
    private final McpClient client;
    private final String serverName;
    private final String qualifiedName;

    public McpToolAdapter(McpToolInfo toolInfo, McpClient client, String serverName) {
        this.toolInfo = toolInfo;
        this.client = client;
        this.serverName = serverName;
        this.qualifiedName = "mcp_" + sanitize(serverName) + "_" + sanitize(toolInfo.getName());
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        return UNSAFE_CHARS.matcher(raw.trim()).replaceAll("_").toLowerCase(Locale.ROOT);
    }

    @Override
    public String getName() {
        return qualifiedName;
    }

    @Override
    public String getDescription() {
        String description = toolInfo.getDescription();
        return "[MCP:" + serverName + "] " + (description == null ? "" : description);
    }

    @Override
    public String getParametersSchema() {
        String schema = toolInfo.getInputSchema();
        return (schema == null || schema.isBlank()) ? "{\"type\":\"object\",\"properties\":{}}" : schema;
    }

    @Override
    public String execute(String arguments) {
        return client.callTool(toolInfo.getName(), arguments);
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    public String getServerName() {
        return serverName;
    }
}
