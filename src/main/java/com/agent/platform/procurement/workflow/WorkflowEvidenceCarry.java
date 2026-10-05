package com.agent.platform.procurement.workflow;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.procurement.model.*;
import com.agent.platform.runtime.*;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Carry grants are issued only by the Java transition after domain/evidence validation. */
public final class WorkflowEvidenceCarry {
    public static final String VALIDATED="workflowEvidenceValidation", GRANT="workflowEvidenceGrant", BUNDLE="workflowEvidenceBundle";
    private WorkflowEvidenceCarry() {}

    static void validated(AgentRunStore runs, String runId, ProcurementCase current, Collection<String> refs, ObjectMapper json) {
        var source=runs.find(runId).orElseThrow();
        var grant=Map.of("caseId",current.caseId(),"caseVersion",current.version(),"refs",List.copyOf(refs),
                "typedOutputSha256",hash(json.writeValueAsString(source.request().metadata().get("validatedStageOutput"))));
        put(runs,runId,VALIDATED,grant);
    }

    static WorkflowEvidenceBundle issue(AgentRunStore runs, ToolExecutionStore tools, String requisitionRun,
            ToolExecutionRecord finalized, ObjectMapper json) {
        var canonical=json.readTree(finalized.result().content());
        var manifest=RecommendationEvidenceManifest.readAndVerify(finalized.toolCallId(),canonical,json);
        var source=runs.find(finalized.runId()).orElseThrow();
        var entries=new ArrayList<WorkflowEvidenceBundle.Entry>();
        var req=runs.find(requisitionRun).orElseThrow();
        var validation=json.valueToTree(req.request().metadata().get(VALIDATED));
        if(!manifest.caseId().equals(validation.path("caseId").asText()))throw invalid();
        for(var ref:validation.path("refs")) {
            var receipt=tools.findByRun(requisitionRun).stream().filter(r->successful(r)
                    && new WorkflowEvidence(List.of(r),json).refs.contains(ref.asText())).findFirst().orElseThrow(WorkflowEvidenceCarry::invalid);
            entries.add(entry(ref.asText(),req,receipt,"validated-requisition",json));
        }
        for(String ref:manifest.validatedEvidenceRefs())entries.add(entry(ref,source,finalized,"recommendation-manifest",json));
        var bundle=new WorkflowEvidenceBundle(Objects.toString(source.request().metadata().get("workflowId")),
                manifest.caseId(),manifest.caseVersion(),manifest.recommendationId(),manifest.recommendationVersion(),"RFQ",entries);
        // Independent source-side authorization prevents a target/model map from granting itself refs.
        put(runs,source.runId(),GRANT,json.convertValue(bundle,Map.class));
        return bundle;
    }

    private static WorkflowEvidenceBundle.Entry entry(String ref, AgentRunRecord run, ToolExecutionRecord receipt, String authority, ObjectMapper json) {
        if(run.state()!=AgentRunState.COMPLETED || !successful(receipt) || !run.request().metadata().containsKey(VALIDATED))throw invalid();
        return new WorkflowEvidenceBundle.Entry(ref,WorkflowEvidenceBundle.Scope.WORKFLOW_CARRIED,run.runId(),
                Objects.toString(run.request().metadata().get("procurementStage")),receipt.toolCallId(),receipt.toolName(),"SUCCEEDED","PASSED",
                authority,hash(receipt.result().content()),hash(json.writeValueAsString(run.request().metadata().get(VALIDATED))));
    }

    public static WorkflowEvidenceBundle authorize(AgentRunRecord target, ToolExecutionRecord finalized,
            ProcurementCase current, AgentRunStore runs, ToolExecutionStore tools, ObjectMapper json) {
        var source=runs.find(finalized.runId()).orElseThrow();
        var bundle=json.convertValue(target.request().metadata().get(BUNDLE),WorkflowEvidenceBundle.class);
        var grant=json.convertValue(source.request().metadata().get(GRANT),WorkflowEvidenceBundle.class);
        if(bundle==null || !bundle.equals(grant) || !"RFQ".equals(bundle.targetStage())
                || !bundle.workflowId().equals(target.request().metadata().get("workflowId"))
                || current==null || !bundle.caseId().equals(current.caseId()) || bundle.caseVersion()!=current.version()
                || current.status()!=ProcurementCaseStatus.RECOMMENDATION_READY
                || !bundle.caseId().equals(target.request().metadata().get("workflowCaseId"))
                || ((Number)target.request().metadata().get("workflowCaseVersion")).longValue()!=bundle.caseVersion()
                || !bundle.recommendationId().equals(finalized.toolCallId()))throw invalid();
        var manifest=RecommendationEvidenceManifest.readAndVerify(finalized.toolCallId(),json.readTree(finalized.result().content()),json);
        if(!manifest.caseId().equals(bundle.caseId()) || manifest.caseVersion()!=bundle.caseVersion()
                || manifest.recommendationVersion()!=bundle.recommendationVersion())throw invalid();
        var manifestRefs=new HashSet<String>();
        for(var e:bundle.entries()) {
            var origin=runs.find(e.originRunId()).orElseThrow(WorkflowEvidenceCarry::invalid);
            var receipt=tools.findToolExecution(e.originToolCallId()).orElseThrow(WorkflowEvidenceCarry::invalid);
            var validation=json.valueToTree(origin.request().metadata().get(VALIDATED));
            if(origin.state()!=AgentRunState.COMPLETED || !sameIdentity(origin,target)
                    || !e.originStage().equals(origin.request().metadata().get("procurementStage"))
                    || !Set.of("REQUISITION","SOURCING").contains(e.originStage())
                    || !receipt.runId().equals(origin.runId()) || !receipt.toolName().equals(e.originTool()) || !successful(receipt)
                    || !e.receiptSha256().equals(hash(receipt.result().content()))
                    || !e.validationSha256().equals(hash(json.writeValueAsString(origin.request().metadata().get(VALIDATED))))
                    || !validation.path("caseId").asText().equals(bundle.caseId())
                    || validation.path("caseVersion").asLong(-1)<0 || validation.path("caseVersion").asLong()>=bundle.caseVersion()
                    || !validation.path("typedOutputSha256").asText().equals(hash(json.writeValueAsString(origin.request().metadata().get("validatedStageOutput"))))
                    || e.scope()!=WorkflowEvidenceBundle.Scope.WORKFLOW_CARRIED || !"PASSED".equals(e.validationStatus())
                    || !"SUCCEEDED".equals(e.originOutcome())
                    || !new WorkflowEvidence(List.of(receipt),json).refs.contains(e.evidenceRef()))throw invalid();
            if("recommendation-manifest".equals(e.authorizationSource())) {
                if(!"SOURCING".equals(e.originStage()) || !receipt.toolCallId().equals(finalized.toolCallId())
                        || !manifest.validatedEvidenceRefs().contains(e.evidenceRef()))throw invalid();
                manifestRefs.add(e.evidenceRef());
            }else if(!"validated-requisition".equals(e.authorizationSource()) || !"REQUISITION".equals(e.originStage())
                    || !json.convertValue(validation.path("refs"),List.class).contains(e.evidenceRef()))throw invalid();
        }
        if(!manifestRefs.equals(new HashSet<>(manifest.validatedEvidenceRefs())))throw invalid();
        return bundle;
    }

    static boolean sameIdentity(AgentRunRecord a,AgentRunRecord b) {
        return a.executionProfile()!=null && "PROCUREMENT".equals(a.executionProfile().name())
                && a.conversationId().equals(b.conversationId()) && a.userId().equals(b.userId())
                && Objects.equals(a.request().metadata().get("tenantId"),b.request().metadata().get("tenantId"))
                && Objects.equals(a.request().metadata().get("workflowId"),b.request().metadata().get("workflowId"));
    }
    static boolean successful(ToolExecutionRecord r){return r.state()==ToolExecutionState.SUCCEEDED && r.result()!=null && r.result().success();}
    static void put(AgentRunStore runs,String id,String key,Object value){runs.update(id,r->{var m=new LinkedHashMap<>(r.request().metadata());m.put(key,value);return r.withRequest(new AgentRequest(r.conversationId(),r.userId(),r.request().question(),m));});}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("workflow evidence carry authorization stale, revoked or invalid");}
}
