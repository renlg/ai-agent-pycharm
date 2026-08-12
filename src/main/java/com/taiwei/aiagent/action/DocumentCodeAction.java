package com.taiwei.aiagent.action;

/**
 * 右键动作：为选中的代码生成注释/文档
 */
public class DocumentCodeAction extends BaseEditorChatAction {

    @Override
    protected String buildPrompt(String filePath, String language, String code) {
        if ("python".equalsIgnoreCase(language) || filePath.toLowerCase().endsWith(".py")) {
            return "请为以下来自 `" + filePath + "` 的 Python 代码补充清晰、简洁的 docstring，"
                    + "先检查项目已有 docstring 风格（Google、NumPy、Sphinx 或简洁风格），"
                    + "准确描述参数、返回值、异常和异步行为；不要为显而易见的语句添加行内注释。"
                    + "请先用 read_file 查看完整上下文，再直接修改文件，不要改变代码逻辑：\n\n"
                    + "```python\n" + code + "\n```";
        }
        return "请为以下来自 `" + filePath + "` 的代码生成规范的文档注释（如 Javadoc/KDoc），"
                + "并直接修改文件写入这些注释（修改前先用 read_file 查看完整上下文，不要改动代码逻辑）：\n\n"
                + "```" + language + "\n" + code + "\n```";
    }
}
