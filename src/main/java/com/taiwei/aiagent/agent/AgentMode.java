package com.taiwei.aiagent.agent;

/**
 * Agent 工作模式
 * PLAN：只读分析模式，禁用所有修改性工具，最终以 Markdown 输出实施计划
 * BUILD：正常模式，允许所有工具调用和代码修改
 */
public enum AgentMode {
    BUILD,
    PLAN;

    public static AgentMode fromString(String value) {
        if (value == null) {
            return BUILD;
        }
        return "plan".equalsIgnoreCase(value.trim()) ? PLAN : BUILD;
    }

    public String toJsValue() {
        return this == PLAN ? "plan" : "build";
    }
}
