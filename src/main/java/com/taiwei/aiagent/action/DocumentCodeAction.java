package com.taiwei.aiagent.action;

/**
 * 右键动作：为选中的代码生成注释/文档
 */
public class DocumentCodeAction extends BaseEditorChatAction {

    @Override
    protected String buildPrompt(String filePath, String language, String code) {
        return "请为以下来自 `" + filePath + "` 的代码生成规范的文档注释（如 Javadoc/KDoc），"
                + "并直接修改文件写入这些注释（修改前先用 read_file 查看完整上下文，不要改动代码逻辑）：\n\n"
                + "```" + language + "\n" + code + "\n```";
    }
}
