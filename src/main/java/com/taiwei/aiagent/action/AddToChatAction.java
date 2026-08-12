package com.taiwei.aiagent.action;

/**
 * 右键动作：把选中的代码放入聊天输入框（不直接发送），供用户补充说明后提交
 */
public class AddToChatAction extends BaseEditorChatAction {

    @Override
    protected boolean insertOnly() {
        return true;
    }

    @Override
    protected String buildPrompt(String filePath, String language, String code) {
        return "以下是来自 `" + filePath + "` 的代码：\n\n"
                + "```" + language + "\n" + code + "\n```\n\n";
    }
}
