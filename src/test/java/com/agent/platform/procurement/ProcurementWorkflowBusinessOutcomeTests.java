package com.agent.platform.procurement;

import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.erp.*;
import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.provider.ProcurementDataProvider;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.runtime.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.agent.platform.procurement.application.ProcurementRecommendationFinalizer.*;

/** Real canonical Finalizer + real Workflow/CAS/approval stores, without a model or ERP network. */
class ProcurementWorkflowBusinessOutcomeTests {
    final ProcurementWorkflowTests.Fixture f=new ProcurementWorkflowTests.Fixture();
    final ProcurementDataProvider provider=mock(ProcurementDataProvider.class);
    final ProcurementRecommendationFinalizer canonical=new ProcurementRecommendationFinalizer(f.cases,provider,new ProcurementDecisionEngine());

    ProcurementWorkflowBusinessOutcomeTests(){
        when(provider.searchSuppliers(any())).thenReturn(List.of(new SupplierCandidate("SUP","Supplier","test")));
        when(provider.getSupplierOffers(any(),anyList())).thenReturn(List.of());
        when(provider.getSupplierEvidence(anyString(),any())).thenReturn(List.of());
        doAnswer(i->canonical.assess(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4)))
                .when(f.finalizer).assess(anyString(),anyString(),anyString(),any(),anySet());
        f.sourcingOutput=output->{
            var copy=new LinkedHashMap<>(output);var proposal=new LinkedHashMap<>((Map<String,Object>)output.get("recommendation"));
            proposal.put("evidenceRefs",List.of("tool-execution:run2-search"));copy.put("recommendation",proposal);return copy;
        };
    }
    SupplierOffer offer(String id,BigDecimal price){return new SupplierOffer(id,"SKU","Item",price,"CNY",2,null,5,"",Map.of(),"test",f.evidenceTime,"record","snapshot",f.evidenceTime,"digest");}
    void proposal(String key,Object value){
        var prior=f.sourcingOutput;f.sourcingOutput=output->{var copy=new LinkedHashMap<>(prior.apply(output));
            var p=new LinkedHashMap<>((Map<String,Object>)copy.get("recommendation"));p.put(key,value);copy.put("recommendation",p);return copy;};
    }
    void noWrite(){assertEquals(2,f.stages.size());assertEquals(0,f.writes);
        assertTrue(f.executions.findByRun("run2").stream().noneMatch(e->e.toolName().equals(ProcurementToolCatalog.RECOMMENDATION_FINALIZE)||e.toolName().equals(ProcurementToolCatalog.CREATE_RFQ)));}
    void invalid(){var result=f.run();assertEquals(AgentRunState.FAILED,result.state(),result.answer());assertEquals(AgentStopReason.TOOL_ERROR,result.stopReason());
        assertTrue(result.answer().contains("WORKFLOW_ERROR"));assertEquals(ProcurementCaseStatus.SOURCING,f.current().status());noWrite();}

    @Test void noOffersKnownProposalCompletesBlockedWithoutToolErrorAndKeepsTrace(){
        var events=new ArrayList<AgentEvent>();f.workflow.setTimeline(new ProcurementLiveEvalRuntimeHarness.InMemoryTimelineStore());
        var result=f.workflow.execute(f.input(),events::add);
        assertEquals(AgentRunState.COMPLETED,result.state(),result.answer());assertEquals(AgentStopReason.COMPLETED,result.stopReason());
        assertEquals(ProcurementCaseStatus.NO_ELIGIBLE_SUPPLIER,f.current().status());noWrite();
        var md=f.runs.find("run2").orElseThrow().request().metadata();
        assertEquals("RECOMMENDATION",f.json.valueToTree(md.get("validatedStageOutput")).path("status").asText());
        var decision=f.json.valueToTree(md.get("workflowFinalization"));
        assertEquals("REJECTED",decision.path("status").asText());assertEquals("NO_ELIGIBLE_SUPPLIER",decision.path("reason").asText());
        assertEquals("SUP",decision.path("proposal").path("selectedSupplierId").asText());assertEquals("SUCCESS",decision.path("executionOutcome").asText());
        assertTrue(f.executions.findByRun("run2").stream().allMatch(e->e.state()==ToolExecutionState.SUCCEEDED));
        assertEquals(result.answer(),events.get(events.size()-1).content());
        verify(f.finalizer,times(1)).assess(anyString(),anyString(),anyString(),any(),anySet());
    }
    @Test void validRecommendationStillFinalizesAndCanReachApproval(){
        when(provider.getSupplierOffers(any(),anyList())).thenReturn(List.of(offer("SUP",BigDecimal.TEN)));
        when(provider.getSupplierEvidence(eq("SUP"),any())).thenReturn(List.of(f.offerEvidence));proposal("evidenceRefs",List.of(f.offerRef));
        var result=f.run();assertEquals(AgentRunState.WAITING_APPROVAL,result.state(),result.answer());
        assertEquals(ProcurementCaseStatus.RECOMMENDATION_READY,f.current().status());assertEquals(3,f.stages.size());assertEquals(0,f.writes);
        assertEquals("FINALIZED",f.json.valueToTree(f.runs.find("run2").orElseThrow().request().metadata().get("workflowFinalization")).path("status").asText());
    }
    @Test void allOffersFailConstraintsIsExpectedBlocked(){
        when(provider.getSupplierOffers(any(),anyList())).thenReturn(List.of(offer("SUP",new BigDecimal("2000"))));
        var result=f.run();assertEquals(AgentRunState.COMPLETED,result.state(),result.answer());assertEquals(ProcurementCaseStatus.NO_ELIGIBLE_SUPPLIER,f.current().status());noWrite();
    }
    @Test void ineligibleSelectionWithAlternativeRequiresNewInputWithoutAutomaticRetry(){
        when(provider.searchSuppliers(any())).thenReturn(List.of(new SupplierCandidate("SUP","Supplier","test"),new SupplierCandidate("ALT","Alternative","test")));
        when(provider.getSupplierOffers(any(),anyList())).thenReturn(List.of(offer("SUP",new BigDecimal("2000")),offer("ALT",BigDecimal.TEN)));
        var result=f.run();assertEquals(AgentRunState.COMPLETED,result.state(),result.answer());assertEquals(AgentStopReason.COMPLETED,result.stopReason());
        assertEquals(ProcurementCaseStatus.CLARIFICATION_REQUIRED,f.current().status());assertTrue(result.answer().contains("SELECTED_SUPPLIER_INELIGIBLE"));noWrite();
    }
    @Test void knownSelectionWithoutOfferWithAlternativeIsTypedRejection(){
        when(provider.searchSuppliers(any())).thenReturn(List.of(new SupplierCandidate("SUP","Supplier","test"),new SupplierCandidate("ALT","Alternative","test")));
        when(provider.getSupplierOffers(any(),anyList())).thenReturn(List.of(offer("ALT",BigDecimal.TEN)));
        var result=f.run();assertEquals(AgentRunState.COMPLETED,result.state(),result.answer());assertTrue(result.answer().contains("NO_MATCHING_OFFER"));noWrite();
    }
    @Test void unknownSupplierCannotHideBehindEmptyEligibility(){proposal("selectedSupplierId","UNKNOWN");invalid();}
    @Test void forgedEvidenceCannotHideBehindEmptyEligibility(){proposal("evidenceRefs",List.of("forged"));invalid();verifyNoInteractions(provider);}
    @Test void finalizerIndependentlyRejectsUntrustedRefs(){
        f.run();var draft=new ProcurementRecommendationDraft(f.current().version(),"SUP",List.of("forged"),List.of(),.9);
        assertThrows(IllegalArgumentException.class,()->canonical.assess("t","u","s",draft,Set.of()));
    }
    @Test void staleProposalCannotHideBehindEmptyEligibility(){proposal("evaluatedCaseVersion",0);invalid();verifyNoInteractions(provider);}
    @Test void canonicalIdTamperingIsStillSchemaFailure(){proposal("caseId","fake");assertEquals(AgentStopReason.STRUCTURED_OUTPUT_INVALID,f.run().stopReason());noWrite();verifyNoInteractions(provider);}
    @Test void malformedDraftIsNotBusinessRejection(){proposal("confidence",2.0);assertEquals(AgentStopReason.STRUCTURED_OUTPUT_INVALID,f.run().stopReason());noWrite();verifyNoInteractions(provider);}
    @Test void infrastructureFailureRemainsWorkflowError(){when(provider.searchSuppliers(any())).thenThrow(new IllegalStateException("ERP unavailable"));invalid();}
    @Test void corruptOfferSpecificationCannotBecomeExpectedBusinessRejection(){
        f.run();var c=f.current();var s=c.state();
        var constrained=new ProcurementCaseState(s.productCategory(),s.productDescription(),s.quantity(),s.budget(),s.currency(),s.requiredDeliveryDays(),
                Map.of("gpuMemoryMinGb","24"),Map.of(),Set.of(),List.of(),"SOURCING");
        var updated=new ProcurementCase(c.caseId(),c.tenantId(),c.conversationId(),c.userId(),ProcurementCaseStatus.SOURCING,constrained,c.createdAt(),c.updatedAt(),c.version()+1,"constraint");
        assertTrue(f.cases.saveIfVersion(updated,c.version()));
        var corrupt=new SupplierOffer("SUP","SKU","Item",BigDecimal.TEN,"CNY",2,null,5,"",Map.of("gpuMemoryGb","not-a-number"),"test",f.evidenceTime,"record","snapshot",f.evidenceTime,"digest");
        when(provider.getSupplierOffers(any(),anyList())).thenReturn(List.of(corrupt));
        var draft=new ProcurementRecommendationDraft(updated.version(),"SUP",List.of(f.offerRef),List.of(),.9);
        assertThrows(IllegalStateException.class,()->canonical.assess("t","u","s",draft,Set.of(f.offerRef)));
    }
    @Test void stageReceiptCannotReplaceOfferEvidenceForValidRecommendation(){
        when(provider.getSupplierOffers(any(),anyList())).thenReturn(List.of(offer("SUP",BigDecimal.TEN)));
        when(provider.getSupplierEvidence(eq("SUP"),any())).thenReturn(List.of(f.offerEvidence));invalid();
    }
    @Test void inactiveSupplierIsOutsideCurrentApprovedDiscoveryContract(){
        var adapter=mock(ProcurementErpAdapter.class);var source=new ProcurementErpAdapter.Source("test","Item","SKU",f.evidenceTime,f.evidenceTime);
        when(adapter.getItem("SKU")).thenReturn(Optional.of(new ProcurementErpAdapter.Item("SKU","Item","Workstation","Nos",24,"",source)));
        when(adapter.listItemSuppliers("SKU",0,200)).thenReturn(new ProcurementErpAdapter.Page<>(List.of(new ProcurementErpAdapter.ItemSupplier("relation","SKU","SUP",BigDecimal.TEN,"CNY",5,10,source)),null));
        when(adapter.getSupplier("SUP")).thenReturn(Optional.of(new ProcurementErpAdapter.Supplier("SUP","Inactive",false,"HIGH",source)));
        var service=new ProcurementMcpService(adapter,null,"V2-ALG");
        var snapshot=f.json.valueToTree(service.call("search_suppliers",f.json.valueToTree(Map.of("productDescription","SKU","productCategory","Workstation"))));
        assertTrue(snapshot.path("suppliers").isArray());assertTrue(snapshot.path("suppliers").isEmpty());
        when(provider.searchSuppliers(any())).thenReturn(List.of());invalid();
    }
}
