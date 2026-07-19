package com.agent.platform.ordercare.incident.recovery.model;

public record RecoveryPlanDecisionRequest(boolean approved, String reviewer, String reason) {
}
