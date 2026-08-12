package com.taiwei.aiagent.llm;

/**
 * LLM 流式响应回调接口
 * 用于处理 SSE (Server-Sent Events) 流式输出
 */
public interface LlmStreamListener {

    /**
     * 收到内容片段
     *
     * @param delta 本次收到的文本增量
     */
    void onContent(String delta);

    /**
     * 收到工具调用（流式累积完成后回调）
     *
     * @param toolCallId   工具调用 ID
     * @param functionName 函数名
     * @param arguments    参数 JSON 字符串
     */
    void onToolCall(String toolCallId, String functionName, String arguments);

    /**
     * 收到 Token 使用统计
     *
     * @param usage Token 使用量信息
     */
    default void onUsage(LlmResponse.Usage usage) {}

    /**
     * 流式响应完成
     */
    void onComplete();

    /**
     * 发生错误
     *
     * @param error 错误信息
     * @param throwable 异常对象（可能为 null）
     */
    void onError(String error, Throwable throwable);
}
