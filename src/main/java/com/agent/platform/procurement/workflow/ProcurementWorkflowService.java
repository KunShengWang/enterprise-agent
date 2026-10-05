package com.agent.platform.procurement.workflow;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import java.time.Instant;
import java.util.*;

/** Procurement application orchestration; no model-driven routing and no new workflow state store. */
@Service
public class ProcurementWorkflowService {
    private final AgentRuntime runtime;private final AgentRunStore runs;private final ToolExecutionStore executions;
    private final AgentToolRuntime tools;private final ProcurementCaseStore cases;private final ProcurementStageProfiles profiles;
    private final ProcurementRecommendationFinalizer finalizer;private final ObjectMapper json;
    @Value("${ERPNEXT_DEPARTMENT:V2-ALG}") private String department="V2-ALG";
    @Autowired(required=false) private AgentTimelineStore timeline;
    public void setTimeline(AgentTimelineStore timeline){this.timeline=timeline;}
    private final Object[] locks=new Object[64];
    public ProcurementWorkflowService(@Qualifier("defaultAgentRuntime") AgentRuntime runtime,AgentRunStore runs,ToolExecutionStore executions,
            AgentToolRuntime tools,ProcurementCaseStore cases,ProcurementStageProfiles profiles,ProcurementRecommendationFinalizer finalizer,ObjectMapper json){
        this.runtime=runtime;this.runs=runs;this.executions=executions;this.tools=tools;this.cases=cases;this.profiles=profiles;this.finalizer=finalizer;this.json=json;
        Arrays.setAll(locks,i->new Object());
    }
    public AgentRuntimeResult execute(AgentRequest input,AgentEventListener listener){
        requireIdentity(input);
        synchronized(locks[Math.floorMod(Objects.hash(input.conversationId(),input.userId(),input.metadata().get("tenantId")),locks.length)]){
            return advance(input,listener==null?AgentEventListener.NOOP:listener);
        }
    }
    public Map<String,Object> status(AgentRequest input){
        requireIdentity(input);var c=current(input);
        return Map.of("case",c==null?Map.of():c,"stage",ProcurementStage.from(c),"source","canonical-case-store");
    }
    private AgentRuntimeResult advance(AgentRequest input,AgentEventListener listener){
        String workflow=UUID.randomUUID().toString();var runIds=new ArrayList<String>();AgentRuntimeResult last=null;
        try{
            // A fresh input is assessed independently against fresh Case facts, never resumed model reasoning.
            last=stage(input,workflow,ProcurementStage.REQUISITION,Map.of(),"",listener);runIds.add(last.runId());
            if(last.state()!=AgentRunState.COMPLETED)return last;
            var requisitionResult=typed(last,RequisitionStageResult.class);
            var output=json.valueToTree(requisitionResult);
            if(requisitionResult.intent()==RequisitionStageResult.Intent.CHAT)return finish(last,AgentRunState.COMPLETED,"COMPLETE",Map.of("clarifications",output.path("clarifications")),runIds,listener);
            var evidence=new WorkflowEvidence(executions.findByRun(last.runId()),json);
            requireObservedVersion(last,input);
            var validated=WorkflowRequisitionValidator.validate(output,current(input),evidence,department,json);
            if(validated.patch().hasChanges()){
                long observed=((Number)runs.find(last.runId()).orElseThrow().request().metadata().get("workflowCaseVersion")).longValue();
                new ProcurementCaseService(cases,new ProcurementCasePatchMerger()).applyPatch(tenant(input),input.conversationId(),input.userId(),
                        validated.patch(),last.runId()+":validated-patch",Math.max(0,observed));
            }
            var c=current(input);
            if(!validated.blockers().isEmpty() || !validated.missing().isEmpty() || c==null || !c.state().missingFields().isEmpty()){
                if(c!=null && c.status()!=ProcurementCaseStatus.CLARIFICATION_REQUIRED)transition(c,ProcurementCaseStatus.CLARIFICATION_REQUIRED);
                return finish(last,AgentRunState.COMPLETED,"NEEDS_USER_INPUT",Map.of("requisition",validated.facts(),"missing",validated.missing(),"clarifications",output.path("clarifications")),runIds,listener);
            }
            if(ProcurementStage.from(c)!=ProcurementStage.SOURCING)throw new IllegalArgumentException("illegal Requisition transition");
            String requisitionRun=last.runId();
            WorkflowFinalizeBinding.bindRequisition(runs,requisitionRun,c,validated);
            WorkflowEvidenceCarry.validated(runs,requisitionRun,c,json.convertValue(output.path("evidenceRefs"),List.class),json);
            boolean wantsRfq=requisitionResult.intent()==RequisitionStageResult.Intent.RFQ;
            last=stage(input,workflow,ProcurementStage.SOURCING,validated.facts(),"",listener);runIds.add(last.runId());
            if(last.state()!=AgentRunState.COMPLETED)return last;
            var sourcingResult=typed(last,SourcingStageResult.class);
            output=json.valueToTree(sourcingResult);
            c=requireObservedVersion(last,input);
            evidence=new WorkflowEvidence(executions.findByRun(last.runId()),json);evidence.require(output.path("evidenceRefs"));
            if(sourcingResult.status()==SourcingStageResult.Status.NO_ELIGIBLE_SUPPLIER){
                if(evidence.facts.getOrDefault(ProcurementToolCatalog.SUPPLIER_SEARCH,List.of()).stream().noneMatch(n->n.path("eligibleSuppliers").isArray()&&n.path("eligibleSuppliers").isEmpty()))throw new IllegalArgumentException("no-eligible needs canonical Search");
                transition(c,ProcurementCaseStatus.NO_ELIGIBLE_SUPPLIER);
                return finish(last,AgentRunState.COMPLETED,"NO_ELIGIBLE_SUPPLIER",Map.of(),runIds,listener);
            }
            var proposal=json.convertValue(output.path("recommendation"),Map.class);
            validateSchema(definition(ProcurementToolCatalog.RECOMMENDATION_FINALIZE).inputSchema(),proposal);
            evidence.require(output.path("recommendation").path("evidenceRefs"));
            WorkflowEvidenceCarry.validated(runs,last.runId(),c,json.convertValue(output.path("recommendation").path("evidenceRefs"),List.class),json);
            var finalized=finalizer.assess(tenant(input),input.userId(),input.conversationId(),
                    json.convertValue(proposal,ProcurementRecommendationDraft.class),Set.copyOf(evidence.refs));
            // Java domain decision is not a model tool execution or a finalized recommendation receipt.
            var decision=new LinkedHashMap<String,Object>();
            decision.put("status",finalized.status());decision.put("reason",finalized.reason());
            decision.put("caseId",finalized.procurementCase().caseId());decision.put("evaluatedCaseVersion",finalized.procurementCase().version());
            decision.put("selectedSupplierId",proposal.get("selectedSupplierId"));decision.put("proposal",proposal);
            decision.put("source","canonical-finalizer");decision.put("executionOutcome","SUCCESS");
            WorkflowEvidenceCarry.put(runs,last.runId(),"workflowFinalization",decision);
            if(finalized.status()==ProcurementRecommendationFinalizer.Status.REJECTED){
                boolean empty=finalized.reason()==ProcurementRecommendationFinalizer.Reason.NO_ELIGIBLE_SUPPLIER;
                transition(finalized.procurementCase(),empty?ProcurementCaseStatus.NO_ELIGIBLE_SUPPLIER:ProcurementCaseStatus.CLARIFICATION_REQUIRED);
                return finish(last,AgentRunState.COMPLETED,empty?"NO_ELIGIBLE_SUPPLIER":"RECOMMENDATION_REJECTED",
                        Map.of("finalization",decision,"explanation",empty?
                                "当前没有满足约束的合格供应商，因此采购流程已停止在供应商选择阶段。":
                                "当前提案的供应商不满足采购约束，推荐已被拒绝；需要新的采购输入。"),runIds,listener);
            }
            String finalizeRef=last.runId()+":workflow-finalize";
            var request=new ToolCallRequest(ProcurementToolCatalog.RECOMMENDATION_FINALIZE,finalizeRef,proposal);
            var claim=executions.claim(last.runId(),request);if(!claim.claimed())throw new IllegalStateException("Finalize already in progress");
            try{
                c=transition(finalized.procurementCase(),ProcurementCaseStatus.RECOMMENDATION_READY);
                var snapshot=new LinkedHashMap<String,Object>(Map.of("caseId",c.caseId(),"caseVersion",c.version(),
                        "recommendation",finalized.recommendation(),"evidence",finalized.evidence(),"source","verified-provider-snapshot"));
                // Recommendation carries a checked identity snapshot; the Requisition state remains authoritative.
                var identity=json.valueToTree(runs.find(requisitionRun).orElseThrow().request().metadata().get(WorkflowFinalizeBinding.REQUISITION));
                snapshot.put("workflowRequisitionRunId",requisitionRun);
                snapshot.put("canonicalRequisitionId",identity.path("canonicalRequisitionId").asText());
                snapshot.put("requisitionBacked",identity.path("requisitionBacked").asBoolean());
                snapshot.put("recommendationEvidenceManifest",RecommendationEvidenceManifest.create(finalizeRef,json.valueToTree(snapshot),Instant.now()));
                executions.markSucceeded(finalizeRef,new ToolCallResult(request.toolName(),true,json.writeValueAsString(snapshot),"",Map.of("owner","workflow")));
            }catch(RuntimeException ex){executions.markFailed(finalizeRef,new ToolCallResult(request.toolName(),false,"",ex.getMessage(),Map.of()));throw ex;}
            var canonical=json.readTree(executions.findToolExecution(finalizeRef).orElseThrow().result().content());
            if(!wantsRfq)return finish(last,AgentRunState.COMPLETED,"COMPLETE",Map.of("recommendation",canonical,"tradeoffsAdvisory",output.path("tradeoffs")),runIds,listener);
            if(ProcurementStage.from(c)!=ProcurementStage.RFQ)throw new IllegalArgumentException("RFQ requires finalized recommendation");
            String reservation="workflow-rfq:"+c.caseId()+":"+c.version();
            var reserved=executions.claim(last.runId(),new ToolCallRequest("procurement_workflow_rfq_reservation",reservation,Map.of("caseId",c.caseId(),"caseVersion",c.version())));
            if(!reserved.claimed())throw new IllegalStateException("RFQ proposal for this Case version already reserved; resume its existing approval");
            var bundle=WorkflowEvidenceCarry.issue(runs,executions,requisitionRun,executions.findToolExecution(finalizeRef).orElseThrow(),json);
            var manifest=canonical.path("recommendationEvidenceManifest");
            var rfqContext=Map.<String,Object>of("finalizedRecommendation",Map.of("recommendationRef",finalizeRef,
                    "caseVersion",c.version(),"selectedSupplier",canonical.path("recommendation").path("recommendedSupplier")),
                    "recommendationEvidenceManifest",manifest,"requisition",validated.facts(),
                    "carriedEvidence",Map.of("scope","WORKFLOW_CARRIED","refs",bundle.entries().stream().map(WorkflowEvidenceBundle.Entry::evidenceRef).toList()));
            last=stage(input,workflow,ProcurementStage.RFQ,rfqContext,finalizeRef,listener);runIds.add(last.runId());
            if(last.state()!=AgentRunState.COMPLETED)return last;
            requireObservedVersion(last,input);
            var rfqResult=typed(last,RfqStageResult.class);output=json.valueToTree(rfqResult);
            var binding=new WorkflowFinalizeBinding(runs,executions,cases,json);
            var bound=binding.find(last.runId()).orElseThrow();
            if(!rfqResult.recommendationRef().equals(bound.toolCallId()))throw new IllegalArgumentException("RFQ recommendationRef not authorized for this workflow");
            var accepted=new WorkflowEvidence(executions.findByRun(last.runId()),json).require(output.path("evidenceRefs"),bundle);
            WorkflowEvidenceCarry.put(runs,last.runId(),"workflowAcceptedEvidence",Map.of("recommendationRef",bound.toolCallId(),
                    "manifestRefs",manifest.path("validatedEvidenceRefs"),"explicitRefs",accepted));
            if(timeline!=null)listener.onEvent(timeline.appendEvent(input.conversationId(),input.userId(),last.runId(),
                    new AgentEventDraft(AgentEventType.WORKFLOW_EVIDENCE_CARRIED,"Server authorized upstream evidence",json.convertValue(bundle,Map.class))));
            if(!rfqResult.requestRfq())return finish(last,AgentRunState.COMPLETED,"NEEDS_USER_INPUT",Map.of(),runIds,listener);
            var execution=operation(last,input,ProcurementToolCatalog.CREATE_RFQ,Map.of());
            if(execution.status()!=AgentToolExecutionStatus.WAITING_APPROVAL)throw new IllegalStateException("RFQ must pause for HITL");
            var paused=new AgentRunBudget(profiles.get(ProcurementStage.RFQ).runBudget(),last.budget());paused.pauseExecution();
            String id=last.runId();runs.update(id,r->r.waitingForApproval(execution.approvalId(),execution.request(),r.toolResults(),r.usedTools(),r.usedRag(),paused.snapshot()));
            executions.markSucceeded(reservation,new ToolCallResult("procurement_workflow_rfq_reservation",true,json.writeValueAsString(Map.of("runId",id,"approvalId",execution.approvalId())),"",Map.of()));
            if(timeline!=null){
                var event=timeline.appendEvent(input.conversationId(),input.userId(),id,new AgentEventDraft(AgentEventType.APPROVAL_REQUIRED,"Java workflow awaits approval",Map.of("approvalId",execution.approvalId(),"stage","RFQ","workflowId",workflow,"outcome","APPROVAL_REQUIRED","runtimeState","WAITING_APPROVAL")));
                listener.onEvent(event);
            }
            return new AgentRuntimeResult(id,input.conversationId(),AgentRunState.WAITING_APPROVAL,AgentStopReason.WAITING_APPROVAL,
                    json.writeValueAsString(Map.of("outcome","WAITING_APPROVAL","case",current(input),"stageRunRefs",runIds)),execution.approvalId(),paused.snapshot(),last.events());
        }catch(RuntimeException ex){
            if(last==null)throw ex;
            return finish(last,AgentRunState.FAILED,"WORKFLOW_ERROR",Map.of("error",Objects.toString(ex.getMessage(),ex.getClass().getSimpleName())),runIds,listener);
        }
    }
    private AgentRuntimeResult stage(AgentRequest input,String workflow,ProcurementStage stage,Map<String,Object> carried,String finalizeRef,AgentEventListener listener){
        var metadata=new LinkedHashMap<String,Object>();metadata.put("tenantId",tenant(input));metadata.put("authenticatedRoles",input.metadata().getOrDefault("authenticatedRoles",Set.of()));
        metadata.put("workflowId",workflow);metadata.put("procurementStage",stage.name());metadata.put("agentId","PROCUREMENT");
        metadata.put("workflowFinalizeRef",finalizeRef);
        if(!finalizeRef.isBlank()){
            var source=runs.find(executions.findToolExecution(finalizeRef).orElseThrow().runId()).orElseThrow();
            metadata.put(WorkflowEvidenceCarry.BUNDLE,source.request().metadata().get(WorkflowEvidenceCarry.GRANT));
        }
        var observed=current(input);metadata.put("workflowCaseVersion",observed==null?-1L:observed.version());
        metadata.put("workflowCaseId",observed==null?"":observed.caseId());
        var packet=new LinkedHashMap<String,Object>();packet.put("userInput",input.question());packet.put("canonicalCase",Optional.ofNullable(observed).map(c->(Object)c).orElse(Map.of()));
        packet.put("validatedPriorStage",carried);packet.put("departmentId",department);packet.put("stage",stage);
        return runtime.run(new AgentRequest(input.conversationId(),input.userId(),json.writeValueAsString(packet),metadata),profiles.get(stage).executionProfile(),listener);
    }
    private <T> T typed(AgentRuntimeResult result,Class<T> type){
        T value=StructuredOutputSpec.stored(runs.find(result.runId()).orElseThrow(),type);
        // Compatibility audit projection of an already validated typed contract, never raw assistant content.
        runs.update(result.runId(),r->{var metadata=new LinkedHashMap<>(r.request().metadata());
            metadata.put("validatedStageOutput",json.convertValue(value,Map.class));
            var request=r.request();return r.withRequest(new AgentRequest(request.conversationId(),request.userId(),request.question(),metadata));});
        var unavailable=new WorkflowEvidence(executions.findByRun(result.runId()),json).unavailableReads();
        if(!unavailable.isEmpty()){
            runs.update(result.runId(),r->{var metadata=new LinkedHashMap<>(r.request().metadata());
                metadata.put("workflowDataUnavailable",unavailable);var request=r.request();
                return r.withRequest(new AgentRequest(request.conversationId(),request.userId(),request.question(),metadata));});
            throw new IllegalStateException("DATA_UNAVAILABLE_DUE_TO_TOOL_FAILURE: "+json.writeValueAsString(unavailable));
        }
        return value;
    }
    private void validateSchema(String schema,Map<String,Object> args){
        StageOutputValidator.validate(json.readTree(schema),json.valueToTree(args));
    }
    private AgentToolRuntimeResult operation(AgentRuntimeResult result,AgentRequest input,String name,Map<String,Object> args){
        var action=tools.execute(result.runId(),input.conversationId(),input.userId(),runs.find(result.runId()).orElseThrow().request().metadata(),new AgentToolCall(result.runId()+":workflow:"+name,name,args,"Java workflow operation"),definition(name));
        if(action.status()!=AgentToolExecutionStatus.WAITING_APPROVAL && (action.result()==null || !action.result().success()))throw new IllegalArgumentException("workflow operation rejected: "+name);
        return action;
    }
    private ProcurementCase transition(ProcurementCase current,ProcurementCaseStatus status){
        if(current==null)throw new IllegalArgumentException("Case required");
        var next=new ProcurementCase(current.caseId(),current.tenantId(),current.conversationId(),current.userId(),status,current.state(),current.createdAt(),Instant.now(),current.version()+1,current.lastAppliedInputId(),current.appliedInputIds());
        if(!cases.saveIfVersion(next,current.version()))throw new ProcurementCaseVersionConflictException("workflow transition stale");return next;
    }
    public AgentRuntimeResult resume(String runId,AgentEventListener listener){
        var stored=runs.find(runId).orElseThrow();if(!"PROCUREMENT".equals(stored.executionProfile().name()))throw new IllegalArgumentException("not workflow stage");
        var result=runtime.resume(runId,listener);
        if(!"RFQ".equals(stored.request().metadata().get("procurementStage")))return result;
        if(result.state()==AgentRunState.WAITING_APPROVAL)return result;
        var receipt=executions.findByRun(runId).stream().filter(e->e.toolName().equals(ProcurementToolCatalog.CREATE_RFQ) && e.state()==ToolExecutionState.SUCCEEDED && e.result()!=null && e.result().success()).findFirst();
        if(receipt.isPresent())return finish(result,AgentRunState.COMPLETED,"COMPLETE",Map.of("rfqReceipt",json.readTree(receipt.get().result().content())),List.of(runId),listener);
        return finish(result,AgentRunState.FAILED,"RFQ_NOT_EXECUTED",Map.of(),List.of(runId),listener);
    }
    private AgentRuntimeResult finish(AgentRuntimeResult result,AgentRunState state,String outcome,Map<String,Object> facts,List<String> runIds,AgentEventListener listener){
        var stored=runs.find(result.runId()).orElseThrow();var input=stored.request();
        var body=new LinkedHashMap<String,Object>();body.put("outcome",outcome);body.put("facts",facts);body.put("case",current(input));body.put("stageRunRefs",runIds);
        String answer=json.writeValueAsString(body);
        runs.update(result.runId(),r->r.finished(state,state==AgentRunState.FAILED?AgentRunPhase.FAILED:state==AgentRunState.NEEDS_CLARIFICATION?AgentRunPhase.WAITING_INPUT:AgentRunPhase.FINISHED,answer,state==AgentRunState.FAILED?AgentStopReason.TOOL_ERROR.name():"",r.toolResults(),r.usedTools(),r.usedRag(),r.blockedByGuardrail(),r.budgetSnapshot()));
        if(timeline!=null){
            var event=timeline.appendEvent(input.conversationId(),input.userId(),result.runId(),new AgentEventDraft(
                    state==AgentRunState.FAILED?AgentEventType.RUN_FAILED:AgentEventType.RUN_COMPLETED,answer,
                    Map.of("workflowOutcome",outcome,"state",state.name(),"stageRunRefs",runIds,"runtimeState",runs.find(result.runId()).orElseThrow().runtimeState())));
            if(listener!=null)listener.onEvent(event);
        }
        return new AgentRuntimeResult(result.runId(),result.sessionId(),state,state==AgentRunState.FAILED?AgentStopReason.TOOL_ERROR:state==AgentRunState.NEEDS_CLARIFICATION?AgentStopReason.WAITING_INPUT:AgentStopReason.COMPLETED,answer,result.approvalId(),result.budget(),timeline==null?result.events():timeline.loadEvents(result.runId(),1000));
    }
    private ProcurementCase requireObservedVersion(AgentRuntimeResult result,AgentRequest input){
        var metadata=runs.find(result.runId()).orElseThrow().request().metadata();
        var current=current(input);long actual=current==null?-1L:current.version();
        if(((Number)metadata.get("workflowCaseVersion")).longValue()!=actual
                || !Objects.equals(metadata.get("workflowCaseId"),current==null?"":current.caseId()))
            throw new ProcurementCaseVersionConflictException("Case changed during stage; proposal rejected");
        return current;
    }
    private ProcurementCase current(AgentRequest request){return cases.findByTenantUserAndConversationId(tenant(request),request.userId(),request.conversationId()).orElse(null);}
    private String tenant(AgentRequest request){return Objects.toString(request.metadata().get("tenantId"),"");}
    private void requireIdentity(AgentRequest request){if(request==null || request.conversationId()==null || request.conversationId().isBlank() || request.userId()==null || request.userId().isBlank() || tenant(request).isBlank())throw new IllegalArgumentException("workflow identity required");}
    private ToolDefinition definition(String name){return new ProcurementToolCatalog().definitions().stream().filter(t->t.name().equals(name)).findFirst().orElseThrow();}
}
