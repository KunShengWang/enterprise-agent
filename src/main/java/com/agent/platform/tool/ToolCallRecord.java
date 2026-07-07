package com.agent.platform.tool;

import java.time.Instant;
import java.util.Map;

public record ToolCallRecord(
        String requestId,
        String toolName,
        boolean success,
        long durationMs,
        String errorMessage,
        Map<String, Object> arguments,
        Map<String, Object> metadata,
        Instant occurredAt
) {

    public ToolCallRecord {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
