package com.agent.platform.ordercare.incident.recovery.model;

import java.util.List;

public record RecoveryPlanValidationResult(boolean valid, List<String> errors) {
    public RecoveryPlanValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static RecoveryPlanValidationResult accepted() {
        return new RecoveryPlanValidationResult(true, List.of());
    }

    public static RecoveryPlanValidationResult rejected(List<String> errors) {
        return new RecoveryPlanValidationResult(false, errors);
    }
}
