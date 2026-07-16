package com.agent.platform.guardrail;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ToolPolicyContext 是“工具权限检查所需的身份和运行上下文”
 * 各字段作用：
    runId：当前 Agent 运行 ID，便于审计和追踪。
    sessionId：当前会话 ID。
    userId：发起请求的用户。
    tenantId：用户所属租户，用于租户级工具权限。
    roles：已经认证的角色，例如 admin。
    attributes：请求携带的其他可信身份元数据。
 */
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
