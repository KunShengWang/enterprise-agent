package com.agent.platform.runtime;

/**
 * Coarse persisted phase of the model-driven runtime. It is not a predefined workflow plan.
 */
public enum AgentRunPhase {
    /** 启动：run 刚创建，准备进入主循环 */
    START,
    /** 上下文准备：加载 Memory、执行 Guardrail、组装 Prompt */
    CONTEXT_PREPARATION,
    /** 模型调用：正在等待 LLM 返回（含流式输出） */
    MODEL_CALL,
    /** 等待审批：高风险工具触发了 HITL，等待人工决策 */
    WAITING_APPROVAL,
    /** 执行工具：ToolExecutor 正在调用本地或 MCP 工具 */
    EXECUTING_TOOL,
    /** 已完成：正常结束，输出最终回答 */
    FINISHED,
    /** 被拦截：Guardrail 判定 BLOCK，执行中断 */
    BLOCKED,
    /** 执行失败：Agent 内部抛出未预期异常 */
    FAILED
}
