package com.agent.platform.procurement;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.*;
import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.erp.*;
import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.agent.platform.procurement.tool.ProcurementToolCatalog.*;

class ProcurementToolSelectorTests {
    final ObjectMapper json=new ObjectMapper();
    final List<ToolDefinition> tools=tools();
    final AgentExecutionProfile profile=new AgentExecutionProfile(ProcurementSourcingExecutionProfileFactory.PROFILE_NAME,
            "unchanged",tools.stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet()),
            AgentRunLimits.from(new AgentProperties()),false,ContextPolicy.disabled(),true);
    final ProcurementToolSelector selector=new ProcurementToolSelector(new ConservativeTokenEstimator());

    @Test void initialAndCaseBuildingKeepPrerequisiteEnterpriseReadsWithoutRfqOrFinalize() {
        for(var state:List.of(ToolSelectionState.absent(),state("CLARIFICATION_REQUIRED",false))) {
            var selected=select(state,List.of(),run(AgentRunState.RUNNING,List.of()));
            assertEquals(12,selected.tools().size());
            for(String name:List.of(CASE_PATCH,"mcp.erpnext.procurement_get_purchase_history","mcp.erpnext.procurement_get_supplier_performance"))
                assertTrue(selected.contains(name));
            for(String name:List.of(CREATE_RFQ,SUPPLIER_SEARCH,RECOMMENDATION_FINALIZE))assertFalse(selected.contains(name));
        }
    }
    @Test void eachCompleteCaseStateRequiresActualFinalizeBeforeRfqExposure() {
        for(String status:List.of("SOURCING","RECOMMENDATION_READY","NO_ELIGIBLE_SUPPLIER")) {
            var selected=select(state(status,true),List.of(),run(AgentRunState.RUNNING,List.of()));
            assertEquals(13,selected.tools().size());assertTrue(selected.contains(SUPPLIER_SEARCH));
            assertTrue(selected.contains(RECOMMENDATION_FINALIZE));assertFalse(selected.contains(CREATE_RFQ));
        }
    }
    @Test void currentRunVersionBoundFinalizeEnablesRfqPreparationAndCoreBudgetRead() {
        var selected=select(state("SOURCING",true),List.of(finalizeAt("r",2)),run(AgentRunState.RUNNING,List.of()));
        assertEquals("RFQ_PREPARATION",selected.metrics().get("selection_stage"));assertEquals(8,selected.tools().size());
        assertTrue(selected.contains(CREATE_RFQ));assertTrue(selected.contains("mcp.erpnext.procurement_get_budget_status"));
        assertFalse(selected.contains(RECOMMENDATION_FINALIZE));
    }
    @Test void staleVersionWrongRunMalformedOrFailedFinalizeNeverEnablesRfq() {
        var good=finalizeAt("r",2);
        var malformed=good.withResult(ToolExecutionState.SUCCEEDED,new ToolCallResult(RECOMMENDATION_FINALIZE,true,"{broken","",Map.of()),"");
        for(var record:List.of(finalizeAt("r",1),finalizeAt("other",2),malformed,
                good.withResult(ToolExecutionState.FAILED,good.result(),"failed"))) {
            assertFalse(select(state("SOURCING",true),List.of(record),run(AgentRunState.RUNNING,List.of())).contains(CREATE_RFQ));
        }
    }
    @Test void waitingApprovalHasOnlyFourSafeReadsAndResumeCannotRestoreSingleUse() {
        var pending=select(state("SOURCING",true),List.of(finalizeAt("r",2)),run(AgentRunState.WAITING_APPROVAL,List.of()));
        assertEquals(4,pending.tools().size());assertEquals("PENDING_APPROVAL",pending.metrics().get("selection_stage"));
        var resumed=select(state("SOURCING",true),List.of(finalizeAt("r",2)),run(AgentRunState.RUNNING,List.of(CREATE_RFQ)));
        assertEquals(5,resumed.tools().size());assertFalse(resumed.contains(CREATE_RFQ));
        assertFalse(resumed.contains(SUPPLIER_SEARCH));assertEquals("POST_RFQ",resumed.metrics().get("selection_stage"));
        var candidates=tools.stream().filter(t->!t.name().equals(CREATE_RFQ)).toList();
        var reduced=selector.select(profile,state("SOURCING",true),run(AgentRunState.RUNNING,List.of()),List.of(finalizeAt("r",2)),candidates);
        assertFalse(reduced.contains(CREATE_RFQ)); // Cannot expand the pre-filtered registry candidates.
    }
    @Test void unknownStateMissingProviderAndCorruptContractsFailClosed() {
        for(var state:List.of(ToolSelectionState.unknown(),state("BAD_STATE",true),state("SOURCING",false)))
            assertEquals(4,select(state,List.of(),run(AgentRunState.RUNNING,List.of())).tools().size());
        assertEquals(4,select(state("SOURCING",true),List.of(),null).tools().size());
        var corrupted=new ToolDefinition("mcp.erpnext.procurement_get_budget_status","same","{bad",ToolRiskLevel.LOW,Map.of());
        var unknown=new ToolDefinition("mcp.erpnext.procurement_create_rfq","backend","{\"type\":\"object\"}",ToolRiskLevel.HIGH,Map.of());
        var selected=selector.select(profile,ToolSelectionState.unknown(),run(AgentRunState.RUNNING,List.of()),List.of(),List.of(corrupted,unknown));
        assertTrue(selected.tools().isEmpty());assertEquals(2,selected.metrics().get("hidden_tool_count"));
    }
    @Test void coreReadCapabilitiesRemainAcrossStagesAndMetricsMatchActualDefinitions() {
        for(var state:List.of(ToolSelectionState.absent(),state("SOURCING",true),ToolSelectionState.unknown())) {
            var s=select(state,List.of(),run(AgentRunState.RUNNING,List.of()));
            for(String name:List.of("procurement_get_budget_status","procurement_get_rfq","procurement_get_item","procurement_get_requisition"))
                assertTrue(s.contains("mcp.erpnext."+name));
            assertEquals(s.tools().size(),s.metrics().get("visible_tool_count"));
            assertTrue((long)s.metrics().get("tool_schema_estimated_tokens")<(long)s.metrics().get("candidate_schema_estimated_tokens"));
            assertTrue(s.tools().stream().allMatch(tools::contains));
        }
    }
    @Test void serverStateLookupUsesTrustedScopeAndDistinguishesAbsenceFromFailure() {
        var store=mock(ProcurementCaseStore.class);var renderer=new ProcurementCaseContextRenderer(store,json);
        when(store.findByTenantUserAndConversationId("t","u","s")).thenReturn(Optional.empty());
        assertEquals(ToolSelectionState.absent(),renderer.toolSelectionState("t","u","s",profile).orElseThrow());
        assertFalse(renderer.toolSelectionState("","u","s",profile).orElseThrow().authoritative());
        var state=new ProcurementCaseState("computer","SKU",2,new BigDecimal("200"),"CNY",7,Map.of(),Map.of(),Set.of(),List.of(),"SOURCING");
        var c=new ProcurementCase("c","t","s","u",ProcurementCaseStatus.SOURCING,state,Instant.now(),Instant.now(),2,"patch");
        when(store.findByTenantUserAndConversationId("t","u","s")).thenReturn(Optional.of(c));
        assertEquals(state("SOURCING",true),renderer.toolSelectionState("t","u","s",profile).orElseThrow());
        when(store.findByTenantUserAndConversationId("t","u","s")).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class,()->renderer.toolSelectionState("t","u","s",profile));
    }
    @Test void hiddenWriteBypassingSelectorStillCannotPassRealApprovalPreparation() {
        var store=mock(ProcurementCaseStore.class);var executions=mock(ToolExecutionStore.class);
        var policy=new DefaultToolPermissionPolicy();var rfq=tools.stream().filter(t->t.name().equals(CREATE_RFQ)).findFirst().orElseThrow();
        var request=new ToolCallRequest(CREATE_RFQ,"call",Map.of());
        var context=new ToolPolicyContext("r","s","u","t",Set.of("USER"),Map.of("stage","RFQ_PREPARATION"));
        assertEquals(GuardrailAction.REQUIRE_APPROVAL,policy.check(rfq,request,context).action());
        var preparer=new ProcurementRfqApprovalPreparer(executions,store,json);
        when(store.findByTenantUserAndConversationId("t","u","s")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,()->preparer.prepare("approval",request,context));
        verify(executions,never()).claim(anyString(),any());
    }
    @Test void legacyProfileIsExactPassThrough() {
        var legacy=new AgentExecutionProfile("legacy","prompt",Set.of(),AgentRunLimits.from(new AgentProperties()),false);
        assertEquals(tools,selector.select(legacy,null,null,List.of(),tools).tools());
    }

    private ToolSelector.Selection select(ToolSelectionState state,List<ToolExecutionRecord> records,AgentRunRecord run) {
        return selector.select(profile,state,run,records,tools);
    }
    private ToolSelectionState state(String status,boolean complete) {return new ToolSelectionState(true,true,"c",2,status,complete,complete);}
    private AgentRunRecord run(AgentRunState state,List<String> used) {
        var run=mock(AgentRunRecord.class);when(run.runId()).thenReturn("r");when(run.state()).thenReturn(state);when(run.usedTools()).thenReturn(used);return run;
    }
    private ToolExecutionRecord finalizeAt(String run,long version) {
        var raw=new ToolCallResult(RECOMMENDATION_FINALIZE,true,json.writeValueAsString(Map.of("source","verified-provider-snapshot",
                "caseId","c","caseVersion",version,"recommendation",Map.of("recommendedSupplier",Map.of("supplierId","S"),"selectedOffer",Map.of("supplierId","S")))),"",Map.of());
        return new ToolExecutionRecord("f",run,RECOMMENDATION_FINALIZE,ToolExecutionState.SUCCEEDED,
                new ToolCallRequest(RECOMMENDATION_FINALIZE,"f",Map.of()),raw,1,"",Instant.now(),Instant.now());
    }
    private List<ToolDefinition> tools() {
        var list=new ArrayList<>(new ProcurementToolCatalog().definitions().stream()
                .filter(t->!Set.of(COMMERCIAL_ANALYSIS,DELIVERY_ANALYSIS).contains(t.name())).toList());
        var service=new ProcurementMcpService(mock(ProcurementErpAdapter.class),mock(RfqAuthorization.class),"V2-ALG");
        service.tools().stream().filter(t->ProcurementMcpService.READ_TOOLS.contains(t.get("name"))).forEach(t->{
            String name=(String)t.get("name");list.add(new ToolDefinition("mcp.erpnext."+name,(String)t.get("description"),
                    json.writeValueAsString(t.get("inputSchema")),ToolRiskLevel.MEDIUM,
                    Map.of("provider","mcp","mcpServerId","erpnext","mcpToolName",name)));});
        return List.copyOf(list);
    }
}
