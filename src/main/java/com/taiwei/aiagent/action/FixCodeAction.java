package com.taiwei.aiagent.action;

/**
 * 右键动作：查找并修复选中代码中的问题
 */
public class FixCodeAction extends BaseEditorChatAction {

    @Override
    protected String buildPrompt(String filePath, String language, String code) {
        return "请检查以下来自 `" + filePath + "` 的代码，找出潜在的 bug、性能问题和不规范之处，"
                + "并直接修改文件修复它们（修改前先用 read_file 查看完整上下文）：\n\n"
                + "```" + language + "\n" + code + "\n```";
    }
}
