package com.agent.platform.runtime;

import com.agent.platform.tool.ToolCallResult;

public record ToolExecutionClaim(
        boolean claimed,
        ToolExecutionState state,
        ToolCallResult cachedResult,
        String reason
) {

    public static ToolExecutionClaim acquired() {
        return new ToolExecutionClaim(true, ToolExecutionState.RUNNING, null, "toolCallId claimed");
    }

    public static ToolExecutionClaim existing(ToolExecutionRecord record, String reason) {
        return new ToolExecutionClaim(false, record.state(), record.result(), reason);
    }

    public static ToolExecutionClaim crossRunConflict(String reason) {
        return new ToolExecutionClaim(false, ToolExecutionState.MANUAL_REVIEW, null, reason);
    }
}
