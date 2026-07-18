package com.agent.platform.ordercare.incident.model;

public record EvidenceTrust(
        String evidenceId,
        int sourceReliability,
        int dataCompleteness,
        int dataFreshness,
        int crossValidationStatus,
        int trustScore,
        String crossValidationLabel
) {}
