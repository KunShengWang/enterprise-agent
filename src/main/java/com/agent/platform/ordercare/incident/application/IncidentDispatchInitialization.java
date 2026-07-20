package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.IncidentRecord;

public record IncidentDispatchInitialization(IncidentRecord incident, boolean newlyCreated) {
}
