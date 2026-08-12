package com.taiwei.aiagent.action;

/**
 * 右键动作：为选中的代码生成单元测试
 */
public class GenerateTestsAction extends BaseEditorChatAction {

    @Override
    protected String buildPrompt(String filePath, String language, String code) {
        return "请为以下来自 `" + filePath + "` 的代码编写单元测试："
                + "先用 list_directory / search_code 了解项目的测试框架和已有测试的风格，"
                + "然后在合适的位置创建测试文件：\n\n"
                + "```" + language + "\n" + code + "\n```";
    }
}
