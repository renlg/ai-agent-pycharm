package com.taiwei.aiagent.llm;

import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.tool.Tool;

import java.util.List;

/**
 * LLM 客户端接口
 * 定义大模型调用的统一抽象，支持不同模型提供商
 */
public interface LlmClient {

    /**
     * 同步调用（非流式）
     *
     * @param messages 消息列表
     * @param tools    可用工具列表（可为 null）
     * @return LLM 响应
     */
    LlmResponse chat(List<ChatMessage> messages, List<Tool> tools);

    /**
     * 流式调用（SSE）
     *
     * @param messages 消息列表
     * @param tools    可用工具列表（可为 null）
     * @param listener 流式回调监听器
     */
    void chatStream(List<ChatMessage> messages, List<Tool> tools, LlmStreamListener listener);

    /**
     * 检测模型是否可用
     *
     * @return true 如果配置有效且可连通
     */
    boolean isAvailable();

    /**
     * 获取当前模型名称
     */
    String getModelName();

    /**
     * 取消正在进行的流式调用
     */
    void cancel();

    /**
     * 释放底层资源（连接池、线程池等）
     */
    default void close() {
    }

    /**
     * 获取当前模型的上下文窗口大小
     * 用于计算压缩阈值
     *
     * @return 上下文窗口大小（Token 数），0 表示未知
     */
    default int getContextWindowSize() {
        return 0;
    }
}
