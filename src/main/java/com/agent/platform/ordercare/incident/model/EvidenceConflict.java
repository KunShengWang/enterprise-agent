package com.agent.platform.ordercare.incident.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvidenceConflict(
        String conflictId,
        EvidenceConflictType conflictType,
        String metricKey,
        ConflictSeverity severity,
        List<String> relatedEvidenceIds,
        Map<String, Object> details,
        String status
) {
    public EvidenceConflict {
        relatedEvidenceIds = relatedEvidenceIds == null ? List.of() : relatedEvidenceIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
        details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        status = status == null || status.isBlank() ? "OPEN" : status.trim();
    }
}
