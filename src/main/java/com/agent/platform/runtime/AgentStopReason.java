package com.agent.platform.runtime;

/**
 * AgentLoop 的显式终止原因，避免只用成功/失败两个模糊状态。
 */
public enum AgentStopReason {
    /** 正常完成：模型输出最终回答，无需继续调用工具 */
    COMPLETED,
    /** 执行中：Agent 仍在循环中（工具结果回填后继续规划） */
    IN_PROGRESS,
    /** 等待人工审批：高风险工具触发 HITL，暂停等待管理员确认 */
    WAITING_APPROVAL,
    /** 等待受控定向输入：Runtime 已保存检查点并释放执行资源 */
    WAITING_INPUT,
    /** 轮次耗尽：ReAct 循环次数达到 maxModelCallsPerRun 上限 */
    MAX_TURNS,
    /** 模型预算耗尽：LLM 调用次数或 token 消耗达到上限 */
    MODEL_BUDGET_EXHAUSTED,
    /** 工具预算耗尽：工具调用次数达到 maxToolCallsPerRun 上限 */
    TOOL_BUDGET_EXHAUSTED,
    /** 超时：整个 Agent 执行超过配置的最大时间 */
    TIMEOUT,
    /** 主动取消：外部通过 runId 发送取消信号中断执行 */
    CANCELLED,
    /** 用户暂停：保留 Checkpoint，等待使用同一 runId 恢复 */
    PAUSED,
    /** 护栏拦截：输入/输出/工具的 Guardrail 判定为 BLOCK */
    GUARDRAIL_BLOCKED,
    /** 模型异常：LLM 调用失败（网络错误、API 限流、服务不可用等） */
    MODEL_ERROR,
    /** 工具异常：工具执行过程中抛出未预期的异常 */
    TOOL_ERROR,
    /** 上下文溢出：Prompt 拼接后超过模型最大上下文窗口 */
    CONTEXT_OVERFLOW,
    /** 内部错误：Agent 框架自身的未知异常 */
    INTERNAL_ERROR
}
