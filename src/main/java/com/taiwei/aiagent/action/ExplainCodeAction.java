package com.taiwei.aiagent.action;

/**
 * 右键动作：解释选中的代码
 */
public class ExplainCodeAction extends BaseEditorChatAction {

    @Override
    protected String buildPrompt(String filePath, String language, String code) {
        return "请解释以下来自 `" + filePath + "` 的代码的功能、关键逻辑和注意点：\n\n"
                + "```" + language + "\n" + code + "\n```";
    }
}
