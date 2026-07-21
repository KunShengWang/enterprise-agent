package com.agent.platform.ordercare.incident.scope.model;

public enum IncidentScopeSnapshotStatus {
    NEW,
    DISCOVERING,
    CANDIDATES_READY,
    WAITING_CONFIRMATION,
    CONFIRMED,
    FAILED,
    EXPIRED,
    CANCELLED
}
