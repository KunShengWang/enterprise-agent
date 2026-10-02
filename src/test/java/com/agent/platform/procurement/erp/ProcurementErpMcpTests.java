package com.agent.platform.procurement.erp;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import static com.agent.platform.procurement.erp.ProcurementErpAdapter.*;
import static com.agent.platform.procurement.erp.ProcurementErpException.Code.*;
import static com.agent.platform.procurement.erp.ErpNextAdapterTests.assertCode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcurementErpMcpTests {
    final ObjectMapper json=new ObjectMapper();
    final ProcurementErpAdapter adapter=mock(ProcurementErpAdapter.class);
    final RfqAuthorization signer=new RfqAuthorization("contract-test-only-secret-at-least-32");
    final ProcurementMcpService service=new ProcurementMcpService(adapter,signer,"V2-ALG");
    final Source source=new Source("ERPNext","Item","SKU",Instant.now(),Instant.parse("2026-10-01T00:00:00Z"));
    @Test void domainToolsDeclareSchemasAndReadWriteNature(){
        var tools=service.tools();assertEquals(14,tools.size());
        for(var tool:tools){
            var n=json.valueToTree(tool);assertTrue(n.path("description").asText().length()>30);
            assertEquals("object",n.path("inputSchema").path("type").asText());
            assertEquals("object",n.path("outputSchema").path("type").asText());
            assertEquals(!n.path("name").asText().equals("procurement_create_rfq"),n.path("annotations").path("readOnlyHint").asBoolean());
        }
    }
    @Test void inputRejectsUnknownFieldsWrongTypeOverflowAndBadPaginationBeforeAdapter(){
        for(String args:List.of("{}","{\"id\":\"SKU\",\"extra\":1}","{\"id\":5}","{\"id\":\" \"}"))
            assertCode(ERP_VALIDATION_ERROR,()->service.call("procurement_get_item",json.readTree(args)));
        for(String args:List.of("{\"limit\":201}","{\"offset\":-1}","{\"offset\":4294967296}","{\"limit\":1.5}"))
            assertCode(ERP_VALIDATION_ERROR,()->service.call("procurement_list_items",json.readTree(args)));
        assertCode(ERP_VALIDATION_ERROR,()->service.call("get_offers",json.readTree("{\"productCategory\":\"x\",\"productDescription\":\"SKU\",\"quantity\":4294967297,\"currency\":\"CNY\",\"supplierIds\":[]}")));
        verifyNoInteractions(adapter);
    }
    @Test void outputProvenancePaginationNotFoundAndAdapterFailure(){
        Item item=new Item("SKU","Fictional workstation","AI Workstation","Nos",24,"3 years",source);
        when(adapter.listItems(2,1)).thenReturn(new Page<>(List.of(item),3));
        var output=json.valueToTree(service.call("procurement_list_items",json.readTree("{\"offset\":2,\"limit\":1}")));
        assertEquals(3,output.path("nextOffset").asInt());assertEquals("ERPNext",output.path("records").get(0).path("source").path("backend").asText());
        when(adapter.getItem("none")).thenReturn(Optional.empty());
        assertCode(ERP_NOT_FOUND,()->service.call("procurement_get_item",json.readTree("{\"id\":\"none\"}")));
        when(adapter.getItem("down")).thenThrow(new ProcurementErpException(ERP_UNAVAILABLE));
        assertCode(ERP_UNAVAILABLE,()->service.call("procurement_get_item",json.readTree("{\"id\":\"down\"}")));
        assertCode(ERP_VALIDATION_ERROR,()->service.call("procurement_get_rfq",json.readTree("{}")));
        assertCode(ERP_VALIDATION_ERROR,()->service.call("procurement_get_rfq",json.readTree("{\"id\":\"a\",\"idempotencyKey\":\"b\"}")));
    }
    @Test void unsignedForgedExpiredAndAlteredRequestsNeverWrite(){
        var r=new RfqRequest("rfq:approved","SUP","SKU",20,21,"CNY");long expiry=Instant.now().getEpochSecond()+120;
        for(var args:List.of(Map.of("request",r),Map.of("request",r,"expiresAt",expiry,"signature","forged"),
                Map.of("request",r,"expiresAt",expiry-1000,"signature",signer.sign(r,expiry-1000)),
                Map.of("request",new RfqRequest("rfq:approved","EVIL","SKU",20,21,"CNY"),"expiresAt",expiry,"signature",signer.sign(r,expiry)))){
            assertThrows(ProcurementErpException.class,()->service.call("procurement_create_rfq",json.valueToTree(args)));
        }
        verifyNoInteractions(adapter);
        var record=new RfqRecord("RFQ-1",r.idempotencyKey(),"SUP","SKU",20,"Draft","digest",source);
        when(adapter.createRfq(r)).thenReturn(record);
        var output=json.valueToTree(service.call("procurement_create_rfq",json.valueToTree(Map.of("request",r,"expiresAt",expiry,"signature",signer.sign(r,expiry)))));
        assertEquals("RFQ-1",output.path("record").path("id").asText());verify(adapter,times(1)).createRfq(r);
    }
    @Test void actualStdioServerProtocolReturnsStructuredResultsAndSafeErrors() throws Exception {
        when(adapter.getItem("none")).thenReturn(Optional.empty());
        String input="""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"procurement_get_item","arguments":{"id":"none"}}}
                """;
        var out=new ByteArrayOutputStream();ProcurementMcpServer.serve(service,new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),out);
        var lines=out.toString(StandardCharsets.UTF_8).lines().toList();assertEquals(3,lines.size());
        assertEquals("2025-11-25",json.readTree(lines.get(0)).path("result").path("protocolVersion").asText());
        assertEquals(14,json.readTree(lines.get(1)).path("result").path("tools").size());
        var error=json.readTree(lines.get(2)).path("result");assertTrue(error.path("isError").asBoolean());
        assertEquals("ERP_NOT_FOUND",json.readTree(error.path("content").get(0).path("text").asText()).path("error").path("code").asText());
    }
    @Test void erpProfileIsSingleAgentAndNeverExposesRemoteWritesOrBridgeTools() throws Exception {
        var factory=new ProcurementSourcingExecutionProfileFactory();var legacy=factory.createProfile();assertEquals(6,legacy.allowedCapabilities().size());
        ReflectionTestUtils.setField(factory,"erpNextEnabled",true);var erp=factory.createProfile();
        assertEquals(15,erp.allowedCapabilities().size());assertFalse(erp.allowedCapabilities().contains(ProcurementToolCatalog.COMMERCIAL_ANALYSIS));
        assertFalse(erp.allowedCapabilities().contains(ProcurementToolCatalog.DELIVERY_ANALYSIS));
        assertFalse(erp.allowedCapabilities().contains("mcp.erpnext.procurement_create_rfq"));
        assertFalse(erp.allowedCapabilities().contains("mcp.erpnext.get_offers"));
        assertFalse(erp.allowedCapabilities().contains("mcp.erpnext.search_suppliers"));
        var registry=mock(com.agent.platform.runtime.AgentCapabilityRegistry.class);
        when(registry.listCapabilities()).thenReturn(service.tools().stream().map(t->new com.agent.platform.tool.ToolDefinition(
                "mcp.erpnext."+t.get("name"),(String)t.get("description"),json.writeValueAsString(t.get("inputSchema")),
                com.agent.platform.tool.ToolRiskLevel.LOW,Map.of())).toList());
        var runtime=mock(com.agent.platform.runtime.DefaultAgentRuntime.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(runtime,"capabilityRegistry",registry);
        List<com.agent.platform.tool.ToolDefinition> visible=ReflectionTestUtils.invokeMethod(runtime,"capabilitiesFor",erp,Map.of(),List.of());
        assertEquals(11,visible.size());
        for(String hidden:List.of("search_suppliers","get_offers","procurement_create_rfq"))
            assertFalse(json.writeValueAsString(visible).contains("mcp.erpnext."+hidden));
        assertTrue(erp.allowedCapabilities().contains(ProcurementToolCatalog.CREATE_RFQ));
        String config=Files.readString(Path.of("src/main/resources/application-erpnext.yaml"));
        assertTrue(config.contains("provider: mcp"));assertTrue(config.contains("__legacy_scenario_unavailable_in_v2__"));
        String serviceSource=Files.readString(Path.of("src/main/java/com/agent/platform/procurement/erp/ProcurementMcpService.java"));
        assertFalse(serviceSource.contains("HttpClient"));assertFalse(serviceSource.contains("Files.read"));
    }
    @Test void seedCardinalityReferencesAndNoAnswerFields() throws Exception {
        Map<String,Integer> counts=Map.of("suppliers",6,"items",10,"item_suppliers",18,"departments",3,"budgets",3,"purchase_history",24,"supplier_performance",6,"sample_requisitions",6);
        Map<String,Set<String>> ids=new HashMap<>();
        for(var entry:counts.entrySet()){
            String data=Files.readString(Path.of("data/procurement/v2/seeds/"+entry.getKey()+".json"));var rows=json.readTree(data);
            assertEquals(entry.getValue(),rows.size());Set<String> keys=new HashSet<>();rows.forEach(r->assertTrue(keys.add(r.path("id").asText())));ids.put(entry.getKey(),keys);
            for(String forbidden:List.of("recommendedSupplier","bestSupplier","expectedWinner","shouldChoose","correctAnswer","agentRecommendation"))assertFalse(data.contains(forbidden));
        }
        for(String file:List.of("item_suppliers","purchase_history","supplier_performance","sample_requisitions","budgets"))
            for(var row:json.readTree(Files.readString(Path.of("data/procurement/v2/seeds/"+file+".json")))){
                if(row.has("supplier"))assertTrue(ids.get("suppliers").contains(row.get("supplier").asText()));
                if(row.has("item"))assertTrue(ids.get("items").contains(row.get("item").asText()));
                if(row.has("department"))assertTrue(ids.get("departments").contains(row.get("department").asText()));
            }
    }
}
