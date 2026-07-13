package com.agent.platform.runtime;

/**
 * AgentLoop 的显式终止原因，避免只用成功/失败两个模糊状态。
 */
public enum AgentStopReason {
    COMPLETED,
    WAITING_APPROVAL,
    MAX_TURNS,
    MODEL_BUDGET_EXHAUSTED,
    TOOL_BUDGET_EXHAUSTED,
    TIMEOUT,
    CANCELLED,
    GUARDRAIL_BLOCKED,
    MODEL_ERROR,
    TOOL_ERROR,
    CONTEXT_OVERFLOW,
    INTERNAL_ERROR
}
