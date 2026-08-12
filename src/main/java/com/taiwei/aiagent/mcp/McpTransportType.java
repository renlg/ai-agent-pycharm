package com.taiwei.aiagent.mcp;

/**
 * MCP 服务器连接方式
 */
public enum McpTransportType {
    /** 本地子进程，stdin/stdout 传输 JSON-RPC 2.0 消息 */
    STDIO,
    /** 旧版 HTTP+SSE 传输（GET 建立 SSE 连接获取消息端点，POST 发送 JSON-RPC 消息） */
    SSE,
    /** 新版 Streamable HTTP 传输（单个 HTTP POST 端点，响应可为普通 JSON 或 SSE 流） */
    STREAMABLE_HTTP
}
