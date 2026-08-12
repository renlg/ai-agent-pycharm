package com.taiwei.aiagent.mcp;

/**
 * MCP initialize 请求的结果
 */
public class McpInitResult {

    private final boolean success;
    private final String serverName;
    private final String serverVersion;
    private final String protocolVersion;
    private final String errorMessage;

    private McpInitResult(boolean success, String serverName, String serverVersion,
                           String protocolVersion, String errorMessage) {
        this.success = success;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.protocolVersion = protocolVersion;
        this.errorMessage = errorMessage;
    }

    public static McpInitResult success(String serverName, String serverVersion, String protocolVersion) {
        return new McpInitResult(true, serverName, serverVersion, protocolVersion, null);
    }

    public static McpInitResult failure(String errorMessage) {
        return new McpInitResult(false, null, null, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getServerName() {
        return serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
