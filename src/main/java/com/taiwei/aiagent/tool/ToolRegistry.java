package com.taiwei.aiagent.tool;

import com.taiwei.aiagent.agent.AgentMode;
import com.taiwei.aiagent.settings.AiAgentSettings;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心
 * 管理所有 Agent 可调用的工具实例
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具
     */
    public void register(Tool tool) {
        if (tool == null || tool.getName() == null) {
            throw new IllegalArgumentException("工具及其名称不能为空");
        }
        tools.put(tool.getName(), tool);
    }

    /**
     * 注销工具
     */
    public void unregister(String toolName) {
        tools.remove(toolName);
    }

    /**
     * 根据名称获取工具
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册工具列表
     */
    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 根据当前 Agent 模式获取可用工具列表
     * Plan 模式下过滤掉所有修改性工具（isMutating() == true），只保留只读工具
     * 用户在设置中禁用的工具始终会被过滤掉
     */
    public List<Tool> getToolsForMode(AgentMode mode) {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        List<Tool> result = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (!settings.isToolEnabled(tool.getName())) {
                continue;
            }
            if (mode == AgentMode.PLAN && tool.isMutating()) {
                continue;
            }
            result.add(tool);
        }
        return result;
    }

    /**
     * 检查指定工具在当前模式下是否允许调用
     * 用于防御 LLM 在 Plan 模式下仍尝试调用未提供的修改性工具，或调用已被用户禁用的工具
     */
    public boolean isToolAllowed(String name, AgentMode mode) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return false;
        }
        if (!AiAgentSettings.getInstance().isToolEnabled(name)) {
            return false;
        }
        return mode != AgentMode.PLAN || !tool.isMutating();
    }

    /**
     * 检查工具是否已注册
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取已注册工具数量
     */
    public int size() {
        return tools.size();
    }
}
