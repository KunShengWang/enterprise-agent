package com.agent.platform.workbench.target;

/**
 * 表示 WorkItem 由哪一类"执行目标/执行器"来实际执行
 */
public enum ExecutionTargetId {
    GENERAL_AGENT,             // 总代理（通用 Agent），最纯粹的 Agent Runtime 主流程
    ORDERCARE_CASE,            // 订单护理案例（OrderCare 案例诊断/恢复），学习“通用 Runtime 如何承载真实业务”的最佳路径
    INCIDENT_INVESTIGATION,    // 事件调查（Incident Command），Multi-Agent
    INCIDENT_RECOVERY_PLAN     // 事件恢复计划（Recovery Plan）
}

