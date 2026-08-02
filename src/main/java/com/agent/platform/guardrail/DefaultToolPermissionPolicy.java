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

    /**
     * 工具权限检查：
     * 1、检查调用的工具是否是租户禁用的工具
     * 2、检查调用的工具是否是管理员才能使用的工具
     * 3、检查的是工具调用参数是否越过系统允许的资源边界，目前主要检查两类高风险参数：文件路径 path、网络地址 url
     * 4、根据配置文件 yaml 的设置判断工具调用是阻塞还是允许还是人工审批
     */
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
        // 租户禁用的工具
        List<String> tenantDenied = properties.getTenantDeniedTools().getOrDefault(effectiveContext.tenantId(), List.of());
        if (tenantDenied.contains(toolName)) {
            return deny("tenant policy denies capability: " + toolName);
        }
        // 如果要调用的工具是管理员专属工具并且自己不是管理员的话会报错
        if (properties.getAdminOnlyTools().contains(toolName) && !effectiveContext.hasRole("admin")) {
            return deny("capability requires authenticated admin role: " + toolName);
        }
        // 检查的是工具调用参数是否越过系统允许的资源边界，目前主要检查两类高风险参数：文件路径 path、网络地址 url
        GuardrailDecision argumentDecision = checkArguments(toolDefinition, request);
        if (argumentDecision != null) {
            return argumentDecision;
        }

        String configured = properties.getDecisions().get(toolName);
        if (configured != null && !configured.isBlank()) {
            // 根据配置文件的设置判断是阻塞还是允许还是人工审批
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

    /**
     * 检查的是工具调用参数是否越过系统允许的资源边界，目前主要检查两类高风险参数：
     * 1、文件路径 path：文件工具的 path 必须位于允许目录中
     * 2、网络地址 url：所有 url 参数的主机必须位于网络白名单中
     */
    private GuardrailDecision checkArguments(ToolDefinition definition, ToolCallRequest request) {
        String name = definition.name().toLowerCase(Locale.ROOT);
        Map<String, Object> arguments = request.arguments();
        // 文件路径检查
        Object pathValue = arguments.get("path");
        if (pathValue != null && (name.contains("file") || name.contains("filesystem") || name.contains("directory"))) {
            try {
                // 路径规范化：把参数转换为 Path、将相对路径转换为绝对路径、消除 . 和 .. 等路径片段
                Path requested = Path.of(String.valueOf(pathValue)).toAbsolutePath().normalize();
                // 检查是否位于允许目录中
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
        // 网络 URL 检查
        Object urlValue = arguments.get("url");
        if (urlValue != null) {
            try {
                // 解析 URL，如：https://api.example.com/orders/1001
                URI uri = URI.create(String.valueOf(urlValue));
                // 解析后提取主机，得到：api.example.com
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                // 检查网络主机白名单
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

    /**
     * 根据配置文件的设置判断是阻塞还是允许还是人工审批
     */
    private GuardrailDecision configuredDecision(String value, String reason) {
        String normalized = value == null ? "deny" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "allow" -> GuardrailDecision.allow(GuardrailStage.TOOL, reason);
            case "ask", "approval", "require_approval" -> ask(reason);// 需要人工审批
            default -> deny(reason);
        };
    }

    /**
     * 需要人工审批
     */
    private GuardrailDecision ask(String reason) {
        return GuardrailDecision.requireApproval(GuardrailStage.TOOL, reason);
    }

    /**
     * 工具拒绝使用
     */
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
