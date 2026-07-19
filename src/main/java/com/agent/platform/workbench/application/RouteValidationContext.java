package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.IdentifierSource;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

import java.util.Map;

public record RouteValidationContext(
        AuthenticatedPrincipal principal,
        AgentWorkItem workItem,
        String originalGoal,
        Map<String, String> trustedIdentifiers,
        Map<String, String> serverResolvedIdentifiers
) {
    public RouteValidationContext {
        if (principal == null || workItem == null) {
            throw new IllegalArgumentException("principal and workItem are required");
        }
        originalGoal = originalGoal == null ? "" : originalGoal;
        trustedIdentifiers = trustedIdentifiers == null ? Map.of() : Map.copyOf(trustedIdentifiers);
        serverResolvedIdentifiers = serverResolvedIdentifiers == null
                ? Map.of() : Map.copyOf(serverResolvedIdentifiers);
    }

    public IdentifierSource sourceOf(String type, String value) {
        if (value != null && !value.isBlank() && originalGoal.contains(value)) {
            return IdentifierSource.EXPLICIT_USER_INPUT;
        }
        if (value != null && value.equals(trustedIdentifiers.get(type))) {
            return IdentifierSource.TRUSTED_CONVERSATION_CONTEXT;
        }
        if (value != null && value.equals(serverResolvedIdentifiers.get(type))) {
            return IdentifierSource.SERVER_RESOLVED_FROM_BATCH;
        }
        return IdentifierSource.MODEL_INFERRED;
    }
}

