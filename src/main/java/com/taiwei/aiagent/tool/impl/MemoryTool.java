package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.memory.MemoryCategory;
import com.taiwei.aiagent.memory.MemoryManager;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 记忆保存工具
 * 用户要求"记住"某个事实时，Agent 通过此工具将其持久化到长期记忆
 */
public class MemoryTool implements Tool {

    private static final Logger LOG = Logger.getInstance(MemoryTool.class);

    private final Project project;

    public MemoryTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "save_memory";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.saveMemory");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "key": {
                      "type": "string",
                      "description": "记忆的简短标识，如 \\"favorite_drink\\""
                    },
                    "content": {
                      "type": "string",
                      "description": "要记住的内容，如 \\"用户喜欢喝冰美式\\""
                    },
                    "category": {
                      "type": "string",
                      "enum": ["fact", "preference", "context", "command"],
                      "description": "记忆分类（可选，默认 fact）：fact-事实，preference-偏好，context-上下文，command-常用命令。无论哪种分类，都应同时生成 tags 同义词标签"
                    },
                    "tags": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "中文同义词标签，务必提供 2-5 个便于检索的同义词。保存地址/位置信息时必须包含\\"地址\\"\\"位置\\"及地名等同义标签，如\\"我家地址是杭州西湖区\\"应给 [\\"地址\\",\\"位置\\",\\"杭州\\",\\"西湖区\\",\\"家\\"]"
                    }
                  },
                  "required": ["key", "content"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            if (!args.has("key") || !args.has("content")) {
                return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.memory.keyContentRequired"),
                        I18nUtil.getMessage("tool.hint.provideValidArguments"));
            }
            String key = args.get("key").getAsString().trim();
            String content = args.get("content").getAsString().trim();
            if (key.isEmpty() || content.isEmpty()) {
                return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.memory.keyContentEmpty"),
                        I18nUtil.getMessage("tool.hint.provideValidArguments"));
            }

            MemoryCategory category = parseCategory(
                    args.has("category") ? args.get("category").getAsString() : null);
            List<String> tags = collectTags(key, args);

            MemoryManager.getInstance(project).remember(content, category, tags, 5);
            return I18nUtil.getMessage("tool.memory.saved", key, content, String.join(", ", tags));

        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Failed to save memory", e,
                    I18nUtil.getMessage("tool.memory.saveFailed", e.getMessage()),
                    I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    /** Merges the key with the model-provided synonym tags, deduplicated and blank-filtered. */
    private static List<String> collectTags(String key, JsonObject args) {
        List<String> tags = new ArrayList<>();
        tags.add(key);
        if (args.has("tags") && args.get("tags").isJsonArray()) {
            for (JsonElement element : args.getAsJsonArray("tags")) {
                try {
                    String tag = element.getAsString().trim();
                    if (!tag.isEmpty() && !tags.contains(tag)) {
                        tags.add(tag);
                    }
                } catch (Exception ignored) {
                    // Non-string array items are skipped rather than failing the save.
                }
            }
        }
        return tags;
    }

    private MemoryCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return MemoryCategory.FACT;
        }
        try {
            return MemoryCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MemoryCategory.FACT;
        }
    }

    @Override
    public boolean isMutating() {
        return true;
    }
}
