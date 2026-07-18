package com.agent.platform.ordercare.incident.model;

import java.time.LocalDateTime;
import java.util.List;

public record IncidentDeadLetterFacts(
        Integer recordCount,
        Integer totalMatchingRecordCount,
        Integer distinctBizKeyCount,
        Integer distinctRequestIdCount,
        Integer duplicateRecordCount,
        Integer unmappedRecordCount,
        List<String> bizKeys,
        List<String> requestIds,
        List<Long> deadLetterIds,
        List<DuplicateGroup> duplicateGroups,
        List<DeadLetterFact> items
) {
    public IncidentDeadLetterFacts {
        bizKeys = bizKeys == null ? List.of() : List.copyOf(bizKeys);
        requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        deadLetterIds = deadLetterIds == null ? List.of() : List.copyOf(deadLetterIds);
        duplicateGroups = duplicateGroups == null ? List.of() : List.copyOf(duplicateGroups);
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record DuplicateGroup(String bizKey, Integer recordCount, List<Long> deadLetterIds) {
        public DuplicateGroup {
            deadLetterIds = deadLetterIds == null ? List.of() : List.copyOf(deadLetterIds);
        }
    }

    public record DeadLetterFact(
            Long deadLetterId,
            String messageId,
            String deadQueue,
            String messageType,
            String bizKey,
            String requestId,
            Integer status,
            Integer replayCount,
            String deathReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
