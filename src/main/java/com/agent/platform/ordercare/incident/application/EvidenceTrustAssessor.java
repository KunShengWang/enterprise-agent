package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.EvidenceConflict;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceTrust;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EvidenceTrustAssessor {

    public List<EvidenceTrust> assess(IncidentSnapshot snapshot,
                                      List<EvidenceRecord> evidence,
                                      List<EvidenceConflict> conflicts) {
        Set<String> conflicted = (conflicts == null ? List.<EvidenceConflict>of() : conflicts).stream()
                .filter(conflict -> "OPEN".equals(conflict.status()))
                .flatMap(conflict -> conflict.relatedEvidenceIds().stream())
                .collect(Collectors.toSet());
        return (evidence == null ? List.<EvidenceRecord>of() : evidence).stream()
                .map(item -> assessOne(snapshot, item, conflicted.contains(item.evidenceId())))
                .toList();
    }

    private EvidenceTrust assessOne(IncidentSnapshot snapshot,
                                    EvidenceRecord evidence,
                                    boolean conflicted) {
        int reliability = sourceReliability(evidence.sourceSystem());
        long populated = evidence.facts().values().stream().filter(value -> value != null).count();
        int completeness = evidence.facts().isEmpty()
                ? 0
                : (int) Math.round(100.0 * populated / evidence.facts().size());
        Instant reference = snapshot == null || snapshot.investigationStartedAt() == null
                ? Instant.now()
                : snapshot.investigationStartedAt();
        long ageSeconds = evidence.observedAt() == null
                ? Long.MAX_VALUE
                : Math.abs(Duration.between(evidence.observedAt(), reference).toSeconds());
        int freshness = ageSeconds <= 120 ? 100 : ageSeconds <= 600 ? 70 : ageSeconds <= 3600 ? 30 : 0;
        int crossValidation = conflicted ? 0 : 50;
        String label = conflicted ? "CONFLICTED" : "UNCHECKED";
        int score = (int) Math.round(
                0.35 * reliability + 0.25 * completeness + 0.20 * freshness + 0.20 * crossValidation);
        return new EvidenceTrust(
                evidence.evidenceId(), reliability, completeness, freshness,
                crossValidation, score, label);
    }

    private int sourceReliability(String sourceSystem) {
        String source = sourceSystem == null ? "" : sourceSystem.toLowerCase(Locale.ROOT);
        if (source.contains("floworder")) {
            return 95;
        }
        if (source.contains("rabbitmq")) {
            return 90;
        }
        if (source.contains("rag")) {
            return 70;
        }
        return 50;
    }
}
