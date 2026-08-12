package com.taiwei.aiagent.llm;

import com.taiwei.aiagent.model.ChatMessage;

/**
 * LLM 响应模型
 * 封装大模型返回的完整响应数据
 */
public class LlmResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;

    /**
     * 响应内容（非流式时完整内容）
     */
    private String content;

    /**
     * 工具调用列表
     */
    private ChatMessage.ToolCall[] toolCalls;

    /**
     * Token 使用情况
     */
    private Usage usage;

    /**
     * 原始响应（调试用）
     */
    private String rawResponse;

    // ========== 静态工厂方法 ==========

    public static LlmResponse success(String content) {
        LlmResponse resp = new LlmResponse();
        resp.success = true;
        resp.content = content;
        return resp;
    }

    public static LlmResponse successWithToolCalls(ChatMessage.ToolCall[] toolCalls, String content) {
        LlmResponse resp = new LlmResponse();
        resp.success = true;
        resp.toolCalls = toolCalls;
        resp.content = content;
        return resp;
    }

    public static LlmResponse error(String errorMessage) {
        LlmResponse resp = new LlmResponse();
        resp.success = false;
        resp.errorMessage = errorMessage;
        return resp;
    }

    // ========== Getters & Setters ==========

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public ChatMessage.ToolCall[] getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(ChatMessage.ToolCall[] toolCalls) {
        this.toolCalls = toolCalls;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    /**
     * 是否有工具调用
     */
    public boolean hasToolCalls() {
        return toolCalls != null && toolCalls.length > 0;
    }

    /**
     * Token 使用统计
     */
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;

        public int getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(int promptTokens) {
            this.promptTokens = promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(int completionTokens) {
            this.completionTokens = completionTokens;
        }

        public int getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
