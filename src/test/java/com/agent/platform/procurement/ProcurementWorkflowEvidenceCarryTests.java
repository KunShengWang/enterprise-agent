package com.agent.platform.procurement;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.procurement.erp.ErpRfqApprovalGuard;
import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.procurement.workflow.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementWorkflowEvidenceCarryTests {
    private ProcurementWorkflowTests.Fixture ready(){var f=new ProcurementWorkflowTests.Fixture();assertEquals(AgentRunState.WAITING_APPROVAL,f.run().state());return f;}
    private WorkflowEvidenceBundle bundle(ProcurementWorkflowTests.Fixture f){return f.json.convertValue(f.runs.find("run3").orElseThrow().request().metadata().get(WorkflowEvidenceCarry.BUNDLE),WorkflowEvidenceBundle.class);}
    private void metadata(ProcurementWorkflowTests.Fixture f,String run,String key,Object value){f.runs.update(run,r->{var m=new LinkedHashMap<>(r.request().metadata());if(value==null)m.remove(key);else m.put(key,value);return r.withRequest(new AgentRequest(r.conversationId(),r.userId(),r.request().question(),m));});}
    private void rejected(ProcurementWorkflowTests.Fixture f){assertThrows(RuntimeException.class,()->f.binding.find("run3"));assertEquals(0,f.writes);}

    @Test void upstreamRefsPassWithEmptyCurrentLedgerAndNoSourcingRequery(){
        var f=new ProcurementWorkflowTests.Fixture();f.rfqRefs=List.of(f.offerRef,"tool-execution:run1-budget");
        assertEquals(AgentRunState.WAITING_APPROVAL,f.run().state());assertTrue(f.executions.findByRun("run3").isEmpty());
        var scopes=new WorkflowEvidence(List.of(),f.json).require(f.json.valueToTree(f.rfqRefs),bundle(f));
        assertTrue(scopes.values().stream().allMatch(s->s==WorkflowEvidenceBundle.Scope.WORKFLOW_CARRIED));
        assertEquals(Set.of("mcp.erpnext.procurement_get_item","mcp.erpnext.procurement_get_requisition"),f.profiles.get(ProcurementStage.RFQ).toolScope());
        assertEquals(0,f.writes);
    }
    @Test void currentStageSuccessfulEvidenceStillPasses(){var f=ready();f.receipt("run3","read","read",Map.of("evidenceId","local"));var scopes=new WorkflowEvidence(f.executions.findByRun("run3"),f.json).require(f.json.valueToTree(List.of("local")),bundle(f));assertEquals(WorkflowEvidenceBundle.Scope.CURRENT_STAGE,scopes.get("local"));}
    @Test void currentFailedReceiptStillRejected(){var f=ready();f.receipt("run3","read","read",Map.of("evidenceId","local"));f.executions.markFailed("run3-read",new ToolCallResult("read",false,"{\"evidenceId\":\"local\"}","failed",Map.of()));assertThrows(IllegalArgumentException.class,()->new WorkflowEvidence(f.executions.findByRun("run3"),f.json).require(f.json.valueToTree(List.of("local")),bundle(f)));}
    @Test void globallyExistingUncarriedRefRejected(){var f=new ProcurementWorkflowTests.Fixture();f.beforeRfq=x->x.receipt("foreign","read","read",Map.of("evidenceId","global"));f.rfqRefs=List.of("global");assertEquals(AgentRunState.FAILED,f.run().state());assertTrue(f.approvalStore.recent(10).isEmpty());assertEquals(0,f.writes);}
    @Test void sourceCaseTamperingRejected(){var f=ready();var grant=new LinkedHashMap<>(f.json.convertValue(f.runs.find("run1").orElseThrow().request().metadata().get(WorkflowEvidenceCarry.VALIDATED),Map.class));grant.put("caseId","other-case");metadata(f,"run1",WorkflowEvidenceCarry.VALIDATED,grant);rejected(f);}
    @Test void targetOtherCaseRejected(){var f=ready();metadata(f,"run3","workflowCaseId","other-case");rejected(f);}
    @Test void otherWorkflowRejected(){var f=ready();metadata(f,"run3","workflowId","other-workflow");rejected(f);}
    @Test void staleCurrentCaseRejected(){var f=ready();var c=f.current();f.cases.saveIfVersion(new ProcurementCase(c.caseId(),c.tenantId(),c.conversationId(),c.userId(),c.status(),c.state(),c.createdAt(),Instant.now(),c.version()+1,"changed"),c.version());rejected(f);}
    @Test void sourceStageFailureRevokesCarry(){var f=ready();f.runs.update("run1",r->r.finished(AgentRunState.FAILED,AgentRunPhase.FAILED,"failed","error",r.toolResults(),r.usedTools(),false,false,r.budgetSnapshot()));rejected(f);}
    @Test void failedFinalizeRevokesCarry(){var f=ready();f.executions.markFailed("run2:workflow-finalize",new ToolCallResult(ProcurementToolCatalog.RECOMMENDATION_FINALIZE,false,"{}","revoked",Map.of()));rejected(f);}
    @Test void sourceReceiptRevocationRejects(){var f=ready();f.executions.markManualReview("run1-budget","revoked");rejected(f);}
    @Test void missingSourceReceiptRejects(){var f=ready();var missing=org.mockito.Mockito.mock(ToolExecutionStore.class);org.mockito.Mockito.when(missing.findToolExecution(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());assertThrows(RuntimeException.class,()->WorkflowEvidenceCarry.authorize(f.runs.find("run3").orElseThrow(),f.executions.findToolExecution("run2:workflow-finalize").orElseThrow(),f.current(),f.runs,missing,f.json));}
    @Test void supersededRecommendationRejected(){var f=ready();metadata(f,"run3","workflowFinalizeRef","new-finalize");rejected(f);}
    @Test void revokedSourceAuthorizationRejected(){var f=ready();metadata(f,"run2",WorkflowEvidenceCarry.GRANT,null);rejected(f);}
    @Test void modelCannotReplaceRecommendationReference(){var f=new ProcurementWorkflowTests.Fixture();f.rfqRecommendationOverride="other-finalize";assertEquals(AgentRunState.FAILED,f.run().state());assertEquals(0,f.writes);}
    @Test void targetCannotForgeBundle(){var f=ready();var map=f.json.convertValue(bundle(f),Map.class);map.put("recommendationVersion",999);metadata(f,"run3",WorkflowEvidenceCarry.BUNDLE,map);rejected(f);}
    @Test void finalizedPayloadTamperingRejectedEvenIfRefStillExists(){var f=ready();var receipt=f.executions.findToolExecution("run2:workflow-finalize").orElseThrow();String altered=receipt.result().content().replace("Supplier","Altered");f.executions.markSucceeded(receipt.toolCallId(),new ToolCallResult(receipt.toolName(),true,altered,"",Map.of()));rejected(f);}
    @Test void sourceValidationTamperingRejected(){var f=ready();metadata(f,"run2","validatedStageOutput",Map.of("evidenceRefs",List.of("fake")));rejected(f);}
    @Test void manifestAgreesWithCanonicalRecommendationAndExcludesUnvalidatedRefs(){
        var f=ready();var receipt=f.executions.findToolExecution("run2:workflow-finalize").orElseThrow();var c=f.json.readTree(receipt.result().content());
        var manifest=RecommendationEvidenceManifest.readAndVerify(receipt.toolCallId(),c,f.json);
        assertEquals(List.of(f.offerRef),manifest.validatedEvidenceRefs());assertEquals("SUP",manifest.selectedSupplierId());
        assertEquals(f.current().version(),manifest.caseVersion());assertEquals(manifest.caseVersion(),manifest.recommendationVersion());
        assertEquals(receipt.toolCallId(),manifest.recommendationId());
        ((tools.jackson.databind.node.ObjectNode)c.path("recommendation")).set("evidenceRefs",f.json.valueToTree(List.of("unknown")));
        assertThrows(IllegalArgumentException.class,()->RecommendationEvidenceManifest.create(receipt.toolCallId(),c,Instant.now()));
    }
    @Test void contextCarriesProjectionAndRefsWithoutRawReceiptBodies(){var f=ready();var packet=f.json.readTree(f.runs.find("run3").orElseThrow().request().question());assertFalse(packet.path("validatedPriorStage").path("finalizedRecommendation").has("evidence"));assertFalse(packet.toString().contains("receiptSha256"));assertFalse(packet.toString().contains("sourceSnapshot"));assertTrue(packet.path("validatedPriorStage").has("recommendationEvidenceManifest"));}
    @Test void auditIncludesOriginOutcomeValidationAndAuthorization(){var f=ready();for(var e:bundle(f).entries()){assertFalse(e.originToolCallId().isBlank());assertFalse(e.originRunId().isBlank());assertEquals("SUCCEEDED",e.originOutcome());assertEquals("PASSED",e.validationStatus());assertEquals(64,e.receiptSha256().length());assertFalse(e.authorizationSource().isBlank());}assertEquals("RFQ",bundle(f).targetStage());}
    @Test void carryRevokedAfterApprovalCannotWrite(){var f=ready();var r=f.runs.find("run3").orElseThrow();f.approvals.decide(r.approvalId(),true,"reviewer","yes");metadata(f,"run2",WorkflowEvidenceCarry.GRANT,null);assertEquals(AgentRunState.FAILED,f.workflow.resume("run3",AgentEventListener.NOOP).state());assertEquals(0,f.writes);}
    @Test void approvalPayloadAndErpIdempotencyKeyBindRecommendationAndVersion(){
        var f=ready();var run=f.runs.find("run3").orElseThrow();var a=f.approvalStore.find(run.approvalId()).orElseThrow();var args=a.toolCallRequest().arguments();
        assertEquals("run2:workflow-finalize",args.get("sourceRecommendationToolCallId"));assertEquals(f.current().version(),args.get("caseVersion"));
        String hash=WorkflowEvidenceCarry.hash(f.current().caseId()+"\n"+f.current().version()+"\nrun2:workflow-finalize\n");
        assertEquals("rfq:"+a.approvalId()+":"+hash,args.get("idempotencyKey"));
        assertNotEquals(hash,WorkflowEvidenceCarry.hash(f.current().caseId()+"\n"+(f.current().version()+1)+"\nrun2:workflow-finalize\n"));
        var prep=new ProcurementRfqApprovalPreparer(f.executions,f.cases,f.json);prep.setWorkflowBinding(f.binding);
        var guard=new ErpRfqApprovalGuard(f.approvalStore,f.runs,f.executions,prep,f.json);
        var req=new ProcurementRfqGateway.CreateRequest((String)args.get("idempotencyKey"),"SUP","Workstation","SKU",2,"CNY",10,Map.of());
        assertThrows(ProcurementRfqGateway.RejectedRequestException.class,()->guard.requireApproved(req));
        f.approvals.decide(a.approvalId(),true,"reviewer","yes");f.executions.claim("run3",a.toolCallRequest());
        assertDoesNotThrow(()->guard.requireApproved(req));
        var tampered=new ProcurementRfqGateway.CreateRequest("rfq:"+a.approvalId()+":"+"0".repeat(64),"SUP","Workstation","SKU",2,"CNY",10,Map.of());
        assertThrows(ProcurementRfqGateway.RejectedRequestException.class,()->guard.requireApproved(tampered));
        assertEquals(0,f.writes);
    }
}
