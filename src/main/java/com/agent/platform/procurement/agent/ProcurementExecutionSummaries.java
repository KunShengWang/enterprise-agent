package com.agent.platform.procurement.agent;

import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.runtime.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded projections over durable receipts. No last-child boolean or second execution store. */
@Component
public final class ProcurementExecutionSummaries implements AgentResultMetadataProvider {
    private final AgentRunStore runs;
    private final ToolExecutionStore executions;
    private final ProcurementCaseStore cases;
    private final ObjectMapper json;
    public ProcurementExecutionSummaries(AgentRunStore runs,ToolExecutionStore executions,ProcurementCaseStore cases,ObjectMapper json){
        this.runs=runs;this.executions=executions;this.cases=cases;this.json=json;
    }
    public record ChildExecutionSummary(String scope,String childRunId,String agentId,boolean caseMutated,
            int caseMutationCount,long resultingCaseVersion,boolean rfqCreated,int rfqCreationCount,
            List<String> receiptRefs,int omittedReceiptRefs) {}
    public record RootExecutionSummary(String scope,String rootRunId,String summaryRef,boolean caseMutated,
            int caseMutationCount,long currentCaseVersion,boolean rfqCreated,int rfqCreationCount,
            List<String> receiptRefs,int omittedReceiptRefs) {}
    private record Receipts(List<Map<String,Object>> patches,List<Map<String,Object>> rfqs){
        List<String> refs(){var refs=new ArrayList<String>();for(var p:patches)refs.add("tool-execution:"+p.get("sourceToolCallId"));
            for(var r:rfqs)refs.add("tool-execution:"+r.get("sourceToolCallId"));return refs.stream().sorted().toList();}
    }
    @SuppressWarnings("unchecked")
    private Receipts receipts(List<ToolExecutionRecord> records,ProcurementCase current){
        var unique=new LinkedHashMap<String,ToolExecutionRecord>();records.forEach(e->unique.putIfAbsent(e.toolCallId(),e));
        var facts=ChildEvidenceLedger.executionFacts(List.copyOf(unique.values()),current,json);
        return new Receipts((List<Map<String,Object>>)facts.get("casePatchReceipts"),(List<Map<String,Object>>)facts.get("rfqReceipts"));
    }
    public ChildExecutionSummary child(String childId,ProcurementCase current){
        var child=runs.find(childId).orElseThrow();new AgentRunLinks(runs).parent(childId).orElseThrow();
        var receipts=receipts(executions.findByRun(childId),current);var refs=receipts.refs();
        long resulting=receipts.patches().stream().mapToLong(p->((Number)p.get("caseVersion")).longValue()).max().orElse(-1);
        return new ChildExecutionSummary("CHILD_LOCAL",childId,child.executionProfile().name(),!receipts.patches().isEmpty(),
                receipts.patches().size(),resulting,!receipts.rfqs().isEmpty(),receipts.rfqs().size(),refs.stream().limit(8).toList(),Math.max(0,refs.size()-8));
    }
    public ProcurementCase current(String rootId){
        var root=runs.find(rootId).orElseThrow();
        return cases.findByTenantUserAndConversationId(Objects.toString(root.request().metadata().get("tenantId"),""),root.userId(),root.conversationId()).orElse(null);
    }
    public RootExecutionSummary root(String rootId){return root(rootId,current(rootId));}
    private RootExecutionSummary root(String rootId,ProcurementCase current){
        var root=runs.find(rootId).orElseThrow();
        if(root.executionProfile()==null || !AgentRegistry.SUPERVISOR.equals(root.executionProfile().name())
                || new AgentRunLinks(runs).parent(rootId).isPresent())throw new IllegalArgumentException("Supervisor root required");
        var records=new ArrayList<>(executions.findByRun(rootId));
        // Include committed receipts even if the enclosing Child later failed or is waiting for approval.
        for(var call:executions.findByRun(rootId))if(AgentRegistry.AGENT_TOOLS.contains(call.toolName())){
            String id=UUID.nameUUIDFromBytes((rootId+":"+call.toolCallId()).getBytes(StandardCharsets.UTF_8)).toString();
            var child=runs.find(id).orElse(null);
            if(child!=null && new AgentRunLinks(runs).parent(id).map(p->p.runId().equals(rootId)).orElse(false)
                    && call.toolCallId().equals(child.request().metadata().get(ChildRunLaunch.CALL)))
                records.addAll(executions.findByRun(id));
        }
        var receipts=receipts(records,current);var refs=receipts.refs();
        return new RootExecutionSummary("ROOT_GLOBAL",rootId,"root-execution-summary:"+rootId,!receipts.patches().isEmpty(),
                receipts.patches().size(),current==null?-1:current.version(),!receipts.rfqs().isEmpty(),receipts.rfqs().size(),
                refs.stream().limit(8).toList(),Math.max(0,refs.size()-8));
    }
    @Override public Map<String,Object> provide(String runId){
        var run=runs.find(runId).orElse(null);
        if(run==null || run.executionProfile()==null || !AgentRegistry.SUPERVISOR.equals(run.executionProfile().name()))return Map.of();
        var current=current(runId);var summary=root(runId,current);
        return Map.of("rootExecutionSummary",summary,"businessResult",current==null?Map.of("caseStatus","UNCREATED"):
                Map.of("caseId",current.caseId(),"caseVersion",current.version(),"caseStatus",current.status().name()));
    }
}
