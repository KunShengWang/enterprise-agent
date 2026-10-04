package com.agent.platform.procurement;

import com.agent.platform.procurement.agent.*;
import com.agent.platform.procurement.erp.ProcurementErpAdapter.*;
import com.agent.platform.procurement.model.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import com.agent.platform.config.AgentProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequisitionAssessmentTests {
    final ObjectMapper json=new ObjectMapper();
    final ProcurementCaseState state=new ProcurementCaseState("workstation","item-1",20,new BigDecimal("300000"),"CNY",21,
            Map.of("gpuMemoryMinGb","24"),Map.of("deliveryPriority","HIGH"),Set.of(),List.of(),"SOURCING");
    Source source(String id){return new Source("erpnext","canonical",id,Instant.EPOCH,Instant.EPOCH);}
    Requisition req(String id,int qty){return new Requisition(id,"department-1","item-1",qty,LocalDate.of(2026,12,15),"Draft",source(id));}
    ToolExecutionRecord read(String id,String tool,Object data){
        var r=new ToolCallRequest(tool,id,Map.of("departmentId","department-1"));
        return ToolExecutionRecord.running("child",r).withResult(ToolExecutionState.SUCCEEDED,new ToolCallResult(tool,true,json.writeValueAsString(data),"",Map.of()),"");
    }
    ToolExecutionRecord erp(String id,String name,Object data){return read(id,"mcp.erpnext.procurement_"+name,data);}
    ToolExecutionRecord budget(int amount){return erp("budget","get_budget_status",Map.of("record",new BudgetStatus("b","department-1",new BigDecimal(amount),BigDecimal.ZERO,new BigDecimal(amount),"CNY","2026",source("b"))));}
    ToolExecutionRecord item(boolean active){return erp("item","get_item",Map.of("record",new Item("item-1","name","workstation","Nos",24,"36",source("item-1"),active)));}
    ToolExecutionRecord failure(String code){var r=new ToolCallRequest("mcp.erpnext.procurement_get_budget_status","negative",Map.of("departmentId","department-1"));return ToolExecutionRecord.running("child",r).withResult(ToolExecutionState.FAILED,new ToolCallResult(r.toolName(),false,"{\"error\":{\"code\":\""+code+"\"}}",code,Map.of()),code);}
    JsonNode answer(String status,List<String> candidates,List<String> deferred,String subject){return answer(status,candidates,deferred,subject,"tool-execution:item");}
    JsonNode answer(String status,List<String> candidates,List<String> deferred,String subject,String ref){
        return json.valueToTree(Map.of("status",status,"blockerCandidates",candidates.stream().map(c->Map.of("type",c,"evidenceRefs",List.of(ref))).toList(),
                "deferredChecks",deferred,"subjectRequisitionId",subject,"departmentId","department-1"));
    }
    Map<String,Object> assess(List<String> candidates,List<ToolExecutionRecord> records){return RequisitionAssessment.validate(answer("BLOCKED",candidates,List.of(),""),state,records,json);}
    @Test void unsupportedCandidateIsRejectedWithoutInvalidatingChild(){
        var r=assess(List.of("BUDGET_UNAVAILABLE"),List.of(item(true),budget(5000)));
        assertEquals(Set.of(),r.get("validatedBlockers"));assertEquals(1,r.get("rejectedCandidateCount"));assertEquals("READY",r.get("status"));
    }
    @Test void validAndUnsupportedCandidatesProduceOneAuthoritativeBlocker(){
        var r=assess(List.of("ITEM_INACTIVE","BUDGET_UNAVAILABLE"),List.of(item(false),budget(5000)));
        assertEquals(Set.of("ITEM_INACTIVE"),r.get("validatedBlockers"));assertEquals(1,r.get("rejectedCandidateCount"));assertEquals("BLOCKED",r.get("status"));
    }
    @Test void allUnsupportedCandidatesCannotMakeAuthoritativeBlocked(){
        var r=assess(List.of("BUDGET_UNAVAILABLE","DUPLICATE_REQUEST"),List.of(item(true),budget(5000)));
        assertEquals(Set.of(),r.get("validatedBlockers"));assertEquals("READY",r.get("status"));assertEquals(2,r.get("rejectedCandidateCount"));
    }
    @Test void missingCanonicalBudgetIsDistinctFromFailedPositiveEvidence(){
        var r=assess(List.of("BUDGET_UNAVAILABLE"),List.of(item(true),failure("ERP_NOT_FOUND")));
        assertEquals(Set.of("BUDGET_UNAVAILABLE"),r.get("validatedBlockers"));assertEquals("UNAVAILABLE",r.get("budgetStatus"));
        assertTrue(ChildEvidenceLedger.derive(List.of(failure("ERP_NOT_FOUND")),json).isEmpty());
        assertThrows(IllegalArgumentException.class,()->RequisitionAssessment.validate(answer("BLOCKED",List.of("BUDGET_UNAVAILABLE"),List.of(),"","tool-execution:negative"),state,List.of(failure("ERP_NOT_FOUND")),json));
    }
    @Test void backendFailureIsNotBusinessUnavailable(){
        for(String code:List.of("ERP_TIMEOUT","ERP_UNAVAILABLE","ERP_AUTH_ERROR")){
            var r=assess(List.of("BUDGET_UNAVAILABLE"),List.of(item(true),failure(code)));
            assertEquals(Set.of(),r.get("validatedBlockers"));assertEquals("BACKEND_ERROR",r.get("budgetStatus"));assertEquals("NEEDS_CLARIFICATION",r.get("status"));
        }
    }
    @Test void missingReadAndZeroBalanceDoNotMeanBudgetRecordUnavailable(){
        for(var reads:List.of(List.of(item(true)),List.of(item(true),budget(0)))){
            var r=assess(List.of("BUDGET_UNAVAILABLE"),reads);assertEquals(Set.of(),r.get("validatedBlockers"));
        }
    }
    ProcurementCase current(){return new ProcurementCase("case","tenant","conversation","buyer",ProcurementCaseStatus.SOURCING,state,Instant.EPOCH,Instant.EPOCH,1,"patch");}
    ToolExecutionRecord amount(String currency,long version){return read("amount","procurement_recommendation_finalize",Map.of("caseId","case","caseVersion",version,"evidence",List.of(),"recommendation",Map.of("selectedOffer",Map.of("productId","item-1","quantity",20,"unitPrice",500,"currency",currency))));}
    @Test void insufficientRequiresKnownSameCurrencyCanonicalAmount(){
        var a=answer("BLOCKED",List.of("BUDGET_INSUFFICIENT"),List.of(),"","tool-execution:budget");
        var r=RequisitionAssessment.assess(a,current(),List.of(budget(5000),amount("CNY",1)),json).assessment();
        assertEquals(Set.of("BUDGET_INSUFFICIENT"),r.get("validatedBlockers"));assertEquals(new BigDecimal("10000"),r.get("requiredAmount"));
        for(var extra:List.of(List.<ToolExecutionRecord>of(),List.of(amount("USD",1)),List.of(amount("CNY",0)))){
            var reads=new ArrayList<>(List.of(budget(5000)));reads.addAll(extra);
            assertEquals(Set.of(),RequisitionAssessment.assess(a,current(),reads,json).assessment().get("validatedBlockers"));
        }
    }
    @Test void budgetCeilingIsNotRequiredAmount(){
        var r=assess(List.of("BUDGET_INSUFFICIENT"),List.of(item(true),budget(5000)));
        assertEquals(false,r.get("requiredAmountKnown"));assertEquals(Set.of(),r.get("validatedBlockers"));
    }
    List<ToolExecutionRecord> duplicateReads(int otherQty){return List.of(item(true),erp("subject","get_requisition",Map.of("record",req("request-1",20))),erp("list","list_requisitions",Map.of("records",List.of(req("request-1",20),req("request-2",otherQty)))));}
    @Test void confirmedDuplicateHasCanonicalWitness(){
        var r=RequisitionAssessment.validate(answer("BLOCKED",List.of("DUPLICATE_REQUEST"),List.of(),"request-1"),state,duplicateReads(20),json);
        assertEquals(Set.of("DUPLICATE_REQUEST"),r.get("validatedBlockers"));assertEquals(List.of("request-2"),r.get("duplicateWitnessIds"));
    }
    @Test void similarAndNoWitnessAreRejected(){
        var r=RequisitionAssessment.validate(answer("BLOCKED",List.of("DUPLICATE_REQUEST"),List.of(),"request-1"),state,duplicateReads(8),json);
        assertEquals(Set.of(),r.get("validatedBlockers"));assertEquals(1,r.get("rejectedCandidateCount"));
        assertEquals(Set.of(),assess(List.of("DUPLICATE_REQUEST"),List.of(item(true))).get("validatedBlockers"));
    }
    @Test void noCaseDoesNotDetermineDuplicateTruth(){
        var a=answer("BLOCKED",List.of("DUPLICATE_REQUEST"),List.of(),"request-1");
        assertEquals(Set.of("DUPLICATE_REQUEST"),RequisitionAssessment.validate(a,null,duplicateReads(20),json).get("validatedBlockers"));
        assertEquals(Set.of(),RequisitionAssessment.validate(a,null,duplicateReads(8),json).get("validatedBlockers"));
        var unknown=RequisitionAssessment.validate(answer("BLOCKED",List.of("DUPLICATE_REQUEST"),List.of(),""),null,List.of(item(true)),json);
        assertEquals("UNKNOWN",unknown.get("duplicateStatus"));assertEquals("NEEDS_CLARIFICATION",unknown.get("status"));
    }
    @Test void selfOtherDepartmentsAndClosedRequestsDoNotCount(){
        for(var other:List.of(req("request-1",20),new Requisition("other","department-2","item-1",20,LocalDate.now(),"Draft",source("other")),new Requisition("other","department-1","item-1",20,LocalDate.now(),"Cancelled",source("other")))){
            var reads=List.of(item(true),erp("subject","get_requisition",Map.of("record",req("request-1",20))),erp("list","list_requisitions",Map.of("records",List.of(other))));
            assertEquals(Set.of(),RequisitionAssessment.validate(answer("BLOCKED",List.of("DUPLICATE_REQUEST"),List.of(),"request-1"),state,reads,json).get("validatedBlockers"));
        }
    }
    @Test void unknownEnumsMissingFieldsAndForgedCandidateRefsRemainHardFailures(){
        for(String ref:List.of("fake","tool-execution:failed","tool-execution:other-child"))assertThrows(IllegalArgumentException.class,()->RequisitionAssessment.validate(answer("BLOCKED",List.of("BUDGET_UNAVAILABLE"),List.of(),"",ref),state,List.of(item(true)),json));
        assertThrows(IllegalArgumentException.class,()->assess(List.of("UNKNOWN_ENUM"),List.of(item(true))));
        assertThrows(IllegalArgumentException.class,()->RequisitionAssessment.validate(json.valueToTree(Map.of("status","BLOCKED")),state,List.of(),json));
        assertThrows(IllegalArgumentException.class,()->RequisitionAssessment.validate(answer("BLOCKED",List.of(),List.of("UNKNOWN"),""),state,List.of(),json));
    }
    @Test void rejectedCandidatesCarryAuditWitnessButNotSupervisorRawClaims(){
        var validation=RequisitionAssessment.assess(answer("BLOCKED",List.of("BUDGET_UNAVAILABLE"),List.of(),""),current(),List.of(item(true),budget(5000)),json);
        var audit=json.valueToTree(validation.audit());assertEquals("BUDGET_UNAVAILABLE",audit.path("rejectedCandidates").get(0).path("type").asText());
        assertEquals(5000,audit.path("canonicalWitness").path("budgetRecords").get(0).path("available").asInt());
        assertFalse(validation.assessment().containsKey("rejectedCandidates"));
        var raw=new ToolCallResult("analyze_requisition",true,json.writeValueAsString(validation.assessment()),"",Map.of("_domainValidationAudit",validation.audit()));
        var projected=new ToolResultProjector(new AgentProperties()).projectForContext("call",raw);
        assertFalse(projected.metadata().containsKey("_domainValidationAudit"));assertFalse(projected.content().contains("blockerCandidates"));
    }
    @Test void authoritativeBlockerDoesNotDependOnLlmStatusOrOmission(){
        var r=RequisitionAssessment.validate(answer("READY",List.of(),List.of(),""),state,List.of(item(false)),json);
        assertEquals("BLOCKED",r.get("status"));assertEquals(Set.of("ITEM_INACTIVE"),r.get("validatedBlockers"));
    }
    @Test void subjectMustHaveCanonicalExactRead(){
        assertThrows(IllegalArgumentException.class,()->RequisitionAssessment.validate(answer("BLOCKED",List.of(),List.of(),"invented"),state,List.of(item(true)),json));
    }
    @Test void adapterReturnsAuthoritativeStatusAndKeepsRejectionsOutOfModelContext() throws Exception {
        var f=new ProcurementMultiAgentTests().new Fixture();f.statusOnly=true;var root=f.start();
        String call="candidate-call",child=UUID.nameUUIDFromBytes((root.runId()+":"+call).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var packet=json.writeValueAsString(Map.of("businessRefs",Map.of("departmentId","department-1"),"priorEvidenceRefs",List.of()));
        var request=new com.agent.platform.agent.AgentRequest("conversation:domain:"+child,"buyer",packet,Map.of("tenantId","tenant",ChildRunLaunch.PARENT,root.runId(),ChildRunLaunch.ROOT,root.runId(),ChildRunLaunch.CALL,call));
        f.runs.create(AgentRunRecord.create(child,"trace","conversation:domain:"+child,request,f.registry.get(AgentRegistry.REQUISITION).profile(),null));
        for(var receipt:List.of(item(true),budget(5000)))f.executions.records.put(receipt.toolCallId(),ToolExecutionRecord.running(child,receipt.request()).withResult(ToolExecutionState.SUCCEEDED,receipt.result(),""));
        var proposed=(tools.jackson.databind.node.ObjectNode)answer("BLOCKED",List.of("BUDGET_UNAVAILABLE"),List.of(),"");
        proposed.set("findings",json.valueToTree(List.of("UNSUPPORTED_MODEL_BLOCKER_SENTINEL")));proposed.set("warnings",json.valueToTree(List.of()));proposed.set("evidenceRefs",json.valueToTree(List.of("tool-execution:item","tool-execution:budget")));
        var childResult=new AgentRuntimeResult(child,"conversation:domain:"+child,AgentRunState.COMPLETED,AgentStopReason.COMPLETED,json.writeValueAsString(proposed),"",null,List.of());
        var executor=new SubAgentExecutor(org.mockito.Mockito.mock(AgentRuntime.class),f.runs,f.executions,f.cases,f.registry,json);
        var method=SubAgentExecutor.class.getDeclaredMethod("result",ToolCallRequest.class,ToolExecutionContext.class,String.class,AgentRuntimeResult.class);method.setAccessible(true);
        var result=(ToolCallResult)method.invoke(executor,new ToolCallRequest(AgentRegistry.ANALYZE,call,Map.of("objective","analyze")),new ToolExecutionContext(root.runId(),"conversation","buyer","tenant",Set.of("USER"),Map.of()),AgentRegistry.REQUISITION,childResult);
        assertTrue(result.success(),result.errorMessage());var content=json.readTree(result.content());
        assertEquals("READY",content.path("status").asText());assertTrue(content.path("assessment").path("validatedBlockers").isEmpty());
        assertEquals(1,content.path("assessment").path("rejectedCandidateCount").asInt());
        assertFalse(result.content().contains("UNSUPPORTED_MODEL_BLOCKER_SENTINEL"));assertFalse(result.content().contains("BUDGET_UNAVAILABLE"));
        assertTrue(json.writeValueAsString(result.metadata()).contains("BUDGET_UNAVAILABLE"));
        assertFalse(new ToolResultProjector(new AgentProperties()).projectForContext(call,result).metadata().containsKey("_domainValidationAudit"));
    }
}
