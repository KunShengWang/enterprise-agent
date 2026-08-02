package com.agent.platform.workbench.model;

import java.time.Instant;

public record AgentWorkItem(
        String workItemId,
        String conversationId,
        String tenantId,
        String ownerPrincipalId,// 属于哪个用户
        String originalGoal,// 用户原始目标，如：“麻烦帮我看看订单 123 咋还没发货”
        String normalizedGoal,// 系统规范化后的目标，如：“调查订单 123 未发货的原因”
        WorkControlState controlState,// Workbench 控制到了哪一步
        WorkExecutionState executionState,// 底层执行器执行到了哪一步
        WorkOutcome outcome,// 最终业务结果是什么
        String activeExecutionTarget,
        String activeRunId,
        String activeIncidentId,
        String activeRecoveryPlanId,
        String routeDecisionId,
        String sourceInputId,// 从哪条用户输入创建
        String parentWorkItemId,
        String routingRequestId,
        int routingAttemptCount,
        Instant routingLastAttemptAt,
        Instant routingNextRetryAt,
        String routingFailureCode,
        String dispatchRequestId,
        long nextEventSequence,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
