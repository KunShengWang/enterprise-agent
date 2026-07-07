package com.agent.platform.guardrail;

import java.time.Instant;
import java.util.Map;

public record GuardrailAuditRecord(
        String auditId,
        GuardrailStage stage,
        GuardrailAction action,
        String subject,
        String reason,
        String safeContent,
        Instant createdAt,
        Map<String, Object> metadata
) {

    public GuardrailAuditRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
