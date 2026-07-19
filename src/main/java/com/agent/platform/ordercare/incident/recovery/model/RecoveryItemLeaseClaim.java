package com.agent.platform.ordercare.incident.recovery.model;

public record RecoveryItemLeaseClaim(
        IncidentRecoveryPlanRecord plan,
        IncidentRecoveryPlanItem item,
        boolean claimed,
        boolean takeover
) { }
