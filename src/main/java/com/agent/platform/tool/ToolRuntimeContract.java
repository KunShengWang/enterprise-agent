package com.agent.platform.tool;

import java.util.Map;

/** Typed view of existing catalog metadata, not a second catalog or model schema. */
public record ToolRuntimeContract(Source source, Effect effect, long timeoutMillis,
                                  String outputContract, Object retryMetadata,
                                  Object approvalMetadata, Object allowedAgents, Object allowedStages) {
    public enum Source { LOCAL, MCP }
    public enum Effect { READ_ONLY, INTERNAL_WRITE, EXTERNAL_SIDE_EFFECT, DESTRUCTIVE }

    public static ToolRuntimeContract from(ToolDefinition definition) {
        Map<String, Object> m = definition.metadata();
        Source source = "mcp".equals(m.get("provider")) || definition.name().startsWith("mcp.")
                ? Source.MCP : Source.LOCAL;
        Effect effect = Boolean.TRUE.equals(m.get("readOnly")) ? Effect.READ_ONLY
                : Boolean.TRUE.equals(m.get("sideEffect")) || source == Source.MCP
                || definition.riskLevel() == ToolRiskLevel.HIGH ? Effect.EXTERNAL_SIDE_EFFECT : Effect.INTERNAL_WRITE;
        if (m.get("effect") != null) effect = Effect.valueOf(m.get("effect").toString());
        long timeout = m.get("timeoutMillis") instanceof Number n ? Math.max(1, n.longValue()) : 60_000;
        return new ToolRuntimeContract(source, effect, timeout,
                String.valueOf(m.getOrDefault("outputContract", m.getOrDefault("contractVersion", "ToolCallResult/v1"))),
                m.getOrDefault("retry", "existing-runtime-policy"),
                m.getOrDefault("approval", "existing-guardrail-policy"),
                m.getOrDefault("allowedAgents", "execution-profile"),
                m.getOrDefault("allowedStages", "existing-visibility-policy"));
    }
}
