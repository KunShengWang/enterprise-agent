package com.agent.platform.runtime;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;

import java.time.Instant;

public record ToolExecutionRecord(
        String toolCallId,
        String runId,
        String toolName,
        ToolExecutionState state,
        ToolCallRequest request,
        ToolCallResult result,
        int attempt,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {

    public ToolExecutionRecord {
        state = state == null ? ToolExecutionState.RUNNING : state;
        errorMessage = errorMessage == null ? "" : errorMessage;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static ToolExecutionRecord running(String runId, ToolCallRequest request) {
        Instant now = Instant.now();
        return new ToolExecutionRecord(
                request.requestId(),
                runId,
                request.toolName(),
                ToolExecutionState.RUNNING,
                request,
                null,
                1,
                "",
                now,
                now
        );
    }

    public ToolExecutionRecord retrying() {
        return new ToolExecutionRecord(
                toolCallId,
                runId,
                toolName,
                ToolExecutionState.RUNNING,
                request,
                result,
                attempt + 1,
                "",
                createdAt,
                Instant.now()
        );
    }

    public ToolExecutionRecord withResult(ToolExecutionState targetState,
                                          ToolCallResult executionResult,
                                          String error) {
        return new ToolExecutionRecord(
                toolCallId,
                runId,
                toolName,
                targetState,
                request,
                executionResult,
                attempt,
                error,
                createdAt,
                Instant.now()
        );
    }
}
