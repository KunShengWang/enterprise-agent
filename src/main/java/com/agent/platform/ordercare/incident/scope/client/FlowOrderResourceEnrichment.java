package com.agent.platform.ordercare.incident.scope.client;

import com.agent.platform.ordercare.incident.scope.model.IncidentScopeAnomalyType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FlowOrderResourceEnrichment(String discoveryRequestId,
                                          LocalDateTime observedAt,
                                          List<Item> items,
                                          List<String> queueNames,
                                          Map<String, String> sourceHealth) {
    public FlowOrderResourceEnrichment {
        items = items == null ? List.of() : List.copyOf(items);
        queueNames = queueNames == null ? List.of() : List.copyOf(queueNames);
        sourceHealth = sourceHealth == null ? Map.of() : Map.copyOf(sourceHealth);
    }

    public record Item(String requestId, String orderNo, String deductNo,
                       Integer reservationStatus, Integer deductStatus,
                       String releaseState, Long stockItemId,
                       Integer stockAvailable, Integer stockLocked,
                       List<IncidentScopeAnomalyType> anomalyTypes,
                       List<DeadLetter> deadLetters,
                       String relationQuality, String completeness,
                       List<SourceReference> sourceReferences) {
    }

    public record DeadLetter(Long deadLetterId, String messageId, String deadQueue,
                             String exchange, String routingKey, String messageType,
                             Integer status, String relationQuality, LocalDateTime observedAt,
                             List<SourceReference> sourceReferences) {
    }

    public record SourceReference(String sourceSystem, String sourceType,
                                  String sourceId, LocalDateTime observedAt) {
    }
}
