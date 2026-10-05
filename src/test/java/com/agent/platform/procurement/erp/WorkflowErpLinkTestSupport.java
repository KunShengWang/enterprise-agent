package com.agent.platform.procurement.erp;
import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
/** In-process ERP transport fixture; the approval, gateway, MCP and adapter are production objects. */
public final class WorkflowErpLinkTestSupport {
    public static ProcurementRfqGateway gateway(com.agent.platform.approval.ApprovalStore approvals, AgentRunStore runs, ToolExecutionStore executions, ProcurementRfqApprovalPreparer preparer, tools.jackson.databind.ObjectMapper json, AtomicReference<tools.jackson.databind.JsonNode> document, java.util.concurrent.atomic.AtomicInteger posts) {
        var client=mock(ErpNextClient.class);var adapter=new ErpNextProcurementAdapter(client,"TEST");
        when(client.list(eq("Request for Quotation"),anyList(),eq(0),eq(2))).thenAnswer(i->document.get()==null?List.of():List.of(document.get()));
        when(client.get("Request for Quotation","RFQ-1")).thenAnswer(i->Optional.ofNullable(document.get()));
        when(client.get("Supplier","SUP")).thenReturn(Optional.of(json.valueToTree(row("name","SUP","supplier_name","Supplier","disabled",0,"custom_approval_status","Approved","custom_capacity","HIGH"))));
        when(client.get("Item","SKU")).thenReturn(Optional.of(json.valueToTree(row("name","SKU","item_name","Item","item_group","Workstation","stock_uom","Nos","custom_gpu_memory_gb",24,"custom_warranty","3 years","disabled",0))));
        when(client.get("Material Request","V2-REQ-001")).thenReturn(Optional.of(json.valueToTree(row("name","V2-REQ-001","company","TEST","custom_department_id","V2-ALG","status","Draft","items",List.of(Map.of("name","MR-LINE-001","item_code","SKU","qty",2,"schedule_date","2026-10-31"))))));
        when(client.list(eq("Procurement Item Supplier"),anyList(),eq(0),eq(200))).thenReturn(List.of(json.valueToTree(row("item","SKU","supplier","SUP","reference_price",10,"currency","CNY","lead_time_days",2,"capacity_units",10))));
        when(client.create(eq("Request for Quotation"),anyMap())).thenAnswer(i->{posts.incrementAndGet();var doc=new LinkedHashMap<String,Object>(i.getArgument(1));doc.putAll(row("name","RFQ-1"));document.set(json.valueToTree(doc));return document.get();});
        var signer=new RfqAuthorization("test-only-signing-secret-at-least-32");var service=new ProcurementMcpService(adapter,signer,"V2-ALG");var mcp=mock(McpToolGateway.class);
        when(mcp.discoverTools()).thenReturn(service.tools().stream().map(t->new ToolDefinition("mcp.erpnext."+t.get("name"),(String)t.get("description"),json.writeValueAsString(t.get("inputSchema")),ToolRiskLevel.LOW,Map.of())).toList());
        when(mcp.callTool(any(ToolDefinition.class),any(ToolCallRequest.class))).thenAnswer(i->{ToolCallRequest call=i.getArgument(1);Object result=service.call(call.toolName().substring("mcp.erpnext.".length()),json.valueToTree(call.arguments()));return new ToolCallResult(call.toolName(),true,json.writeValueAsString(result),"",Map.of());});
        return new ErpNextProcurementRfqGateway(mcp,json,signer,new ErpRfqApprovalGuard(approvals,runs,executions,preparer,json));
    }
    private static Map<String,Object> row(Object... pairs) {
        var result=new LinkedHashMap<String,Object>();result.put("name","ID");result.put("modified","2026-10-01 00:00:00");
        for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }
}
