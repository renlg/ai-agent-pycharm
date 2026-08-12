package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.skill.Skill;
import com.taiwei.aiagent.skill.SkillManager;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Optional;

public class LoadSkillTool implements Tool {

    private static final Logger LOG = Logger.getInstance(LoadSkillTool.class);

    private final Project project;

    public LoadSkillTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "load_skill";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.loadSkill");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string",
                      "description": "要加载的 Skill 名称（与「可用的 Skill」列表中的名称一致）"
                    }
                  },
                  "required": ["name"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String name = args.get("name").getAsString();

            Optional<Skill> skill = SkillManager.getInstance(project).getSkill(name);
            if (skill.isEmpty()) {
                return ToolError.of("SKILL_NOT_FOUND", I18nUtil.getMessage("tool.skill.notFound", name),
                        I18nUtil.getMessage("tool.skill.notFoundHint"));
            }
            return skill.get().getContent();
        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Failed to load skill", e,
                    I18nUtil.getMessage("tool.skill.failed", e.getMessage()),
                    I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    @Override
    public boolean isMutating() {
        return false;
    }
}
