package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.taiwei.aiagent.browser.BrowserToolService;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

import javax.swing.*;

public class BrowserTool implements Tool {

    private static final Logger LOG = Logger.getInstance(BrowserTool.class);

    private final Project project;

    public BrowserTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "browser";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.browser");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["open_url", "get_content", "execute_js", "capture_network"],
                      "description": "要执行的操作：open_url 打开网页，get_content 获取页面内容，execute_js 执行 JS 代码，capture_network 捕获网络请求"
                    },
                    "url": {
                      "type": "string",
                      "description": "要打开的 URL（仅 open_url 操作需要）"
                    },
                    "code": {
                      "type": "string",
                      "description": "要执行的 JavaScript 代码（仅 execute_js 操作需要）"
                    },
                    "content_type": {
                      "type": "string",
                      "enum": ["html", "text"],
                      "description": "获取内容的类型（仅 get_content 操作使用，默认 html）"
                    }
                  },
                  "required": ["action"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String action = args.get("action").getAsString();

            BrowserToolService service = BrowserToolService.getInstance(project);

            return switch (action) {
                case "open_url" -> executeOpenUrl(service, args);
                case "get_content" -> executeGetContent(service, args);
                case "execute_js" -> executeJs(service, args);
                case "capture_network" -> service.captureNetwork();
                default -> ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.browser.unknownAction", action),
                        I18nUtil.getMessage("tool.browser.supportedActions"));
            };
        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Browser tool failed", e,
                    I18nUtil.getMessage("tool.browser.failed", e.getMessage()), I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    private String executeOpenUrl(BrowserToolService service, JsonObject args) {
        if (!args.has("url") || args.get("url").getAsString().isEmpty()) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.browser.urlRequired"), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }
        String url = args.get("url").getAsString();

        activateToolWindow();
        service.openUrl(url);
        return I18nUtil.getMessage("tool.browser.opened", url);
    }

    private String executeGetContent(BrowserToolService service, JsonObject args) {
        String contentType = args.has("content_type") ? args.get("content_type").getAsString() : "html";
        return service.getContent(contentType);
    }

    private String executeJs(BrowserToolService service, JsonObject args) {
        if (!args.has("code") || args.get("code").getAsString().isEmpty()) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.browser.codeRequired"), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }
        String code = args.get("code").getAsString();
        return service.executeJavaScript(code);
    }

    private void activateToolWindow() {
        SwingUtilities.invokeLater(() -> {
            ToolWindowManager twm = ToolWindowManager.getInstance(project);
            ToolWindow tw = twm.getToolWindow("浏览器");
            if (tw != null) {
                tw.activate(null);
            }
        });
    }

    @Override
    public boolean isMutating() {
        return false;
    }
}
