package com.agent.platform.guardrail;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record ToolPolicyContext(
        String runId,
        String sessionId,
        String userId,
        String tenantId,
        Set<String> roles,
        Map<String, Object> attributes
) {

    public ToolPolicyContext {
        runId = runId == null ? "" : runId;
        sessionId = sessionId == null ? "" : sessionId;
        userId = userId == null ? "anonymous" : userId;
        tenantId = tenantId == null ? "default" : tenantId;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ToolPolicyContext from(String runId,
                                         String sessionId,
                                         String userId,
                                         Map<String, Object> attributes) {
        Map<String, Object> safeAttributes = attributes == null ? Map.of() : attributes;
        String tenantId = String.valueOf(safeAttributes.getOrDefault("tenantId", "default"));
        Set<String> roles = new LinkedHashSet<>();
        Object rawRoles = safeAttributes.get("authenticatedRoles");
        if (rawRoles instanceof Collection<?> collection) {
            collection.stream()
                    .filter(value -> value != null && !String.valueOf(value).isBlank())
                    .map(value -> String.valueOf(value).toLowerCase(Locale.ROOT))
                    .forEach(roles::add);
        }
        return new ToolPolicyContext(runId, sessionId, userId, tenantId, roles, safeAttributes);
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role.toLowerCase(Locale.ROOT));
    }
}
