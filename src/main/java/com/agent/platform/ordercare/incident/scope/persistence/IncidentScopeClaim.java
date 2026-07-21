package com.agent.platform.ordercare.incident.scope.persistence;

import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshot;

public record IncidentScopeClaim(IncidentScopeSnapshot snapshot, boolean acquired) {
}
