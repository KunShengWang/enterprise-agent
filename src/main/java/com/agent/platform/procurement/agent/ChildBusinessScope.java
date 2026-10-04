package com.agent.platform.procurement.agent;

import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import java.util.*;

/** Checks server-written TaskPacket references before a child can reach a capability/ERP. */
@Component
public final class ChildBusinessScope {
    private final AgentRunStore runs;
    private final ObjectMapper json;
    public ChildBusinessScope(AgentRunStore runs,ObjectMapper json){this.runs=runs;this.json=json;}
    public ToolCallResult validate(String runId,ToolCallRequest request){
        if(new AgentRunLinks(runs).parent(runId).isEmpty())return null;
        var refs=json.readTree(runs.find(runId).orElseThrow().request().question()).path("businessRefs");
        return validateRefs(refs,request);
    }
    public static ToolCallResult validateRefs(JsonNode refs,ToolCallRequest request){
        String tool=request.toolName(),field="",argument="";
        if(tool.equals("mcp.erpnext.procurement_get_budget_status")){field="departmentId";argument=field;}
        if(tool.equals("mcp.erpnext.procurement_get_item")){field="itemId";argument="id";}
        if(tool.equals("mcp.erpnext.procurement_list_item_suppliers")){field="itemId";argument=field;}
        if(tool.equals("mcp.erpnext.procurement_get_requisition")){field="requisitionId";argument="id";}
        if(tool.equals("mcp.erpnext.procurement_get_supplier")){field="supplierId";argument="id";}
        if(field.isEmpty())return null;
        String canonical=refs.path(field).asText("");
        // Discovery remains model-owned when no item/requisition/supplier has been bound.
        if(canonical.isBlank() && !field.equals("departmentId"))return null;
        String error=canonical.isBlank()?"MISSING_CANONICAL_REFERENCE":
                canonical.equals(request.arguments().get(argument))?"":"CANONICAL_REFERENCE_MISMATCH";
        return error.isEmpty()?null:new ToolCallResult(tool,false,"",error,
                Map.of("errorType",error,"_harnessOutcome","VALIDATION_ERROR","retryable",false,"canonicalField",field));
    }
}
