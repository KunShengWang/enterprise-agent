package com.agent.platform.procurement.erp;

import com.agent.platform.config.McpProperties;
import com.agent.platform.mcp.*;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.provider.McpProcurementDataProvider;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.tool.ProcurementRfqGateway;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in tests against the actual seeded ERPNext, including a real child stdio MCP process. */
@EnabledIfEnvironmentVariable(named="ERPNEXT_IT",matches="true")
class ErpNextProcurementIT {
    final ObjectMapper json=new ObjectMapper();
    final ErpNextProcurementAdapter adapter=ErpNextProcurementAdapter.fromEnvironment();
    StdioMcpToolGateway mcp;
    @BeforeEach void open(){
        var server=new McpProperties.Server();server.setServerName("erpnext");server.setToolNamePrefix("mcp.erpnext.");
        server.setCommand(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
        server.setArgs(List.of("-cp",System.getProperty("java.class.path"),ProcurementMcpServer.class.getName()));
        var properties=new McpProperties();properties.setServers(List.of(server));mcp=new StdioMcpToolGateway(properties,json);
    }
    @AfterEach void close(){if(mcp!=null)mcp.shutdown();}
    @Test void seededCoreFactsAreReadableThroughRuntimeIdentity(){
        assertEquals(6,adapter.listSuppliers(0,50).records().size());assertEquals(10,adapter.listItems(0,50).records().size());
        assertEquals(6,adapter.listRequisitions(0,50).records().size());
        assertEquals(new BigDecimal("420000"),adapter.getBudgetStatus("V2-ALG").available().stripTrailingZeros().setScale(0));
        assertEquals(6,adapter.getPurchaseHistory("V2-SUP-001",0,50).records().size());
        assertEquals(new BigDecimal("0.98"),adapter.getSupplierPerformance("V2-SUP-001").orElseThrow().onTimeRate());
        assertEquals(20,adapter.getRequisition("V2-REQ-001").orElseThrow().quantity());
        assertFalse(adapter.getSupplier("V2-SUP-006").orElseThrow().approved());
    }
    @Test void existingProviderUsesRealStdioAndErpWithoutLegacyScenario() throws Exception {
        assertEquals(14,mcp.discoverTools().size());
        var properties=new ProcurementDataProperties();properties.setProvider("mcp");properties.setMcpToolPrefix("mcp.erpnext.");
        properties.setScenarioDir("__does_not_exist__");properties.setScenarioFile("");
        @SuppressWarnings("unchecked") ObjectProvider<McpToolGateway> source=mock(ObjectProvider.class);when(source.getIfAvailable()).thenReturn(mcp);
        var provider=new McpProcurementDataProvider(json,properties,source);
        var state=new ProcurementCaseState("AI Workstation","V2-WS-001",20,new BigDecimal("300000"),"CNY",21,
                Map.of("gpuMemoryMinGb","24"),Map.of("deliveryPriority","HIGH"),Set.of(),List.of(),"SOURCING");
        var candidates=provider.searchSuppliers(state);var offers=provider.getSupplierOffers(state,candidates);
        assertEquals(5,candidates.size());assertEquals(4,offers.size());
        var evaluation=new ProcurementDecisionEngine().evaluate(state,candidates,offers);
        assertTrue(evaluation.candidates().stream().anyMatch(ProcurementDecisionEngine.CandidateResult::eligible));
        var evidence=provider.getSupplierEvidence("V2-SUP-001",state);
        assertTrue(evidence.stream().anyMatch(e->"OFFER".equals(e.evidenceType())));
        assertTrue(offers.stream().allMatch(o->o.sourceRecordId().startsWith("ERPNext:")));
        Files.createDirectories(Path.of("target/erpnext"));
        Files.writeString(Path.of("target/erpnext/provider-demo.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("dataSource","ERPNext","candidates",candidates,"offers",offers,"evaluation",evaluation,"evidence",evidence)));
    }
    @Test void approvedCanonicalRequestCreatesOneDraftAndUnsignedCallIsRejected() throws Exception {
        var execution=ErpRfqControlledTestSupport.approveAndExecute(guard -> new ErpNextProcurementRfqGateway(mcp,json,RfqAuthorization.fromEnvironment(),guard),json);
        var receipt=execution.rfqId();var gateway=execution.gateway();
        var record=adapter.getRfq(receipt).orElseThrow();
        assertEquals("Draft",record.status());assertEquals(20,record.quantity());
        var same=gateway.create(new ProcurementRfqGateway.CreateRequest(record.idempotencyKey(),"V2-SUP-001","AI Workstation","V2-WS-001",20,"CNY",21,Map.of()));
        assertEquals(receipt,same.rfqId());
        var rows=ErpNextClient.fromEnvironment().list("Request for Quotation",List.of(List.of("custom_procurement_key","=",record.idempotencyKey())),0,10);
        assertEquals(1,rows.size());
        var definition=mcp.discoverTools().stream().filter(d->d.name().equals("mcp.erpnext.procurement_create_rfq")).findFirst().orElseThrow();
        var unsigned=mcp.callTool(definition,new ToolCallRequest(definition.name(),"unsigned",Map.of("request",Map.of("idempotencyKey","rfq:unsigned"))));
        assertFalse(unsigned.success());assertTrue(adapter.findRfqByIdempotencyKey("rfq:unsigned").isEmpty());
        var rejectedRequest=new ProcurementErpAdapter.RfqRequest("rfq:rejected-"+UUID.randomUUID(),"V2-SUP-001","V2-WS-001",20,21,"CNY");
        long expires=java.time.Instant.now().getEpochSecond()+120;
        var signer=RfqAuthorization.fromEnvironment();
        for(var args:List.of(Map.of("request",rejectedRequest),
                Map.of("request",rejectedRequest,"expiresAt",expires,"signature","forged"),
                Map.of("request",rejectedRequest,"expiresAt",expires-1000,"signature",signer.sign(rejectedRequest,expires-1000)),
                Map.of("request",new ProcurementErpAdapter.RfqRequest(rejectedRequest.idempotencyKey(),"V2-SUP-001","V2-WS-001",21,21,"CNY"),
                        "expiresAt",expires,"signature",signer.sign(rejectedRequest,expires)))) {
            @SuppressWarnings("unchecked") Map<String,Object> payload=json.convertValue(args,Map.class);
            var denied=mcp.callTool(definition,new ToolCallRequest(definition.name(),"denied-"+UUID.randomUUID(),payload));
            assertFalse(denied.success());assertTrue(adapter.findRfqByIdempotencyKey(rejectedRequest.idempotencyKey()).isEmpty());
        }
        Files.writeString(Path.of("target/erpnext/rfq-demo.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(record));
    }
}
