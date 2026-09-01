package com.agent.platform.procurement.model;

import java.time.Instant;
import java.util.Set;

public record ProcurementCase(
        String caseId,
        String tenantId,
        String conversationId,
        String userId,
        ProcurementCaseStatus status,
        ProcurementCaseState state,
        Instant createdAt,
        Instant updatedAt,
        long version,
        String lastAppliedInputId,
        Set<String> appliedInputIds
) {
    public ProcurementCase(String caseId, String tenantId, String conversationId, String userId,
                           ProcurementCaseStatus status, ProcurementCaseState state,
                           Instant createdAt, Instant updatedAt, long version, String lastAppliedInputId) {
        this(caseId, tenantId, conversationId, userId, status, state, createdAt, updatedAt, version,
                lastAppliedInputId, lastAppliedInputId == null || lastAppliedInputId.isBlank()
                        ? Set.of() : Set.of(lastAppliedInputId));
    }

    public ProcurementCase {
        if (caseId == null || caseId.isBlank() || tenantId == null || tenantId.isBlank()
                || conversationId == null || conversationId.isBlank()
                || userId == null || userId.isBlank() || status == null || state == null
                || createdAt == null || updatedAt == null || version < 0) {
            throw new IllegalArgumentException("procurement case identity, status, state and timestamps are required");
        }
        caseId = caseId.trim(); tenantId = tenantId.trim(); conversationId = conversationId.trim(); userId = userId.trim();
        lastAppliedInputId = lastAppliedInputId == null ? "" : lastAppliedInputId.trim();
        appliedInputIds = appliedInputIds == null ? Set.of() : appliedInputIds.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
