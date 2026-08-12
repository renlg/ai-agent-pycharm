package com.taiwei.aiagent.action;

/**
 * 右键动作：为选中的代码生成单元测试
 */
public class GenerateTestsAction extends BaseEditorChatAction {

    @Override
    protected String buildPrompt(String filePath, String language, String code) {
        if ("python".equalsIgnoreCase(language) || filePath.toLowerCase().endsWith(".py")) {
            return "请为以下来自 `" + filePath + "` 的 Python 代码编写单元测试："
                    + "先用 list_directory / search_code（优先查看 test_*.py、*_test.py、pyproject.toml、pytest.ini、tox.ini）"
                    + "确认项目使用 pytest 还是 unittest，并遵循已有测试目录、fixture、异步测试和 mock 风格；"
                    + "若项目没有既定框架，优先使用 pytest。覆盖正常路径、边界条件和异常路径，"
                    + "使用正确的 Python 模块导入，然后在合适的位置创建可直接运行的测试文件。\n\n"
                    + "```python\n" + code + "\n```";
        }
        return "请为以下来自 `" + filePath + "` 的代码编写单元测试："
                + "先用 list_directory / search_code 了解项目的测试框架和已有测试的风格，"
                + "然后在合适的位置创建测试文件：\n\n"
                + "```" + language + "\n" + code + "\n```";
    }
}
