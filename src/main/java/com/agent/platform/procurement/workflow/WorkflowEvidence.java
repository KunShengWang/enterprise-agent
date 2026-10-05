package com.agent.platform.procurement.workflow;

import com.agent.platform.runtime.*;
import tools.jackson.databind.*;
import java.util.*;

/** Per-stage receipt evidence. Failed reads cannot become positive evidence. */
public final class WorkflowEvidence {
    private final List<ToolExecutionRecord> records;
    public final Set<String> refs=new HashSet<>();
    public final Map<String,List<JsonNode>> facts=new LinkedHashMap<>();
    public WorkflowEvidence(List<ToolExecutionRecord> records,ObjectMapper json) {
        this.records=List.copyOf(records);
        for(var r:records)if(r.state()==ToolExecutionState.SUCCEEDED && r.result()!=null && r.result().success()){
            refs.add("tool-execution:"+r.toolCallId());
            var n=json.readTree(r.result().content());facts.computeIfAbsent(r.toolName(),k->new ArrayList<>()).add(n);collect(n);
        }
    }
    private void collect(JsonNode n){
        if(n.isObject()){
            var source=n.path("source");
            if(source.path("entityType").isTextual() && source.path("entityId").isTextual() && source.path("version").isTextual())
                refs.add(source.path("entityType").asText()+":"+source.path("entityId").asText()+":"+source.path("version").asText());
            if(n.path("evidenceId").isTextual())refs.add(n.path("evidenceId").asText());
            n.properties().forEach(e->collect(e.getValue()));
        }else if(n.isArray())n.forEach(this::collect);
    }
    public void require(JsonNode array){
        if(!array.isArray())throw new IllegalArgumentException("evidenceRefs must be array");
        for(var ref:array)if(!ref.isTextual() || !refs.contains(ref.asText()))throw new IllegalArgumentException("evidence outside successful stage ledger");
    }
    /** A missing fact after an unrecovered infrastructure failure is not a user clarification. */
    public List<Map<String,Object>> unavailableReads() {
        var failures=new ArrayList<Map<String,Object>>();
        for(var failed:records){
            if(failed.result()==null || failed.result().success())continue;
            var outcome=failed.result().outcome();
            if(outcome!=com.agent.platform.tool.ToolExecutionOutcome.RETRYABLE_ERROR
                    && outcome!=com.agent.platform.tool.ToolExecutionOutcome.FATAL_ERROR)continue;
            boolean recovered=records.stream().anyMatch(r->r.state()==ToolExecutionState.SUCCEEDED
                    && r.result()!=null && r.result().success() && r.toolName().equals(failed.toolName())
                    && r.request().arguments().equals(failed.request().arguments())
                    && !r.updatedAt().isBefore(failed.updatedAt()));
            if(!recovered)failures.add(Map.of("toolCallId",failed.toolCallId(),"toolName",failed.toolName(),
                    "outcome",outcome.name(),"attempts",failed.result().metadata().getOrDefault("attempts",1)));
        }
        return List.copyOf(failures);
    }
    /** Caller must first reauthorize the server-owned bundle against current stores/version. */
    public Map<String,WorkflowEvidenceBundle.Scope> require(JsonNode array,WorkflowEvidenceBundle authorized){
        if(!array.isArray())throw new IllegalArgumentException("evidenceRefs must be array");
        var carried=new HashSet<String>();authorized.entries().forEach(e->carried.add(e.evidenceRef()));
        var accepted=new LinkedHashMap<String,WorkflowEvidenceBundle.Scope>();
        for(var ref:array){
            if(!ref.isTextual())throw new IllegalArgumentException("evidence ref must be text");
            if(refs.contains(ref.asText()))accepted.put(ref.asText(),WorkflowEvidenceBundle.Scope.CURRENT_STAGE);
            else if(carried.contains(ref.asText()))accepted.put(ref.asText(),WorkflowEvidenceBundle.Scope.WORKFLOW_CARRIED);
            else throw new IllegalArgumentException("evidence outside authorized workflow carry/current stage ledger");
        }
        return Map.copyOf(accepted);
    }
    public boolean completeRequisitionScan(ObjectMapper json){
        int offset=0;var visited=new HashSet<Integer>();
        while(visited.add(offset)){
            final int at=offset;
            var page=records.stream().filter(r->r.toolName().equals("mcp.erpnext.procurement_list_requisitions")
                    && r.state()==ToolExecutionState.SUCCEEDED && r.result()!=null && r.result().success()
                    && ((Number)r.request().arguments().getOrDefault("offset",0)).intValue()==at).findFirst();
            if(page.isEmpty())return false;
            var data=json.readTree(page.get().result().content());
            if(!data.path("records").isArray() || !data.has("nextOffset"))return false;
            if(data.path("nextOffset").isNull())return true;
            if(!data.path("nextOffset").isIntegralNumber())return false;
            offset=data.path("nextOffset").asInt();
        }
        return false;
    }
    public List<JsonNode> records(String shortTool){
        var result=new ArrayList<JsonNode>();
        for(var n:facts.getOrDefault("mcp.erpnext.procurement_"+shortTool,List.of())){
            if(n.path("record").isObject())result.add(n.path("record"));
            if(n.path("records").isArray())n.path("records").forEach(result::add);
        }return result;
    }
}
