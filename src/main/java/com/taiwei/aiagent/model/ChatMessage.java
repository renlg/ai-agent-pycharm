package com.taiwei.aiagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息模型
 * 对应 OpenAI Chat Completion API 的 message 结构
 */
public class ChatMessage {

    /**
     * 消息角色: system / user / assistant / tool
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 工具调用 ID（仅 role=tool 时使用）
     */
    private String toolCallId;

    /**
     * 工具调用列表（仅 role=assistant 且需要调用工具时使用）
     */
    private ToolCall[] toolCalls;

    /**
     * 图片内容列表（视觉输入，仅 role=user 时使用）
     */
    private List<ImageContent> imageContents;

    public ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    // ========== 静态工厂方法 ==========

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage userWithImages(String content, List<ImageContent> images) {
        ChatMessage msg = new ChatMessage("user", content);
        msg.setImageContents(images);
        return msg;
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage tool(String content, String toolCallId) {
        ChatMessage msg = new ChatMessage("tool", content);
        msg.setToolCallId(toolCallId);
        return msg;
    }

    // ========== Getters & Setters ==========

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public ToolCall[] getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(ToolCall[] toolCalls) {
        this.toolCalls = toolCalls;
    }

    public List<ImageContent> getImageContents() {
        return imageContents;
    }

    public void setImageContents(List<ImageContent> imageContents) {
        this.imageContents = imageContents;
    }

    public boolean hasImages() {
        return imageContents != null && !imageContents.isEmpty();
    }

    /**
     * 图片内容（视觉输入）
     */
    public static class ImageContent {
        private String base64Data;
        private String mimeType;

        public ImageContent() {
        }

        public ImageContent(String base64Data, String mimeType) {
            this.base64Data = base64Data;
            this.mimeType = mimeType;
        }

        public String getBase64Data() {
            return base64Data;
        }

        public void setBase64Data(String base64Data) {
            this.base64Data = base64Data;
        }

        public String getMimeType() {
            return mimeType;
        }

        public void setMimeType(String mimeType) {
            this.mimeType = mimeType;
        }
    }

    /**
     * 工具调用结构
     */
    public static class ToolCall {
        private String id;
        private String type = "function";
        private FunctionCall function;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public FunctionCall getFunction() {
            return function;
        }

        public void setFunction(FunctionCall function) {
            this.function = function;
        }
    }

    /**
     * 函数调用详情
     */
    public static class FunctionCall {
        private String name;
        private String arguments;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }
    }
}
