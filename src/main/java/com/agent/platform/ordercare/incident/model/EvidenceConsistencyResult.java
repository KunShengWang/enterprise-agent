package com.agent.platform.ordercare.incident.model;

import java.util.List;

public record EvidenceConsistencyResult(
        List<EvidenceConflict> conflicts,
        List<NotComparableEvidence> notComparable
) {
    public EvidenceConsistencyResult {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        notComparable = notComparable == null ? List.of() : List.copyOf(notComparable);
    }

    public record NotComparableEvidence(
            String ruleId,
            List<String> relatedEvidenceIds,
            String reason
    ) {
        public NotComparableEvidence {
            relatedEvidenceIds = relatedEvidenceIds == null ? List.of() : List.copyOf(relatedEvidenceIds);
        }
    }
}
