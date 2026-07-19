package com.agent.platform.workbench.model;

public record ValidatedIdentifier(
        String type,
        String value,
        IdentifierSource source
) {
    public ValidatedIdentifier {
        if (type == null || type.isBlank() || value == null || value.isBlank() || source == null) {
            throw new IllegalArgumentException("type, value and source are required");
        }
        type = type.trim();
        value = value.trim();
    }
}

