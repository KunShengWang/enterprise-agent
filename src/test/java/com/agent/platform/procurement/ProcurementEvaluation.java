package com.agent.platform.procurement;

import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.ToolExecutionRecord;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Test-only, versioned wire contracts. PASS means evaluated structured checks only. */
public final class ProcurementEvaluation {
    public static final String SCHEMA = "procurement-evaluation-v2";
    private ProcurementEvaluation() { }

    public enum Status { PASS, FAIL, ERROR, SKIP, NOT_APPLICABLE }

    public record EvaluationCase(String schemaVersion, String datasetVersion, String datasetSha256, String fixtureSha256,
                                 String caseId, String userMessage, JsonNode providerFixture,
                                 JsonNode expectedCase, JsonNode expected) { }

    /** Raw events are observations, not inferred approvals or proof of external side effects.
     * Null toolExecutions means unavailable; an empty list means captured and empty.
     * Evidence provenance remains inside the original tool result JSON.
     */
    public record ExecutionArtifact(String schemaVersion, String caseId, String datasetSha256,
                                    String userMessage, AgentRuntimeResult runtime,
                                    ProcurementCase finalCase, List<ToolExecutionRecord> toolExecutions,
                                    List<AgentEvent> observedEvents, Map<String, String> metadata,
                                    List<String> captureLimitations) {
        public ExecutionArtifact {
            toolExecutions = toolExecutions == null ? null : List.copyOf(toolExecutions);
            observedEvents = observedEvents == null ? List.of() : List.copyOf(observedEvents);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            captureLimitations = captureLimitations == null ? List.of() : List.copyOf(captureLimitations);
        }
    }

    public record Check(String metric, Status status, String reason, List<String> evidencePaths) {
        public Check { evidencePaths = List.copyOf(evidencePaths); }
    }

    public record EvaluationResult(String caseId, String artifactSha256, String graderVersion,
                                   Status structuredStatus, List<Check> checks) {
        public EvaluationResult { checks = List.copyOf(checks); }
    }

    public record Report(String schemaVersion, String datasetVersion, String datasetSha256, String fixtureSha256,
                         String graderVersion, String scope, List<EvaluationResult> results,
                         Map<Status, Long> checkCounts) {
        public Report { results = List.copyOf(results); checkCounts = Map.copyOf(checkCounts); }
    }

    public record Comparison(String kind, List<String> changes) {
        public Comparison { changes = List.copyOf(changes); }
    }
}
