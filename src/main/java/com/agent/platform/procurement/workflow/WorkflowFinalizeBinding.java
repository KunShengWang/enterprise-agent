package com.agent.platform.procurement.workflow;

import com.agent.platform.runtime.*;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import org.springframework.stereotype.Component;
import java.util.*;

/** Cross-stage reference accepted only from server-created PROCUREMENT run records. */
@Component
public final class WorkflowFinalizeBinding {
    public static final String REQUISITION = "workflowCanonicalRequisition";
    private final AgentRunStore runs;private final ToolExecutionStore tools;
    private final com.agent.platform.procurement.persistence.ProcurementCaseStore cases;
    private final tools.jackson.databind.ObjectMapper json;
    public WorkflowFinalizeBinding(AgentRunStore runs,ToolExecutionStore tools,com.agent.platform.procurement.persistence.ProcurementCaseStore cases,tools.jackson.databind.ObjectMapper json){this.runs=runs;this.tools=tools;this.cases=cases;this.json=json;}
    public Optional<ToolExecutionRecord> find(String runId){
        var target=runs.find(runId).orElse(null);
        if(target==null || target.executionProfile()==null || !"PROCUREMENT".equals(target.executionProfile().name())
                || !"RFQ".equals(target.request().metadata().get("procurementStage")))return Optional.empty();
        String ref=Objects.toString(target.request().metadata().get("workflowFinalizeRef"),"");
        var receipt=tools.findToolExecution(ref).orElseThrow(()->new IllegalArgumentException("missing workflow Finalize"));
        var source=runs.find(receipt.runId()).orElseThrow();
        if(!"PROCUREMENT".equals(source.executionProfile().name()) || !"SOURCING".equals(source.request().metadata().get("procurementStage"))
                || !source.conversationId().equals(target.conversationId()) || !source.userId().equals(target.userId())
                || !Objects.equals(source.request().metadata().get("tenantId"),target.request().metadata().get("tenantId"))
                || !Objects.equals(source.request().metadata().get("workflowId"),target.request().metadata().get("workflowId"))
                || receipt.state()!=ToolExecutionState.SUCCEEDED || receipt.result()==null || !receipt.result().success()
                || !ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(receipt.toolName()))throw new IllegalArgumentException("invalid workflow Finalize binding");
        var current=cases.findByTenantUserAndConversationId(Objects.toString(target.request().metadata().get("tenantId")),target.userId(),target.conversationId()).orElseThrow();
        WorkflowEvidenceCarry.authorize(target,receipt,current,runs,tools,json);
        return Optional.of(receipt);
    }
    public boolean permits(String runId,String ref){return find(runId).map(r->r.toolCallId().equals(ref)).orElse(false);}

    /** Application-owned identity, written once after Requisition domain validation, never from RFQ output. */
    static void bindRequisition(AgentRunStore runs, String runId,
            com.agent.platform.procurement.model.ProcurementCase current, WorkflowRequisitionValidator.Result validated) {
        String id = Objects.toString(validated.facts().get("subjectRequisitionId"), "");
        var identity = Map.of("caseId", current.caseId(), "caseVersion", current.version(),
                "requisitionBacked", !id.isBlank(), "canonicalRequisitionId", id,
                "itemId", current.state().productDescription(), "quantity", current.state().quantity());
        runs.update(runId, run -> {
            var metadata = new LinkedHashMap<>(run.request().metadata());
            if (metadata.containsKey(REQUISITION)) throw new IllegalArgumentException("requisition identity already bound");
            metadata.put(REQUISITION, identity);
            var request = run.request();
            return run.withRequest(new com.agent.platform.agent.AgentRequest(request.conversationId(), request.userId(), request.question(), metadata));
        });
    }

    /** Null is permitted only for an explicitly validated, non-requisition-backed workflow. */
    public String requisitionId(String runId) {
        var finalized = find(runId).orElseThrow(() -> new IllegalArgumentException("workflow Finalize required"));
        var snapshot = json.readTree(finalized.result().content());
        var source = runs.find(finalized.runId()).orElseThrow();
        var req = runs.find(snapshot.path("workflowRequisitionRunId").asText()).orElseThrow(() -> invalidIdentity());
        var identity = json.valueToTree(req.request().metadata().get(REQUISITION));
        var current = cases.findByTenantUserAndConversationId(Objects.toString(source.request().metadata().get("tenantId")), source.userId(), source.conversationId()).orElseThrow();
        String id = identity.path("canonicalRequisitionId").asText();
        if (req.state()!=AgentRunState.COMPLETED || !"REQUISITION".equals(req.request().metadata().get("procurementStage"))
                || !"PROCUREMENT".equals(req.executionProfile().name())
                || !Objects.equals(req.userId(), source.userId()) || !Objects.equals(req.conversationId(), source.conversationId())
                || !Objects.equals(req.request().metadata().get("tenantId"), source.request().metadata().get("tenantId"))
                || !Objects.equals(req.request().metadata().get("workflowId"), source.request().metadata().get("workflowId"))
                || !identity.path("caseId").asText().equals(current.caseId())
                || identity.path("caseVersion").asLong(-1) != ((Number) source.request().metadata().get("workflowCaseVersion")).longValue()
                || !snapshot.path("caseId").asText().equals(current.caseId()) || snapshot.path("caseVersion").asLong(-1)!=current.version()
                || !identity.path("itemId").asText().equals(current.state().productDescription())
                || identity.path("quantity").asInt(-1)!=current.state().quantity()
                || !identity.path("requisitionBacked").isBoolean() || !identity.path("canonicalRequisitionId").isTextual()
                || identity.path("requisitionBacked").asBoolean() != !id.isBlank()
                || !identity.path("canonicalRequisitionId").equals(snapshot.path("canonicalRequisitionId"))
                || !identity.path("requisitionBacked").equals(snapshot.path("requisitionBacked"))) throw invalidIdentity();
        return id.isBlank() ? null : id;
    }

    private static IllegalArgumentException invalidIdentity() {
        return new IllegalArgumentException("RFQ_REQUISITION_LINK_CONSISTENCY: missing, stale or mismatched canonical requisition binding");
    }
}
