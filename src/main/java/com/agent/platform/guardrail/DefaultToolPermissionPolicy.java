package com.agent.platform.guardrail;

import com.agent.platform.config.ToolPolicyProperties;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运行时强制执行的 allow/ask/deny 策略，包含风险、租户、角色和参数边界。
 */
@Component
public class DefaultToolPermissionPolicy implements ToolPermissionPolicy {

    private final ToolPolicyProperties properties;

    public DefaultToolPermissionPolicy() {
        this(new ToolPolicyProperties());
    }

    @Autowired
    public DefaultToolPermissionPolicy(ToolPolicyProperties properties) {
        this.properties = properties;
    }

    @Override
    public GuardrailDecision check(ToolDefinition toolDefinition, ToolCallRequest request) {
        return check(toolDefinition, request, new ToolPolicyContext("", "", "anonymous", "default", java.util.Set.of(), Map.of()));
    }

    @Override
    public GuardrailDecision check(ToolDefinition toolDefinition,
                                   ToolCallRequest request,
                                   ToolPolicyContext context) {
        if (toolDefinition == null || request == null) {
            return deny("unknown capability cannot be executed");
        }
        ToolPolicyContext effectiveContext = context == null
                ? new ToolPolicyContext("", "", "anonymous", "default", java.util.Set.of(), Map.of())
                : context;
        String toolName = toolDefinition.name();

        List<String> tenantDenied = properties.getTenantDeniedTools().getOrDefault(effectiveContext.tenantId(), List.of());
        if (tenantDenied.contains(toolName)) {
            return deny("tenant policy denies capability: " + toolName);
        }
        if (properties.getAdminOnlyTools().contains(toolName) && !effectiveContext.hasRole("admin")) {
            return deny("capability requires authenticated admin role: " + toolName);
        }

        GuardrailDecision argumentDecision = checkArguments(toolDefinition, request);
        if (argumentDecision != null) {
            return argumentDecision;
        }

        String configured = properties.getDecisions().get(toolName);
        if (configured != null && !configured.isBlank()) {
            return configuredDecision(configured, "configured policy for " + toolName);
        }
        if (toolDefinition.riskLevel() == ToolRiskLevel.CRITICAL) {
            return deny("critical risk capability is denied: " + toolName);
        }
        if (toolDefinition.riskLevel() == ToolRiskLevel.HIGH) {
            return ask("high risk capability requires approval: " + toolName);
        }
        if (isFilesystemWrite(toolDefinition, request)) {
            return ask("filesystem mutation requires approval: " + toolName);
        }
        return configuredDecision(properties.getDefaultDecision(), "default capability policy: " + toolName);
    }

    private GuardrailDecision checkArguments(ToolDefinition definition, ToolCallRequest request) {
        String name = definition.name().toLowerCase(Locale.ROOT);
        Map<String, Object> arguments = request.arguments();
        Object pathValue = arguments.get("path");
        if (pathValue != null && (name.contains("file") || name.contains("filesystem") || name.contains("directory"))) {
            try {
                Path requested = Path.of(String.valueOf(pathValue)).toAbsolutePath().normalize();
                boolean withinAllowedRoot = properties.getAllowedFilesystemRoots().stream()
                        .map(root -> Path.of(root).toAbsolutePath().normalize())
                        .anyMatch(requested::startsWith);
                if (!withinAllowedRoot) {
                    return deny("filesystem path is outside configured roots");
                }
            }
            catch (InvalidPathException exception) {
                return deny("filesystem path is invalid");
            }
        }
        Object urlValue = arguments.get("url");
        if (urlValue != null) {
            try {
                URI uri = URI.create(String.valueOf(urlValue));
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                if (host.isBlank() || !properties.getAllowedNetworkHosts().contains(host)) {
                    return deny("network host is not allowlisted: " + host);
                }
            }
            catch (IllegalArgumentException exception) {
                return deny("network URL is invalid");
            }
        }
        return null;
    }

    private GuardrailDecision configuredDecision(String value, String reason) {
        String normalized = value == null ? "deny" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "allow" -> GuardrailDecision.allow(GuardrailStage.TOOL, reason);
            case "ask", "approval", "require_approval" -> ask(reason);
            default -> deny(reason);
        };
    }

    private GuardrailDecision ask(String reason) {
        return GuardrailDecision.requireApproval(GuardrailStage.TOOL, reason);
    }

    private GuardrailDecision deny(String reason) {
        return GuardrailDecision.block(GuardrailStage.TOOL, reason);
    }

    private boolean isFilesystemWrite(ToolDefinition definition, ToolCallRequest request) {
        String name = definition.name().toLowerCase(Locale.ROOT);
        if (!(name.contains("filesystem") || name.contains("file") || name.contains("directory"))) {
            return false;
        }
        if (name.contains("write") || name.contains("delete") || name.contains("move") || name.contains("create")) {
            return true;
        }
        return request.arguments().keySet().stream().anyMatch(key -> {
            String normalized = key.toLowerCase(Locale.ROOT);
            return normalized.contains("write") || normalized.contains("delete") || normalized.contains("content");
        });
    }
}
