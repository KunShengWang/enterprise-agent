package com.agent.platform.tool;

import java.util.Map;

public record ToolCallPlan(
        boolean shouldCallTool,
        String toolName,
        Map<String, Object> arguments,
        String reason,
        double confidence,
        String planner
) {

    public ToolCallPlan {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        reason = reason == null ? "" : reason;
        planner = planner == null ? "unknown" : planner;
    }

    public static ToolCallPlan noTool(String reason, String planner) {
        return new ToolCallPlan(false, "", Map.of(), reason, 0.0, planner);
    }
}
