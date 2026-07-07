package com.agent.platform.tool;

import java.util.Map;

public record ToolRunStats(
        long totalCalls,
        long successCalls,
        long failedCalls,
        double successRate,
        Map<String, Long> callsByTool
) {

    public ToolRunStats {
        callsByTool = callsByTool == null ? Map.of() : Map.copyOf(callsByTool);
    }
}
