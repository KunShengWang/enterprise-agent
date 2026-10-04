package com.agent.platform.procurement;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.*;
import com.agent.platform.procurement.agent.*;
import com.agent.platform.procurement.erp.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.agent.platform.procurement.tool.ProcurementToolCatalog.*;

class ChildContractBoundaryTests {
    final ObjectMapper json=new ObjectMapper();
    final String budget="mcp.erpnext.procurement_get_budget_status";
    JsonNode refs(){return json.valueToTree(Map.of("departmentId","dept-canonical","itemId","item-canonical","requisitionId","req-canonical"));}
    ToolCallRequest budgetRequest(String value){return new ToolCallRequest(budget,UUID.randomUUID().toString(),Map.of("departmentId",value));}
    @Test void serverMappingIsPublishedWithoutModelGuesswork(){
        var service=new ProcurementMcpService(mock(ProcurementErpAdapter.class),mock(RfqAuthorization.class),"configured-dept");
        var schema=json.valueToTree(service.tools().stream().filter(t->t.get("name").equals("procurement_get_budget_status")).findFirst().orElseThrow());
        assertEquals("configured-dept",schema.path("inputSchema").path("x-businessRefs").path("departmentId").asText());
    }
    @Test void correctCanonicalDepartmentAccepted(){assertNull(ChildBusinessScope.validateRefs(refs(),budgetRequest("dept-canonical")));}
    @Test void wrongAndMissingCanonicalDepartmentFailSafely(){
        assertEquals(ToolExecutionOutcome.VALIDATION_ERROR,ChildBusinessScope.validateRefs(refs(),budgetRequest("Department dept-canonical")).outcome());
        assertEquals("MISSING_CANONICAL_REFERENCE",ChildBusinessScope.validateRefs(json.createObjectNode(),budgetRequest("invented")).errorMessage());
    }
    @Test void boundIdsCannotBeReplacedButUnboundCandidatesCanBeDiscovered(){
        for(String suffix:List.of("get_item","get_requisition"))assertNotNull(ChildBusinessScope.validateRefs(refs(),new ToolCallRequest("mcp.erpnext.procurement_"+suffix,"id",Map.of("id","wrong"))));
        assertNull(ChildBusinessScope.validateRefs(refs(),new ToolCallRequest("mcp.erpnext.procurement_get_supplier","id",Map.of("id","candidate"))));
        assertNull(ChildBusinessScope.validateRefs(json.createObjectNode(),new ToolCallRequest("mcp.erpnext.procurement_get_item","id",Map.of("id","discovered"))));
    }
    @Test void packetBindsScopeAndWrongQueryNeverReachesExecutor(){
        var f=new ProcurementMultiAgentTests().new Fixture();f.start();
        var child=f.runs.recent(100).stream().filter(r->AgentRegistry.SOURCING.equals(r.executionProfile().name())).findFirst().orElseThrow();
        var packet=json.readTree(child.request().question());
        assertEquals("department-test",packet.path("businessRefs").path("departmentId").asText());
        assertEquals("case-1",packet.path("businessRefs").path("caseId").asText());
        assertEquals("UNSUPPORTED_BY_CANONICAL_MODEL",packet.path("referencePolicy").path("costCenterId").asText());
        var capability=mock(AgentCapabilityExecutor.class);when(capability.execute(any())).thenReturn(new ToolCallResult(budget,true,"{}","",Map.of()));
        var guard=mock(GuardrailService.class);when(guard.checkToolCall(any(),any(),any())).thenReturn(GuardrailDecision.allow(GuardrailStage.TOOL,"test"));
        var runtime=new DefaultAgentToolRuntime(guard,f.approvals,f.executions,capability,new AgentProperties(),List.of(),List.of());
        runtime.setRunLinks(new AgentRunLinks(f.runs));runtime.setChildBusinessScope(new ChildBusinessScope(f.runs,json));
        for(String department:List.of("wrong","department-test")){
            var r=runtime.execute(child.runId(),child.conversationId(),child.userId(),child.request().metadata(),new AgentToolCall(UUID.randomUUID().toString(),budget,Map.of("departmentId",department),""),f.definition(budget));
            assertEquals(department.equals("wrong")?ToolExecutionOutcome.VALIDATION_ERROR:ToolExecutionOutcome.SUCCESS,r.result().outcome());
        }
        verify(capability,times(1)).execute(argThat(r->r.arguments().get("departmentId").equals("department-test")));
        assertTrue(f.executions.findByRun(child.runId()).stream().anyMatch(e->e.result()!=null && e.result().outcome()==ToolExecutionOutcome.VALIDATION_ERROR));
    }
    ToolExecutionRecord record(String run,String id,String tool,Object content,ToolExecutionOutcome outcome){
        var request=new ToolCallRequest(tool,id,Map.of());
        return ToolExecutionRecord.running(run,request).withResult(outcome==ToolExecutionOutcome.SUCCESS?ToolExecutionState.SUCCEEDED:ToolExecutionState.FAILED,
                new ToolCallResult(tool,outcome==ToolExecutionOutcome.SUCCESS,json.writeValueAsString(content),"",Map.of("_harnessOutcome",outcome.name())),"");
    }
    Map<String,Object> fact(){return Map.of("record",Map.of("id","budget-1","departmentId","dept-canonical","available",100,"source",Map.of("entityType","Budget","entityId","budget-1","version","2026-01-01T00:00:00Z")));}
    @Test void successLedgerCarriesCanonicalFactsAndSourceReceipt(){
        var entry=ChildEvidenceLedger.derive(List.of(record("child","ok",budget,fact(),ToolExecutionOutcome.SUCCESS)),json).stream().filter(e->e.evidenceRef().equals("tool-execution:ok")).findFirst().orElseThrow();
        assertEquals("ok",entry.sourceToolCallId());assertEquals("SUCCESS",entry.outcome());assertEquals("budget-1",entry.entity());assertEquals(100,entry.canonicalFacts().path("record").path("available").asInt());
    }
    @Test void allFailedOutcomesAndMalformedSuccessExcluded(){
        for(var outcome:ToolExecutionOutcome.values())if(outcome!=ToolExecutionOutcome.SUCCESS)assertTrue(ChildEvidenceLedger.derive(List.of(record("child","fail",budget,fact(),outcome)),json).isEmpty());
        assertTrue(ChildEvidenceLedger.derive(List.of(record("child","bad",budget,Map.of("record",Map.of("id","b")),ToolExecutionOutcome.SUCCESS)),json).isEmpty());
    }
    JsonNode answer(String ref){return json.valueToTree(Map.of("status","COMPLETE","findings",List.of("已写入 Case"),"warnings",List.of(),"evidenceRefs",List.of(ref)));}
    @Test void fakeAndFailedReferencesCannotValidateOutput(){for(String ref:List.of("fake","tool-execution:failed"))assertThrows(IllegalArgumentException.class,()->SubAgentExecutor.validate(answer(ref),Set.of("real")));}
    @Test void crossChildEvidenceRequiresExplicitServerGrant(){
        var f=new ProcurementMultiAgentTests().new Fixture();f.start();
        var child=f.runs.recent(100).stream().filter(r->AgentRegistry.RFQ.equals(r.executionProfile().name())).findFirst().orElseThrow();
        var source=f.runs.recent(100).stream().filter(r->AgentRegistry.SOURCING.equals(r.executionProfile().name())).findFirst().orElseThrow();
        f.executions.records.put("private",record(source.runId(),"private",budget,fact(),ToolExecutionOutcome.SUCCESS));
        var executor=new SubAgentExecutor(mock(AgentRuntime.class),f.runs,f.executions,f.cases,f.registry,json);
        var allowed=executor.evidenceRefs(child.runId());assertFalse(allowed.contains("tool-execution:private"));assertTrue(allowed.containsAll(f.evidence));
        assertThrows(IllegalArgumentException.class,()->SubAgentExecutor.validate(answer("tool-execution:private"),allowed));
    }
    @Test void noPatchProseIsNeverAnAuthoritativeMutation(){
        SubAgentExecutor.validate(answer("real"),Set.of("real"));assertEquals(false,ChildEvidenceLedger.executionFacts(List.of(),null,json).get("casePatched"));
        var forged=(tools.jackson.databind.node.ObjectNode)answer("real");forged.put("casePatched",true);assertThrows(IllegalArgumentException.class,()->SubAgentExecutor.validate(forged,Set.of("real")));
    }
    @Test void patchRequiresSuccessfulReceiptAndMatchingAuthoritativeStore(){
        var f=new ProcurementMultiAgentTests().new Fixture();var c=f.cases.findByTenantUserAndConversationId("tenant","buyer","conversation").orElseThrow();var body=Map.of("caseId",c.caseId(),"caseVersion",c.version(),"state",c.state());
        var success=record("child","patch",CASE_PATCH,body,ToolExecutionOutcome.SUCCESS);
        assertEquals(true,ChildEvidenceLedger.executionFacts(List.of(success),c,json).get("casePatched"));assertEquals(false,ChildEvidenceLedger.executionFacts(List.of(success),null,json).get("casePatched"));
        assertEquals(false,ChildEvidenceLedger.executionFacts(List.of(record("child","unrecorded",CASE_PATCH,body,ToolExecutionOutcome.SUCCESS)),c,json).get("casePatched"));
        assertEquals(false,ChildEvidenceLedger.executionFacts(List.of(record("child","patch",CASE_PATCH,body,ToolExecutionOutcome.BUSINESS_ERROR)),c,json).get("casePatched"));
    }
    @Test void schemaAndRuntimeRejectUnknownStatusAndDeferredEnum(){
        for(var domain:new AgentRegistry(new AgentProperties()).domains()){
            assertTrue(json.readTree(domain.outputSchema()).path("properties").path("status").path("enum").isArray());assertTrue(domain.profile().systemPrompt().contains(domain.outputSchema()));
        }
        var invalid=(tools.jackson.databind.node.ObjectNode)answer("real");invalid.put("status","SOMETHING_NEW");assertThrows(IllegalArgumentException.class,()->SubAgentExecutor.validate(invalid,Set.of("real")));
        var a=new RequisitionAssessmentTests();assertThrows(IllegalArgumentException.class,()->RequisitionAssessment.validate(a.answer("NEEDS_CLARIFICATION",List.of(),List.of("BUDGET_AVAILABILITY_VERIFICATION"),""),null,List.of(),json));
        SubAgentExecutor.validate(answer("real"),Set.of("real"));
    }
}
