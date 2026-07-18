package com.agent.platform.ordercare.incident.model;

public record IncidentInvestigationResult(
        IncidentRecord incident,
        IncidentAssessment assessment,
        IncidentAggregate aggregate
) {}
