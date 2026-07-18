package com.agent.platform.ordercare.incident.application;

import java.util.List;

public class IncidentAssessmentValidationException extends IllegalArgumentException {

    private final List<String> validationErrors;

    public IncidentAssessmentValidationException(List<String> validationErrors) {
        super("reviewer assessment references are invalid: " + validationErrors);
        this.validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }

    public List<String> validationErrors() {
        return validationErrors;
    }
}
