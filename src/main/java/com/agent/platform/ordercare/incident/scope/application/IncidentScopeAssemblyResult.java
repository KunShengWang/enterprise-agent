package com.agent.platform.ordercare.incident.scope.application;

import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCandidate;

import java.util.List;
import java.util.Map;

public record IncidentScopeAssemblyResult(
        List<IncidentScopeCandidate> candidates,
        Map<String, String> sourceHealth,
        boolean truncated,
        String candidateFingerprint
) {
}
