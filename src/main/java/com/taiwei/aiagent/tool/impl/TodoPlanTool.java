package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量任务/计划追踪工具
 * 在 Agent 会话内以内存 Map 维护一个有序任务列表，支持增删改查
 */
public class TodoPlanTool implements Tool {

    private static final Logger LOG = Logger.getInstance(TodoPlanTool.class);

    private final List<String> items = new ArrayList<>();
    // index (0-based) -> completed
    private final Map<Integer, Boolean> completed = new HashMap<>();
    private String planName = "";

    @Override
    public String getName() {
        return "todo_plan";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.todoPlan");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["init", "add", "complete", "status"],
                      "description": "操作类型"
                    },
                    "plan_name": {
                      "type": "string",
                      "description": "[init] 计划名称"
                    },
                    "items": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "[init / add] 要添加的项目列表"
                    },
                    "item_index": {
                      "type": "integer",
                      "description": "[complete] 要标记完成的项目索引（1-based）"
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

            return switch (action) {
                case "init"     -> doInit(args);
                case "add"      -> doAdd(args);
                case "complete" -> doComplete(args);
                case "status"   -> doStatus();
                default -> ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.todo.unknownAction", action),
                        I18nUtil.getMessage("tool.todo.supportedActions"));
            };
        } catch (Exception e) {
            return ToolError.unexpected(LOG, "todo_plan failed", e,
                    I18nUtil.getMessage("tool.todo.failed", e.getMessage()), I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    private synchronized String doInit(JsonObject args) {
        planName = args.has("plan_name") ? args.get("plan_name").getAsString() : I18nUtil.getMessage("tool.todo.unnamed");
        items.clear();
        completed.clear();

        if (args.has("items")) {
            JsonArray arr = args.get("items").getAsJsonArray();
            for (var elem : arr) {
                items.add(elem.getAsString());
            }
        }

        return I18nUtil.getMessage("tool.todo.initialized", planName, items.size());
    }

    private synchronized String doAdd(JsonObject args) {
        if (!args.has("items")) return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.todo.itemsRequired"), I18nUtil.getMessage("tool.hint.provideValidArguments"));

        JsonArray arr = args.get("items").getAsJsonArray();
        int added = 0;
        for (var elem : arr) {
            items.add(elem.getAsString());
            added++;
        }
        return I18nUtil.getMessage("tool.todo.added", added, items.size());
    }

    private synchronized String doComplete(JsonObject args) {
        if (!args.has("item_index")) return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.todo.indexRequired"), I18nUtil.getMessage("tool.hint.provideValidArguments"));

        int idx = args.get("item_index").getAsInt();
        if (idx < 1 || idx > items.size()) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.todo.indexOutOfRange", idx, items.size()), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }

        completed.put(idx - 1, true);
        return I18nUtil.getMessage("tool.todo.completed", idx, items.get(idx - 1));
    }

    private synchronized String doStatus() {
        if (items.isEmpty()) {
            return I18nUtil.getMessage("tool.todo.empty");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(I18nUtil.getMessage("tool.todo.planLabel", planName)).append("\n\n");

        int doneCount = 0;
        for (int i = 0; i < items.size(); i++) {
            boolean done = completed.getOrDefault(i, false);
            if (done) doneCount++;
            sb.append(done ? "[x]" : "[ ]").append(" ").append(i + 1).append(". ").append(items.get(i)).append("\n");
        }

        sb.append("\n").append(I18nUtil.getMessage("tool.todo.progress", doneCount, items.size(), items.size() - doneCount));
        return sb.toString();
    }
}
