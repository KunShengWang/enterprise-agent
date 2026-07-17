package com.agent.platform.ordercare.model;

/** FlowOrder Recovery Action v1 的权威查询/对账视图。 */
public record OrderCareRecoveryAction(
        String schemaVersion,
        String proposalId,
        String actionRequestId,
        String actionType,
        String targetType,
        String targetKey,
        String actionStatus,
        String caseOutcome,
        String reconciliationStatus,
        String executionOwner,
        String executionLeaseUntil,
        String lastHeartbeatAt,
        Boolean leaseExpired,
        Integer reconcileCount,
        String lastError,
        String executeResult,
        String reconciledAt,
        String createdAt,
        String updatedAt
) {
}
