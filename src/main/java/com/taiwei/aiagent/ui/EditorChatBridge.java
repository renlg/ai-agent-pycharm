package com.taiwei.aiagent.ui;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 编辑器 → 聊天面板的桥接服务（project-level）。
 * <p>
 * 编辑器右键动作（解释代码/修复代码等）通过本服务把提示词投递给当前打开的 {@link ChatPanel}，
 * 避免动作类直接持有 UI 组件引用。ChatPanel 在构造时注册、dispose 时注销。
 */
public class EditorChatBridge {

    private volatile ChatPanel activePanel;

    public static EditorChatBridge getInstance(@NotNull Project project) {
        return project.getService(EditorChatBridge.class);
    }

    public void register(ChatPanel panel) {
        this.activePanel = panel;
    }

    public void unregister(ChatPanel panel) {
        if (this.activePanel == panel) {
            this.activePanel = null;
        }
    }

    /**
     * 把提示词作为用户消息直接发送到聊天面板（立即触发 Agent 回复）。
     *
     * @return 聊天面板尚未创建时返回 false
     */
    public boolean sendPrompt(String prompt) {
        ChatPanel panel = activePanel;
        if (panel == null) {
            return false;
        }
        panel.submitExternalPrompt(prompt);
        return true;
    }

    /**
     * 把文本插入聊天输入框（不发送），供用户补充说明后再提交。
     *
     * @return 聊天面板尚未创建时返回 false
     */
    public boolean insertToInput(String text) {
        ChatPanel panel = activePanel;
        if (panel == null) {
            return false;
        }
        panel.insertExternalInputText(text);
        return true;
    }
}
