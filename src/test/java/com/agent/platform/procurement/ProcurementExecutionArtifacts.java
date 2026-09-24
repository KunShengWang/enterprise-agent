package com.agent.platform.procurement;

import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.ToolExecutionRecord;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import static com.agent.platform.procurement.ProcurementEvaluation.*;

/** Captures already executed data only. No Runtime.run, Provider, tool or model calls. */
public final class ProcurementExecutionArtifacts {
    private ProcurementExecutionArtifacts() { }

    public static ExecutionArtifact capture(EvaluationCase definition, String actualUserMessage,
                                            AgentRuntimeResult result, ProcurementCase finalCase,
                                            List<ToolExecutionRecord> records, List<AgentEvent> events,
                                            Map<String, String> metadata) {
        Map<String, String> manifest = new LinkedHashMap<>();
        for (String key : List.of("executionMode", "codeRevision", "model", "promptSha256",
                "toolSchemaSha256", "modelConfigSha256", "fixtureSha256")) manifest.put(key, "UNKNOWN");
        if (metadata != null) manifest.putAll(metadata);
        // Bind the captured business identity separately so a later swapped Case is detected.
        if (finalCase != null) {
            manifest.put("tenantId", finalCase.tenantId());
            manifest.put("userId", finalCase.userId());
            manifest.put("businessCaseId", finalCase.caseId());
        }
        return new ExecutionArtifact(SCHEMA, definition.caseId(), definition.datasetSha256(),
                actualUserMessage, result, finalCase, records, events, manifest, List.of(
                "Events are only the supplied timeline; absent events do not prove no rejected request.",
                "Approval ID/events are observations, not an authoritative approval decision ledger.",
                "No external side-effect receipt or gateway counter is captured.",
                "Runtime budget is parent-only; descendant usage is not aggregated.",
                "Missing model/prompt/tool/config/fixture fingerprints are UNKNOWN, not verified reproducibility.",
                "Final answer and product text semantics are retained but not graded."));
    }

    static ExecutionArtifact fromLiveExecution(EvaluationCase definition, String conversationId,
                                               ProcurementLiveEvalRuntimeHarness.CaseExecution execution,
                                               Map<String, String> metadata) {
        return capture(definition, definition.userMessage(), execution.result(),
                execution.caseStore().findByTenantUserAndConversationId(
                        ProcurementLiveEvalRuntimeHarness.TENANT_ID,
                        ProcurementLiveEvalRuntimeHarness.USER_ID, conversationId).orElse(null),
                execution.toolExecutionStore().all(),
                execution.timelineStore().loadEvents(execution.result().runId(), 10_000), metadata);
    }
}
