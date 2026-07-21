package com.agent.platform.ordercare.incident.scope.client;

import com.agent.platform.ordercare.incident.scope.model.IncidentScopeAnomalyType;

import java.time.LocalDateTime;
import java.util.List;

public record FlowOrderOrderCandidates(String discoveryRequestId,
                                       LocalDateTime observedAt,
                                       List<Candidate> candidates,
                                       int candidateCount,
                                       boolean truncated,
                                       String nextCursor) {
    public FlowOrderOrderCandidates {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        nextCursor = nextCursor == null ? "" : nextCursor;
    }

    public record Candidate(String requestId, String orderNo, String deductNo,
                            Integer orderStatus, Integer reservationStatus,
                            LocalDateTime observedAt,
                            List<IncidentScopeAnomalyType> anomalyTypes,
                            List<SourceReference> sourceReferences) {
    }

    public record SourceReference(String sourceSystem, String sourceType,
                                  String sourceId, LocalDateTime observedAt) {
    }
}
