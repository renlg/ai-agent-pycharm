package com.taiwei.aiagent.mcp;

/**
 * 从 MCP 服务器 tools/list 返回的单个工具描述
 */
public class McpToolInfo {

    private final String name;
    private final String description;
    private final String inputSchema;

    public McpToolInfo(String name, String description, String inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /** JSON Schema 字符串，描述该工具的入参 */
    public String getInputSchema() {
        return inputSchema;
    }
}
