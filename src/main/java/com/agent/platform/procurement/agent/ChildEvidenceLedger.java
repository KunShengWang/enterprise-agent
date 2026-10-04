package com.agent.platform.procurement.agent;

import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.ToolExecutionOutcome;
import tools.jackson.databind.*;
import java.util.*;
import static com.agent.platform.procurement.tool.ProcurementToolCatalog.*;

/** Derived view of existing receipts; no second evidence or business store. */
public final class ChildEvidenceLedger {
    public record Entry(String evidenceRef,String sourceToolCallId,String toolName,String outcome,
                        String entity,String version,JsonNode canonicalFacts) {}
    private ChildEvidenceLedger() {}
    public static boolean successful(ToolExecutionRecord e){
        return e.state()==ToolExecutionState.SUCCEEDED && e.result()!=null && e.result().success()
                && e.result().outcome()==ToolExecutionOutcome.SUCCESS;
    }
    public static List<Entry> derive(List<ToolExecutionRecord> records,ObjectMapper json){
        var ledger=new ArrayList<Entry>();
        for(var e:records){
            if(!successful(e))continue;
            try{
                var data=json.readTree(e.result().content());
                if(!valid(e.toolName(),data))continue;
                var refs=new LinkedHashSet<String>();refs.add("tool-execution:"+e.toolCallId());collect(data,refs);
                var source=data.path("record").path("source");
                String entity=source.path("entityId").asText(data.path("caseId").asText(""));
                String version=source.path("version").asText(data.path("caseVersion").asText(""));
                for(String ref:refs)ledger.add(new Entry(ref,e.toolCallId(),e.toolName(),"SUCCESS",entity,version,data));
            }catch(RuntimeException invalid){/* malformed success is trace data, not positive evidence */}
        }
        return List.copyOf(ledger);
    }
    private static boolean valid(String tool,JsonNode data){
        if(data==null || !data.isObject())return false;
        if(tool.startsWith("mcp.erpnext.procurement_")){
            if(data.has("record"))return canonicalRecord(data.path("record"));
            if(!data.path("records").isArray())return false;
            for(var record:data.path("records"))if(!canonicalRecord(record))return false;
            return true;
        }
        return switch(tool){
            case CASE_PATCH -> data.path("caseId").isTextual() && data.path("caseVersion").isIntegralNumber() && data.path("state").isObject();
            case SUPPLIER_SEARCH -> data.path("offers").isArray() && data.path("evidence").isArray();
            case RECOMMENDATION_FINALIZE -> data.path("caseId").isTextual() && data.path("recommendation").isObject() && data.path("evidence").isArray();
            case CREATE_RFQ -> data.path("rfqId").isTextual() && !data.path("rfqId").asText().isBlank() && data.path("approvalBound").asBoolean();
            default -> false;
        };
    }
    private static boolean canonicalRecord(JsonNode n){
        var s=n.path("source");
        return n.isObject() && s.isObject() && text(s,"entityType") && text(s,"entityId") && text(s,"version");
    }
    private static boolean text(JsonNode n,String key){return n.path(key).isTextual() && !n.path(key).asText().isBlank();}
    private static void collect(JsonNode node,Set<String> refs){
        if(node.isObject()){
            if(text(node,"evidenceId"))refs.add(node.path("evidenceId").asText());
            var s=node.path("source");
            if(s.isObject() && text(s,"entityType") && text(s,"entityId") && text(s,"version"))
                refs.add(s.path("entityType").asText()+":"+s.path("entityId").asText()+":"+s.path("version").asText());
            node.properties().forEach(e->collect(e.getValue(),refs));
        }else if(node.isArray())for(var n:node)collect(n,refs);
    }
    public static Map<String,Object> executionFacts(List<ToolExecutionRecord> records,ProcurementCase current,ObjectMapper json){
        var patches=new ArrayList<Map<String,Object>>();var rfqs=new ArrayList<Map<String,Object>>();
        for(var e:records){
            if(!successful(e))continue;
            var n=json.readTree(e.result().content());
            if(CASE_PATCH.equals(e.toolName()) && valid(e.toolName(),n) && current!=null
                    && current.caseId().equals(n.path("caseId").asText()) && current.version()>=n.path("caseVersion").asLong()
                    && current.appliedInputIds().contains(e.toolCallId()))
                patches.add(Map.of("sourceToolCallId",e.toolCallId(),"caseId",current.caseId(),"caseVersion",n.path("caseVersion").asLong()));
            if(CREATE_RFQ.equals(e.toolName()) && valid(e.toolName(),n) && current!=null
                    && current.caseId().equals(n.path("caseId").asText()))
                rfqs.add(Map.of("sourceToolCallId",e.toolCallId(),"rfqId",n.path("rfqId").asText(),"approvalBound",true));
        }
        return Map.of("authority","runtime-receipts-and-canonical-store","casePatched",!patches.isEmpty(),
                "casePatchReceipts",patches,"rfqCreated",!rfqs.isEmpty(),"rfqReceipts",rfqs);
    }
}
