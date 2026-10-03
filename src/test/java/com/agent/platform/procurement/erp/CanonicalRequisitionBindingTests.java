package com.agent.platform.procurement.erp;

import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalRequisitionBindingTests {
    final ObjectMapper json=new ObjectMapper();
    final ProcurementCaseState state=new ProcurementCaseState("AI Workstation","SKU",20,new BigDecimal("300000"),"CNY",21,Map.of(),Map.of(),Set.of(),List.of(),"SOURCING");
    ToolExecutionRecord read(String id,String item,int quantity,String requestedId) {
        var source=new ProcurementErpAdapter.Source("ERPNext","Material Request",id,Instant.now(),Instant.now());
        var req=new ProcurementErpAdapter.Requisition(id,"V2-ALG",item,quantity,LocalDate.now().plusDays(21),"Draft",source);
        var call=new ToolCallRequest("mcp.erpnext.procurement_get_requisition","read-"+id,Map.of("id",requestedId));
        return ToolExecutionRecord.running("run",call).withResult(ToolExecutionState.SUCCEEDED,
                new ToolCallResult(call.toolName(),true,json.writeValueAsString(Map.of("record",req)),"",Map.of()),"");
    }
    @Test void bindsOnlyMatchingCanonicalFactsAndDeduplicatesReads() {
        var matching=read("MR-A","SKU",20,"MR-A");
        assertEquals("MR-A",CanonicalRequisitionBinding.resolve(List.of(matching,matching,read("MR-B","OTHER",20,"MR-B")),state,json));
        assertNull(CanonicalRequisitionBinding.resolve(List.of(read("MR-A","SKU",21,"MR-A")),state,json));
    }
    @Test void mismatchedIdentityOrAmbiguousRequestsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->CanonicalRequisitionBinding.resolve(List.of(read("MR-B","SKU",20,"MR-A")),state,json));
        assertThrows(IllegalArgumentException.class,()->CanonicalRequisitionBinding.resolve(List.of(read("MR-A","SKU",20,"MR-A"),read("MR-B","SKU",20,"MR-B")),state,json));
    }
}
