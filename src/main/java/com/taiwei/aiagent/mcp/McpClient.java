package com.taiwei.aiagent.mcp;

import java.util.List;

/**
 * MCP（Model Context Protocol）客户端，屏蔽底层传输方式（stdio / SSE / streamable HTTP）
 * 所有实现必须捕获内部异常，绝不向调用方抛出，而是返回可读的错误信息。
 * 实现须保证线程安全，允许并发调用 callTool()。
 */
public interface McpClient {

    /**
     * 建立连接并完成 MCP initialize 握手
     */
    McpInitResult initialize();

    /**
     * 获取该服务器提供的工具列表
     */
    List<McpToolInfo> listTools();

    /**
     * 调用指定工具
     *
     * @param toolName     工具名（MCP 服务器原始名称，不带前缀）
     * @param argumentsJson JSON 格式参数
     * @return 工具执行结果文本
     */
    String callTool(String toolName, String argumentsJson);

    /**
     * 探活
     */
    boolean ping();

    /**
     * 当前是否处于已连接状态
     */
    boolean isConnected();

    /**
     * 关闭连接，释放底层资源（子进程 / HTTP 连接等）
     */
    void close();

    /**
     * 服务器显示名称（配置中的 name，用于工具前缀等）
     */
    String getServerName();
}
