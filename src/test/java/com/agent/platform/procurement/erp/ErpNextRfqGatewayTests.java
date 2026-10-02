package com.agent.platform.procurement.erp;

import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.procurement.tool.ProcurementRfqGateway;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.agent.platform.procurement.erp.ProcurementErpAdapter.*;
import static com.agent.platform.procurement.erp.ProcurementErpException.Code.*;

class ErpNextRfqGatewayTests {
    @Test void signedCapabilityCannotOutlivePersistedApproval() {
        var json=new ObjectMapper();var signer=new RfqAuthorization("test-only-signing-secret-at-least-32");
        var transport=mock(McpToolGateway.class);var guard=mock(ErpRfqApprovalGuard.class);
        var expiry=Instant.now().plusSeconds(60);
        when(guard.requireApproved(any())).thenReturn(expiry);
        var definition=new ToolDefinition("mcp.erpnext.procurement_create_rfq","write","{}",ToolRiskLevel.HIGH,Map.of());
        when(transport.discoverTools()).thenReturn(List.of(definition));
        when(transport.callTool(any(ToolDefinition.class),any(ToolCallRequest.class))).thenAnswer(i->{
            ToolCallRequest call=i.getArgument(1);
            long actual=((Number)call.arguments().get("expiresAt")).longValue();
            assertEquals(expiry.getEpochSecond(),actual);
            var r=json.convertValue(call.arguments().get("request"),RfqRequest.class);
            signer.verify(r,actual,(String)call.arguments().get("signature"));
            return new ToolCallResult(call.toolName(),true,json.writeValueAsString(Map.of("record",
                    new RfqRecord("RFQ-1",r.idempotencyKey(),r.supplierId(),r.itemId(),r.quantity(),"Draft","digest",
                            new Source("ERPNext","Request for Quotation","RFQ-1",Instant.now(),Instant.now())))),"",Map.of());
        });
        var gateway=new ErpNextProcurementRfqGateway(transport,json,signer,guard);
        var request=new ProcurementRfqGateway.CreateRequest("rfq:approved","SUP","AI Workstation","SKU",20,"CNY",21,Map.of());
        assertEquals("RFQ-1",gateway.create(request).rfqId());
        when(guard.requireApproved(any())).thenReturn(Instant.now().minusSeconds(1));
        assertThrows(ProcurementRfqGateway.RejectedRequestException.class,()->gateway.create(request));
        verify(transport,times(1)).callTool(any(ToolDefinition.class),any(ToolCallRequest.class));
    }
    @Test void springSelectsProductionMcpConstructorWhenEnabled(){
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(com.agent.platform.config.McpProperties.class,com.agent.platform.config.McpProperties::new)
                .withBean(ObjectMapper.class,ObjectMapper::new)
                .withUserConfiguration(com.agent.platform.mcp.StdioMcpToolGateway.class)
                .withPropertyValues("enterprise-agent.mcp.enabled=true")
                .run(context->{assertNull(context.getStartupFailure());assertNotNull(context.getBean(McpToolGateway.class));});
    }
    @Test void realControlledChainSignsOnlyAfterApprovalAndCaseValidation(){
        var json=new ObjectMapper();var signer=new RfqAuthorization("test-only-signing-secret-at-least-32");
        var adapter=mock(ProcurementErpAdapter.class);var service=new ProcurementMcpService(adapter,signer,"V2-ALG");
        var transport=mock(McpToolGateway.class);
        var definitions=service.tools().stream().map(t->new ToolDefinition("mcp.erpnext."+t.get("name"),(String)t.get("description"),json.writeValueAsString(t.get("inputSchema")),ToolRiskLevel.LOW,Map.of())).toList();
        when(transport.discoverTools()).thenReturn(definitions);
        when(adapter.createRfq(any())).thenAnswer(i->{RfqRequest r=i.getArgument(0);return new RfqRecord("RFQ-1",r.idempotencyKey(),r.supplierId(),r.itemId(),r.quantity(),"Draft","digest",new Source("ERPNext","Request for Quotation","RFQ-1",Instant.now(),Instant.now()));});
        when(transport.callTool(any(ToolDefinition.class),any(ToolCallRequest.class))).thenAnswer(i->{
            ToolCallRequest r=i.getArgument(1);
            if(r.toolName().endsWith("procurement_get_rfq")) return new ToolCallResult(r.toolName(),false,"{\"error\":{\"code\":\"ERP_NOT_FOUND\"}}","",Map.of());
            Object result=service.call(r.toolName().substring("mcp.erpnext.".length()),json.valueToTree(r.arguments()));
            return new ToolCallResult(r.toolName(),true,json.writeValueAsString(result),"",Map.of());
        });
        assertEquals("RFQ-1",ErpRfqControlledTestSupport.approveAndExecute(guard -> new ErpNextProcurementRfqGateway(transport,json,signer,guard),json).rfqId());verify(adapter,times(1)).createRfq(any());
    }
    @Test void cancelledOrMalformedErpReceiptCannotBecomeCreated(){
        var json=new ObjectMapper();var transport=mock(McpToolGateway.class);
        var definition=new ToolDefinition("mcp.erpnext.procurement_get_rfq","read","{}",ToolRiskLevel.LOW,Map.of());
        when(transport.discoverTools()).thenReturn(List.of(definition));
        var record=new RfqRecord("RFQ-1","rfq:key","SUP","SKU",20,"Cancelled","digest",new Source("ERPNext","Request for Quotation","RFQ-1",Instant.now(),Instant.now()));
        when(transport.callTool(any(ToolDefinition.class),any(ToolCallRequest.class))).thenReturn(new ToolCallResult(definition.name(),true,json.writeValueAsString(Map.of("record",record)),"",Map.of()));
        var gateway=new ErpNextProcurementRfqGateway(transport,json,new RfqAuthorization("test-only-signing-secret-at-least-32"),mock(ErpRfqApprovalGuard.class));
        ErpNextAdapterTests.assertCode(ERP_RESPONSE_INVALID,()->gateway.findByIdempotencyKey("rfq:key"));
    }
}
