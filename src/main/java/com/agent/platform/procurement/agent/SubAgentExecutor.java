package com.agent.platform.procurement.agent;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.agent.platform.procurement.tool.ProcurementToolCatalog.*;

/** Agent-as-tool execution over the existing runtime and stores. No private model loop. */
@Component
public final class SubAgentExecutor {
    private final AgentRuntime runtime;
    private final AgentRunStore runs;
    private final ToolExecutionStore executions;
    private final ProcurementCaseStore cases;
    private final AgentRegistry registry;
    private final ObjectMapper json;
    private final ProcurementExecutionSummaries summaries;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AgentCapabilityRegistry capabilities;
    public void setCapabilities(AgentCapabilityRegistry capabilities){this.capabilities=capabilities;}
    public SubAgentExecutor(@Lazy AgentRuntime runtime,AgentRunStore runs,ToolExecutionStore executions,
                            ProcurementCaseStore cases,AgentRegistry registry,ObjectMapper json){
        this.runtime=runtime;this.runs=runs;this.executions=executions;this.cases=cases;this.registry=registry;this.json=json;
        this.summaries=new ProcurementExecutionSummaries(runs,executions,cases,json);
    }
    public ToolCallResult execute(ToolCallRequest request,ToolExecutionContext context){
        var root=runs.find(context.runId()).orElseThrow();
        if(root.executionProfile()==null || !AgentRegistry.SUPERVISOR.equals(root.executionProfile().name())
                || root.request().metadata().containsKey(ChildRunLaunch.PARENT)
                || !root.conversationId().equals(context.sessionId()) || !root.userId().equals(context.userId())
                || !Objects.equals(root.request().metadata().get("tenantId"),context.tenantId()))
            return failure(request,"DOMAIN_DELEGATION_DENIED",false);
        if(!request.arguments().keySet().equals(Set.of("objective")) || !(request.arguments().get("objective") instanceof String objective)
                || objective.isBlank() || objective.length()>12_000)return failure(request,"INVALID_TASK_PACKET",false);
        String agent=registry.agentFor(request.toolName());
        String childId=UUID.nameUUIDFromBytes((root.runId()+":"+request.requestId()).getBytes(StandardCharsets.UTF_8)).toString();
        var stored=runs.find(childId).orElse(null);
        AgentRuntimeResult result;
        if(stored!=null){
            new AgentRunLinks(runs).parent(childId).filter(p->p.runId().equals(root.runId())).orElseThrow();
            if(!agent.equals(stored.executionProfile().name()))return failure(request,"CHILD_IDENTITY_MISMATCH",true);
            result=runtime.resume(childId,AgentEventListener.NOOP);
        }else{
            var prior=executions.findByRun(root.runId()).stream().filter(e->e.result()!=null && e.result().success()
                    && AgentRegistry.AGENT_TOOLS.contains(e.toolName())).toList();
            if(executions.findByRun(root.runId()).stream().filter(e->AgentRegistry.AGENT_TOOLS.contains(e.toolName())).count()>12)
                return failure(request,"CHILD_RUN_BUDGET_EXCEEDED",true);
            var current=cases.findByTenantUserAndConversationId(context.tenantId(),context.userId(),context.sessionId()).orElse(null);
            long version=current==null?-1:current.version();
            for(var e:prior){
                var old=json.readTree(e.result().content());
                if(request.toolName().equals(e.toolName()) && old.path("caseVersion").asLong(-2)==version
                        && objective.equals(old.path("objective").asText()) && !"CHILD_WAITING_APPROVAL".equals(old.path("status").asText())){
                    var metadata=new LinkedHashMap<>(e.result().metadata());metadata.put("reusedDomainResult",true);
                    var refreshed=(tools.jackson.databind.node.ObjectNode)old;
                    refreshed.set("rootExecutionSummary",json.valueToTree(summaries.root(root.runId())));
                    refreshed.set("canonicalCase",json.valueToTree(current==null?Map.of():current));
                    return new ToolCallResult(request.toolName(),true,json.writeValueAsString(refreshed),"",metadata);
                }
            }
            if(!agent.equals(AgentRegistry.REQUISITION) && (current==null || !current.state().missingFields().isEmpty()))
                return failure(request,"REQUIRES_REQUISITION_ANALYSIS",false);
            if(!agent.equals(AgentRegistry.REQUISITION) && prior.stream().filter(e->AgentRegistry.ANALYZE.equals(e.toolName()))
                    .map(e->json.readTree(e.result().content())).anyMatch(n->n.path("caseVersion").asLong(-2)==version
                            && !n.path("assessment").path("blockingReasons").isMissingNode()
                            && !n.path("assessment").path("blockingReasons").isEmpty()))
                return failure(request,"REQUISITION_BLOCKED",false);
            String finalizeRef="";
            if(agent.equals(AgentRegistry.RFQ)){
                var finalization=latestFinalization(root.runId());
                if(finalization==null)return failure(request,"REQUIRES_SOURCING",false);
                var value=json.readTree(finalization.result().content());
                if(!current.caseId().equals(value.path("caseId").asText()) || current.version()!=value.path("caseVersion").asLong(-1))
                    return failure(request,"STALE_RECOMMENDATION",false);
                finalizeRef=finalization.toolCallId();
            }
            var packet=new LinkedHashMap<String,Object>();
            packet.put("objective",objective);packet.put("userGoal",root.request().question());
            packet.put("caseId",current==null?"":current.caseId());packet.put("caseVersion",version);
            var businessRefs=new LinkedHashMap<String,String>();
            if(capabilities!=null)capabilities.findCapability("mcp.erpnext.procurement_get_budget_status").ifPresent(d->{
                String department=json.readTree(d.inputSchema()).path("x-businessRefs").path("departmentId").asText("");
                if(!department.isBlank())businessRefs.put("departmentId",department);
            });
            if(current!=null){
                businessRefs.put("caseId",current.caseId());
                if(current.state().productDescription()!=null && !current.state().productDescription().isBlank())
                    businessRefs.put("itemId",current.state().productDescription());
            }
            prior.stream().filter(e->AgentRegistry.ANALYZE.equals(e.toolName()))
                    .max(Comparator.comparing(ToolExecutionRecord::updatedAt)).ifPresent(e->{
                        String subject=json.readTree(e.result().content()).path("assessment").path("subjectRequisitionId").asText("");
                        if(!subject.isBlank())businessRefs.put("requisitionId",subject);
                    });
            if(!finalizeRef.isBlank()){
                var recommendation=json.readTree(executions.findToolExecution(finalizeRef).orElseThrow().result().content()).path("recommendation");
                String supplier=recommendation.path("recommendedSupplier").path("supplierId").asText("");
                if(!supplier.isBlank())businessRefs.put("supplierId",supplier);
            }
            packet.put("businessRefs",businessRefs);
            packet.put("referencePolicy",Map.of("costCenterId","UNSUPPORTED_BY_CANONICAL_MODEL",
                    "unboundItemRequisitionSupplier","LEGAL_DISCOVERY_REQUIRED","missingDepartment","MISSING_CANONICAL_REFERENCE"));
            packet.put("priorEvidenceRefs",finalizeRef.isBlank()?List.of():ChildEvidenceLedger.derive(
                    List.of(executions.findToolExecution(finalizeRef).orElseThrow()),json).stream().map(ChildEvidenceLedger.Entry::evidenceRef).toList());
            packet.put("constraints",current==null?Map.of():current.state());
            packet.put("priorResultRefs",prior.stream().map(ToolExecutionRecord::toolCallId).toList());
            prior.stream().filter(e->AgentRegistry.ANALYZE.equals(e.toolName()))
                    .max(Comparator.comparing(ToolExecutionRecord::updatedAt))
                    .ifPresent(e->packet.put("validatedRequisitionSummary",json.readTree(e.result().content())));
            if(!finalizeRef.isBlank())packet.put("finalizedRecommendation",recommendationSummary(executions.findToolExecution(finalizeRef).orElseThrow()));
            var metadata=new LinkedHashMap<String,Object>();metadata.put("tenantId",context.tenantId());metadata.put("authenticatedRoles",context.roles());
            String ref=finalizeRef;
            result=ChildRunLaunch.call(new ChildRunLaunch.Scope(childId,root.runId(),request.requestId(),ref),()->runtime.run(
                    new AgentRequest(root.conversationId()+":domain:"+childId,root.userId(),json.writeValueAsString(packet),metadata),
                    registry.get(agent).profile(),AgentEventListener.NOOP));
        }
        return result(request,context,agent,result);
    }
    public ProcurementExecutionSummaries.RootExecutionSummary rootExecutionSummary(String rootId){return summaries.root(rootId);}
    public ToolExecutionRecord latestFinalization(String rootId){
        // References are obtained from persisted successful AgentTool results, never model-supplied IDs.
        return executions.findByRun(rootId).stream().filter(e->AgentRegistry.SOURCE.equals(e.toolName()) && e.result()!=null && e.result().success())
                .map(e->json.readTree(e.result().content()).path("finalizationRef").asText())
                .filter(s->!s.isBlank()).map(id->executions.findToolExecution(id).orElse(null)).filter(Objects::nonNull)
                .filter(e->e.state()==ToolExecutionState.SUCCEEDED && RECOMMENDATION_FINALIZE.equals(e.toolName()) && e.result().success())
                .filter(e->new AgentRunLinks(runs).parent(e.runId()).map(p->p.runId().equals(rootId)).orElse(false))
                .max(Comparator.comparing(ToolExecutionRecord::updatedAt)).orElse(null);
    }
    private ToolCallResult result(ToolCallRequest request,ToolExecutionContext context,String agent,AgentRuntimeResult child){
        var current=cases.findByTenantUserAndConversationId(context.tenantId(),context.userId(),context.sessionId()).orElse(null);
        var content=new LinkedHashMap<String,Object>();
        content.put("agentId",agent);content.put("childRunId",child.runId());content.put("caseVersion",current==null?-1:current.version());
        content.put("objective",request.arguments().get("objective"));content.put("budget",child.budget());
        content.put("childExecutionSummary",summaries.child(child.runId(),current));
        content.put("rootExecutionSummary",summaries.root(context.runId()));
        content.put("canonicalCase",current==null?Map.of():current);
        if(child.state()==AgentRunState.WAITING_APPROVAL){
            content.put("status","CHILD_WAITING_APPROVAL");
            return new ToolCallResult(request.toolName(),false,json.writeValueAsString(content),"",
                    Map.of("_harnessOutcome","APPROVAL_REQUIRED","childRunId",child.runId(),"childApprovalId",child.approvalId(),"retryable",false));
        }
        if(child.state()!=AgentRunState.COMPLETED)return failure(request,
                Set.of(AgentStopReason.MAX_TURNS,AgentStopReason.TOOL_BUDGET_EXHAUSTED,AgentStopReason.MODEL_BUDGET_EXHAUSTED,AgentStopReason.TIMEOUT).contains(child.stopReason())
                ?"CHILD_RUN_BUDGET_EXCEEDED":"CHILD_RUN_FAILED",true);
        try{
            var answer=json.readTree(child.answer());
            RequisitionAssessment.Validation domainValidation=null;
            var ledger=evidenceLedger(child.runId());
            var allowed=ledger.stream().map(ChildEvidenceLedger.Entry::evidenceRef).collect(java.util.stream.Collectors.toSet());
            var businessRefs=json.readTree(runs.find(child.runId()).orElseThrow().request().question()).path("businessRefs");
            if(agent.equals(AgentRegistry.REQUISITION) && !businessRefs.path("departmentId").asText("").equals(answer.path("departmentId").asText("")))
                throw new IllegalArgumentException("output department contradicts canonical scope");
            if(agent.equals(AgentRegistry.REQUISITION)) {
                var base=answer.deepCopy();
                if(!base.isObject())throw new IllegalArgumentException("object required");
                ((tools.jackson.databind.node.ObjectNode)base).remove("blockerCandidates");
                ((tools.jackson.databind.node.ObjectNode)base).remove("deferredChecks");
                ((tools.jackson.databind.node.ObjectNode)base).remove("subjectRequisitionId");
                ((tools.jackson.databind.node.ObjectNode)base).remove("departmentId");
                validate(base,allowed);
                domainValidation=RequisitionAssessment.assess(answer,current,executions.findByRun(child.runId()),json);
                content.put("assessment",domainValidation.assessment());
            } else validate(answer,allowed);
            content.put("status",answer.path("status").asText());
            content.put("analysis",Map.of("authority","validated-analysis-not-committed-state","status",answer.path("status"),
                    "findings",answer.path("findings"),"warnings",answer.path("warnings"),"evidenceRefs",answer.path("evidenceRefs")));
            content.put("proposals",Map.of("authority","uncommitted-model-proposal","proposedCaseChanges",
                    answer.has("proposedCaseChanges")?answer.path("proposedCaseChanges"):List.of()));
            content.put("canonicalCase",current==null?Map.of():current);
            content.put("evidenceLedger",ledger.stream().map(e->Map.of("evidenceRef",e.evidenceRef(),"sourceToolCallId",e.sourceToolCallId(),
                    "toolName",e.toolName(),"outcome",e.outcome(),"entity",e.entity(),"version",e.version())).toList());
            if(domainValidation!=null){
                var assessment=domainValidation.assessment();
                content.put("status",assessment.get("status"));
                // Raw model findings may repeat a rejected blocker. Keep them in the Child trace only.
                content.put("analysis",Map.of("authority","runtime-validated-domain-result","status",assessment.get("status"),
                        "findings",List.of(json.writeValueAsString(assessment)),"warnings",assessment.get("validationWarnings"),
                        "evidenceRefs",answer.path("evidenceRefs")));
            }
            if(agent.equals(AgentRegistry.SOURCING)){
                var finalization=executions.findByRun(child.runId()).stream().filter(e->RECOMMENDATION_FINALIZE.equals(e.toolName())
                        && e.state()==ToolExecutionState.SUCCEEDED && e.result()!=null && e.result().success()).max(Comparator.comparing(ToolExecutionRecord::updatedAt)).orElse(null);
                if("COMPLETE".equals(answer.path("status").asText()) && finalization==null)throw new IllegalArgumentException("Finalize required");
                if("NO_ELIGIBLE_SUPPLIER".equals(answer.path("status").asText())) {
                    boolean verified=executions.findByRun(child.runId()).stream().filter(e->SUPPLIER_SEARCH.equals(e.toolName()) && e.result()!=null && e.result().success())
                            .map(e->json.readTree(e.result().content())).anyMatch(n->n.path("eligibleSuppliers").isArray() && n.path("eligibleSuppliers").isEmpty());
                    if(!verified || finalization!=null)throw new IllegalArgumentException("no-eligible assertion lacks Java search evidence");
                }
                if(finalization!=null){content.put("finalizationRef",finalization.toolCallId());content.put("recommendation",recommendationSummary(finalization));}
            }
            if(agent.equals(AgentRegistry.RFQ)){
                boolean written=executions.findByRun(child.runId()).stream().anyMatch(e->CREATE_RFQ.equals(e.toolName()) && e.state()==ToolExecutionState.SUCCEEDED && e.result()!=null && e.result().success());
                if("COMPLETE".equals(answer.path("status").asText())!=written)throw new IllegalArgumentException("RFQ completion disagrees with execution receipt");
                content.put("executionStatus",written?"CREATED":"NOT_CREATED");
            }
            var metadata=new LinkedHashMap<String,Object>();metadata.put("childRunId",child.runId());metadata.put("domainResult",true);
            // Raw persisted metadata/event trace only; projectForContext excludes this private audit.
            if(domainValidation!=null)metadata.put("_domainValidationAudit",domainValidation.audit());
            return new ToolCallResult(request.toolName(),true,json.writeValueAsString(content),"",metadata);
        }catch(RuntimeException invalid){return failure(request,"CHILD_OUTPUT_INVALID",true);}
    }
    public Map<String,Object> recommendationSummary(ToolExecutionRecord execution){
        var root=json.readTree(execution.result().content());var r=root.path("recommendation");var offer=r.path("selectedOffer");
        var value=new LinkedHashMap<String,Object>();
        value.put("caseId",root.path("caseId").asText());value.put("caseVersion",root.path("caseVersion").asLong());
        value.put("supplier",r.path("recommendedSupplier"));
        value.put("offer",Map.of("unitPrice",offer.path("unitPrice"),"totalPrice",offer.path("totalPrice"),"currency",offer.path("currency"),"leadTimeDays",offer.path("leadTimeDays")));
        var evidence=new LinkedHashSet<String>();collect(root,evidence);
        value.put("evidenceRefs",evidence);value.put("source",root.path("source").asText());return value;
    }
    public static void validate(JsonNode node,Set<String> evidence){
        if(node==null || !node.isObject())throw new IllegalArgumentException("object required");
        Set<String> fields=new HashSet<>();node.properties().forEach(e->fields.add(e.getKey()));
        fields.remove("proposedCaseChanges");
        if(!fields.equals(Set.of("status","findings","warnings","evidenceRefs")))throw new IllegalArgumentException("fields invalid");
        if(!Set.of("READY","COMPLETE","BLOCKED","NEEDS_CLARIFICATION","NO_ELIGIBLE_SUPPLIER","REJECTED").contains(node.path("status").asText()))throw new IllegalArgumentException("status invalid");
        if(node.has("proposedCaseChanges")){
            if(!node.path("proposedCaseChanges").isArray())throw new IllegalArgumentException("proposal array required");
            for(var proposal:node.path("proposedCaseChanges"))if(!proposal.isTextual() || proposal.asText().length()>2000)throw new IllegalArgumentException("invalid proposal");
        }
        for(String field:List.of("findings","warnings","evidenceRefs")){
            var array=node.path(field);if(!array.isArray() || array.size()>40)throw new IllegalArgumentException("array invalid");
            for(var item:array)if(!item.isTextual() || item.asText().length()>2000)throw new IllegalArgumentException("entry invalid");
        }
        for(var ref:node.path("evidenceRefs"))if(!evidence.contains(ref.asText()))throw new IllegalArgumentException("unknown evidence");
        if(Set.of("READY","COMPLETE").contains(node.path("status").asText()) && node.path("evidenceRefs").isEmpty())throw new IllegalArgumentException("evidence required");
    }
    public List<ChildEvidenceLedger.Entry> evidenceLedger(String childId){
        var ledger=new ArrayList<>(ChildEvidenceLedger.derive(executions.findByRun(childId),json));
        var run=runs.find(childId).orElseThrow();var ref=Objects.toString(run.request().metadata().get(ChildRunLaunch.FINALIZE),"");
        var packet=json.readTree(run.request().question());var grants=new HashSet<String>();
        for(var grant:packet.path("priorEvidenceRefs"))grants.add(grant.asText());
        if(!ref.isBlank())executions.findToolExecution(ref).filter(e->RECOMMENDATION_FINALIZE.equals(e.toolName())
                && new AgentRunLinks(runs).sameRoot(childId,e.runId())).ifPresent(e->
                ChildEvidenceLedger.derive(List.of(e),json).stream().filter(entry->grants.contains(entry.evidenceRef())).forEach(ledger::add));
        return List.copyOf(ledger);
    }
    public Set<String> evidenceRefs(String childId){
        return evidenceLedger(childId).stream().map(ChildEvidenceLedger.Entry::evidenceRef).collect(java.util.stream.Collectors.toSet());
    }
    private static void collect(JsonNode node,Set<String> refs){
        if(node==null)return;
        if(node.isObject()){
            if(node.path("evidenceId").isTextual())refs.add(node.path("evidenceId").asText());
            var source=node.path("source");
            if(source.isObject() && source.path("entityId").isTextual())refs.add(source.path("entityType").asText()+":"+source.path("entityId").asText()+":"+source.path("version").asText());
            node.properties().forEach(e->collect(e.getValue(),refs));
        }else if(node.isArray())for(var item:node)collect(item,refs);
    }
    public static ToolCallResult failure(ToolCallRequest request,String reason,boolean fatal){
        return new ToolCallResult(request.toolName(),false,"",reason,Map.of("errorType",reason,"_harnessOutcome",fatal?"FATAL_ERROR":"POLICY_DENIED","retryable",false));
    }
}
