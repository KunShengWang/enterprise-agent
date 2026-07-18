package com.agent.platform.ordercare.incident.model;

import java.util.List;

public record IncidentMqFactsResult(
        IncidentFactEnvelope<IncidentDeadLetterFacts> deadLetterFacts,
        BrokerObservation brokerObservation,
        boolean partial,
        List<EvidenceGap> evidenceGaps
) {
    public IncidentMqFactsResult {
        evidenceGaps = evidenceGaps == null ? List.of() : List.copyOf(evidenceGaps);
    }
}
