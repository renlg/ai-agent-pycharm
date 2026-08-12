package com.taiwei.aiagent.tool;

/**
 * Agent 工具接口
 * 所有可被 Agent 调用的工具都需要实现此接口
 */
public interface Tool {

    /**
     * 工具名称（唯一标识，用于 LLM 函数调用）
     * 建议使用英文，如 "read_file", "search_code"
     */
    String getName();

    /**
     * 工具描述（告知 LLM 该工具的功能和使用场景）
     */
    String getDescription();

    /**
     * 参数 JSON Schema（JSON Schema 格式，告知 LLM 如何构造参数）
     * 示例：
     * {
     *   "type": "object",
     *   "properties": {
     *     "path": { "type": "string", "description": "文件路径" }
     *   },
     *   "required": ["path"]
     * }
     */
    String getParametersSchema();

    /**
     * 执行工具
     *
     * @param arguments JSON 格式的参数
     * @return 工具执行结果（文本形式返回给 LLM）
     */
    String execute(String arguments);

    /**
     * 是否为修改性工具（写文件、执行命令等会改变项目状态的操作）。
     * Plan 模式下会过滤掉所有 isMutating() 返回 true 的工具，只保留只读工具。
     */
    default boolean isMutating() {
        return false;
    }
}
