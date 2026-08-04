package com.agent.platform.tool;

import java.util.Map;
import java.util.Set;

/**
 * Runtime 在工具执行边界注入的可信上下文。
 *
 * <p>工具参数来自模型，只能表达调用意图；身份、父 Run 和业务范围必须从该上下文读取，
 * 不能相信模型在 arguments 中提交的同名字段。</p>
 */
public record ToolExecutionContext(
        String runId,
        String sessionId,
        String userId,
        String tenantId,
        Set<String> roles,
        Map<String, Object> attributes
) {

    public ToolExecutionContext {
        runId = normalize(runId);
        sessionId = normalize(sessionId);
        userId = normalize(userId);
        tenantId = normalize(tenantId);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ToolExecutionContext empty() {
        return new ToolExecutionContext("", "", "", "", Set.of(), Map.of());
    }

    public String attribute(String name) {
        Object value = attributes.get(name);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public int intAttribute(String name, int defaultValue) {
        Object value = attributes.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            }
            catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
