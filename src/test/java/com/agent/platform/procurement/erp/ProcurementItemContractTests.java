package com.agent.platform.procurement.erp;

import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.provider.McpProcurementDataProvider;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.agent.platform.procurement.erp.ProcurementErpAdapter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcurementItemContractTests {
    final ObjectMapper json=new ObjectMapper();
    final ProcurementErpAdapter adapter=mock(ProcurementErpAdapter.class);
    final AtomicBoolean active=new AtomicBoolean(false);
    final Instant version=Instant.parse("2026-10-03T00:00:00Z");
    final ProcurementMcpService service=new ProcurementMcpService(adapter,new RfqAuthorization("contract-test-secret-at-least-32-characters"),"V2-ALG");
    final ProcurementCaseState state=new ProcurementCaseState("AI Workstation","SKU",20,new BigDecimal("300000"),"CNY",21,
            Map.of("gpuMemoryMinGb","24"),Map.of(),Set.of(),List.of(),"SOURCING");

    Source source(String type,String id) {return new Source("ERPNext",type,id,Instant.now(),version);}
    McpProcurementDataProvider provider() {
        when(adapter.getItem("SKU")).thenAnswer(i->Optional.of(new Item("SKU","Workstation","AI Workstation","Nos",24,"3 years",source("Item","SKU"),active.get())));
        when(adapter.listItemSuppliers("SKU",0,200)).thenReturn(new Page<>(List.of(new ItemSupplier("REL","SKU","SUP",new BigDecimal("10000"),"CNY",14,100,source("Procurement Item Supplier","REL"))),null));
        when(adapter.getSupplier("SUP")).thenReturn(Optional.of(new Supplier("SUP","Supplier",true,"HIGH",source("Supplier","SUP"))));
        when(adapter.getSupplierPerformance("SUP")).thenReturn(Optional.of(new SupplierPerformance("SUP",10,new BigDecimal("0.98"),new BigDecimal("96"),source("Procurement Supplier Performance","SUP"))));
        when(adapter.getBudgetStatus("V2-ALG")).thenReturn(new BudgetStatus("BUD","V2-ALG",new BigDecimal("300000"),BigDecimal.ZERO,new BigDecimal("300000"),"CNY","2026",source("Budget","BUD")));
        var gateway=mock(McpToolGateway.class);
        when(gateway.discoverTools()).thenReturn(service.tools().stream().map(t->new ToolDefinition("mcp.erpnext."+t.get("name"),(String)t.get("description"),json.writeValueAsString(t.get("inputSchema")),ToolRiskLevel.LOW,Map.of("mcpServerId","erpnext"))).toList());
        when(gateway.callTool(any(ToolDefinition.class),any(ToolCallRequest.class))).thenAnswer(i->{
            ToolCallRequest request=i.getArgument(1);
            return new ToolCallResult(request.toolName(),true,json.writeValueAsString(service.call(request.toolName().substring("mcp.erpnext.".length()),json.valueToTree(request.arguments()))),"",Map.of());
        });
        var properties=new ProcurementDataProperties();properties.setProvider("mcp");properties.setMcpToolPrefix("mcp.erpnext.");
        @SuppressWarnings("unchecked") ObjectProvider<McpToolGateway> gatewaySource=mock(ObjectProvider.class);
        when(gatewaySource.getIfAvailable()).thenReturn(gateway);
        return new McpProcurementDataProvider(json,properties,gatewaySource);
    }
    @Test void inactiveCanonicalFactAndEvidenceReachJavaEligibility() {
        var provider=provider();
        var item=json.valueToTree(service.call("procurement_get_item",json.valueToTree(Map.of("id","SKU")))).path("record");
        assertFalse(item.path("active").asBoolean());assertEquals("Item",item.path("source").path("entityType").asText());
        var candidates=provider.searchSuppliers(state);var offers=provider.getSupplierOffers(state,candidates);
        assertFalse(offers.isEmpty());assertEquals(false,offers.get(0).specifications().get("itemActive"));
        assertEquals("SKU",((Map<?,?>)offers.get(0).specifications().get("itemSource")).get("entityId"));
        var evaluated=new ProcurementDecisionEngine().evaluate(state,candidates,offers);
        assertTrue(evaluated.candidates().stream().noneMatch(ProcurementDecisionEngine.CandidateResult::eligible));
        assertTrue(evaluated.candidates().get(0).failures().contains("INACTIVE_ITEM"));
        assertTrue(provider.getSupplierEvidence("SUP",state).stream().anyMatch(e->e.fact().contains("itemActive")));
    }
    @Test void finalizationRereadsItemAndRejectsEvenAfterAnEarlierEligibleSearch() {
        var provider=provider();active.set(true);
        var candidates=provider.searchSuppliers(state);var offers=provider.getSupplierOffers(state,candidates);
        assertTrue(new ProcurementDecisionEngine().evaluate(state,candidates,offers).candidates().get(0).eligible());
        var evidence=provider.getSupplierEvidence("SUP",state).stream().map(SupplierEvidence::evidenceId).toList();
        var cases=mock(ProcurementCaseStore.class);
        when(cases.findByTenantUserAndConversationId("tenant","buyer","conversation")).thenReturn(Optional.of(
                new ProcurementCase("case","tenant","conversation","buyer",ProcurementCaseStatus.SOURCING,state,version,version,1,"patch")));
        active.set(false);
        var draft=new ProcurementRecommendationDraft(1,"SUP",evidence,List.of(ProcurementTradeoffDimension.PRICE),0.9);
        var failure=assertThrows(IllegalArgumentException.class,()->new ProcurementRecommendationFinalizer(cases,provider,new ProcurementDecisionEngine()).finalize("tenant","buyer","conversation",draft));
        assertTrue(failure.getMessage().contains("not eligible"));
        verify(cases).findByTenantUserAndConversationId("tenant","buyer","conversation");verifyNoMoreInteractions(cases);
    }
    @Test void evidenceDoesNotDriftBecauseOfReadTimestamp() {
        var provider=provider();active.set(true);
        assertEquals(provider.getSupplierEvidence("SUP",state).stream().map(SupplierEvidence::evidenceId).toList(),
                provider.getSupplierEvidence("SUP",state).stream().map(SupplierEvidence::evidenceId).toList());
    }
}
