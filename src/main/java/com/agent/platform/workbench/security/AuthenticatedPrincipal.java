package com.agent.platform.workbench.security;

import java.util.Set;

/**
 * Trusted identity passed by the application boundary. It is deliberately not built from a workbench request body.
 */
public record AuthenticatedPrincipal(
        String tenantId,
        String principalId,
        Set<String> roles
) {

    public AuthenticatedPrincipal {
        tenantId = requireText(tenantId, "tenantId");
        principalId = requireText(principalId, "principalId");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
