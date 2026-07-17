package com.agent.platform.ordercare.model;

import java.util.List;

/**
 * enterprise-agent 对 FlowOrder v1 案例契约的强类型视图。
 */
public record OrderCareCaseSnapshot(
        String schemaVersion,
        String caseKey,
        String identifierType,
        String identifierValue,
        String canonicalRequestId,
        Boolean found,
        String diagnosisCode,
        Boolean factsComplete,
        Boolean recoveryEligible,
        String generatedAt,
        ReservationFact reservation,
        OrderFact order,
        DeductFact deduct,
        InventoryFact inventory,
        List<DeadLetterFact> deadLetters,
        List<RecoveryActionFact> recoveryActions,
        List<RecoveryCandidate> candidates,
        List<String> evidence,
        List<String> hardRisks
) {

    public OrderCareCaseSnapshot {
        deadLetters = deadLetters == null ? List.of() : List.copyOf(deadLetters);
        recoveryActions = recoveryActions == null ? List.of() : List.copyOf(recoveryActions);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        hardRisks = hardRisks == null ? List.of() : List.copyOf(hardRisks);
    }

    public record ReservationFact(
            Boolean exists,
            Long id,
            String requestId,
            String traceId,
            Integer status,
            String statusName,
            String orderNo,
            Integer orderStatus,
            String orderStatusName,
            String latestOrderEventType,
            String latestOrderEventTime,
            Integer orderEventVersion,
            String lastError
    ) {
    }

    public record OrderFact(
            Boolean dependencyAvailable,
            Boolean exists,
            String orderNo,
            Integer status,
            String statusName,
            String queryError
    ) {
    }

    public record DeductFact(
            Boolean exists,
            Long id,
            String deductNo,
            String orderNo,
            Long stockItemId,
            Integer quantity,
            Integer status,
            String statusName,
            String releaseReason,
            String lastError,
            String updatedAt
    ) {
    }

    public record InventoryFact(
            Boolean exists,
            Long stockItemId,
            Integer totalStock,
            Integer availableStock,
            Integer lockedStock,
            Integer soldStock,
            Integer invariantDiff,
            Boolean invariantOk,
            Integer version,
            String updatedAt
    ) {
    }

    public record DeadLetterFact(
            Long deadLetterId,
            String messageId,
            String deadQueue,
            String producerService,
            String messageType,
            String bizKey,
            Integer status,
            String statusName,
            Integer replayCount,
            String deathReason,
            String lastError,
            String replayedAt,
            String resolvedAt,
            String updatedAt
    ) {
    }

    public record RecoveryActionFact(
            Long actionId,
            String actionRequestId,
            String actionType,
            String targetType,
            String targetKey,
            Integer status,
            String statusName,
            String lastError,
            String updatedAt
    ) {
    }

    public record RecoveryCandidate(
            String candidateId,
            String actionType,
            String targetType,
            String targetKey,
            Boolean eligible,
            String decisionOwner,
            String blockedBy
    ) {
    }
}
