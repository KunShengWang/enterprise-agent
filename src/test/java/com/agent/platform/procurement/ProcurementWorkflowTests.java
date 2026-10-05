package com.agent.platform.procurement;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.*;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.*;
import com.agent.platform.memory.RuleBasedConversationSummarizer;
import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.provider.ProcurementDataProvider;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.procurement.workflow.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.agent.platform.procurement.ProcurementLiveEvalRuntimeHarness.*;

class ProcurementWorkflowTests {
    @Test void profilesUseSameIdentityAndStableReadOnlyTools(){
        var f=new Fixture();for(var stage:ProcurementStage.values()){
            var p=f.profiles.get(stage);assertEquals("PROCUREMENT",p.executionProfile().name());assertTrue(p.contextPolicy().isolatedRun());
            assertFalse(p.toolScope().contains(ProcurementToolCatalog.CREATE_RFQ));assertFalse(p.toolScope().contains(ProcurementToolCatalog.CASE_PATCH));
            assertEquals(p.toolScope(),f.profiles.get(stage).toolScope());
        }
        assertFalse(f.profiles.get(ProcurementStage.REQUISITION).toolScope().stream().anyMatch(n->n.contains("supplier_performance")));
    }
    @Test void legalStagesAndApprovalPause(){var f=new Fixture();var r=f.run();assertEquals(AgentRunState.WAITING_APPROVAL,r.state(),r.answer());assertEquals(List.of("REQUISITION","SOURCING","RFQ"),f.stages);assertEquals(0,f.writes);assertEquals(ProcurementCaseStatus.RECOMMENDATION_READY,f.current().status());}
    @Test void analyzeDoesNotCreateRfq(){var f=new Fixture();f.intent="ANALYZE";assertEquals(AgentRunState.COMPLETED,f.run().state());assertEquals(List.of("REQUISITION","SOURCING"),f.stages);assertEquals(0,f.writes);}
    @Test void missingQuantityPauses(){var f=new Fixture();f.quantity=false;assertTrue(f.run().answer().contains("NEEDS_USER_INPUT"));assertEquals(List.of("REQUISITION"),f.stages);}
    @Test void canonicalInactiveStopsSourcing(){var f=new Fixture();f.active=false;var r=f.run();assertTrue(r.answer().contains("NEEDS_USER_INPUT"));assertTrue(r.answer().contains("ITEM_INACTIVE"));assertEquals(1,f.stages.size());}
    @Test void unsupportedCandidatesDoNotBlock(){var f=new Fixture();f.unsupported=true;f.intent="ANALYZE";var r=f.run();assertEquals(AgentRunState.COMPLETED,r.state(),r.answer());assertEquals(2,f.stages.size());}
    @Test void noEligibleCannotReachRfq(){var f=new Fixture();f.noEligible=true;var r=f.run();assertEquals(AgentRunState.COMPLETED,r.state(),r.answer());assertEquals(ProcurementCaseStatus.NO_ELIGIBLE_SUPPLIER,f.current().status());assertEquals(2,f.stages.size());}
    @Test void failedFinalizeCannotReachRfq(){var f=new Fixture();f.failFinalize=true;assertEquals(AgentRunState.FAILED,f.run().state());assertEquals(2,f.stages.size());assertEquals(ProcurementCaseStatus.SOURCING,f.current().status());}
    @Test void schemaUnknownFieldHardFails(){var f=new Fixture();f.extraField=true;assertEquals(AgentRunState.FAILED,f.run().state());assertNull(f.current());}
    @Test void forgedEvidenceHardFails(){var f=new Fixture();f.fakeEvidence=true;assertEquals(AgentRunState.FAILED,f.run().state());assertNull(f.current());}
    @Test void approvalResumeIsIdempotent(){var f=new Fixture();var r=f.run();f.approvals.decide(r.approvalId(),true,"reviewer","yes");var resumed=f.workflow.resume(r.runId(),AgentEventListener.NOOP);assertEquals(AgentRunState.COMPLETED,resumed.state(),resumed.answer());f.workflow.resume(r.runId(),AgentEventListener.NOOP);assertEquals(1,f.writes);}
    @Test void unapprovedRemainsWaiting(){var f=new Fixture();var r=f.run();assertEquals(AgentRunState.WAITING_APPROVAL,f.workflow.resume(r.runId(),AgentEventListener.NOOP).state());assertEquals(0,f.writes);}
    @Test void staleCaseRejectsApprovedWrite(){var f=new Fixture();var r=f.run();var c=f.current();f.cases.saveIfVersion(new ProcurementCase(c.caseId(),c.tenantId(),c.conversationId(),c.userId(),c.status(),c.state(),c.createdAt(),Instant.now(),c.version()+1,"external"),c.version());f.approvals.decide(r.approvalId(),true,"reviewer","yes");assertEquals(AgentRunState.FAILED,f.workflow.resume(r.runId(),AgentEventListener.NOOP).state());assertEquals(0,f.writes);}
    @Test void crossStageBindingRejectsTenantTampering(){var f=new Fixture();var r=f.run();f.runs.update(r.runId(),v->{var m=new HashMap<>(v.request().metadata());m.put("tenantId","other");return v.withRequest(new AgentRequest("s","u","x",m));});assertThrows(IllegalArgumentException.class,()->f.binding.find(r.runId()));}
    @Test void draftCannotMapToRfq(){var f=new Fixture();assertEquals(ProcurementStage.REQUISITION,ProcurementStage.from(null));var c=new ProcurementCaseService(f.cases,new ProcurementCasePatchMerger()).ensureCase("t","s","u");assertEquals(ProcurementStage.REQUISITION,ProcurementStage.from(c));assertTrue(f.binding.find("missing").isEmpty());}
    @Test void statusQueryDoesNotRunModel(){var f=new Fixture();assertEquals(ProcurementStage.REQUISITION,f.workflow.status(f.input()).get("stage"));assertTrue(f.stages.isEmpty());}
    @Test void contextExcludesPreviousStageRawHistory(){
        var f=new Fixture();var timeline=new InMemoryTimelineStore();timeline.appendMessages("s","u","old",List.of(AgentMessageDraft.user("OLD_RAW_REASONING",4)));
        timeline.appendMessages("s","u","new",List.of(AgentMessageDraft.user("canonical packet",4)));
        var manager=new DefaultAgentContextManager(timeline,new ConservativeTokenEstimator(),new EmptyMemoryService(),new RuleBasedConversationSummarizer(),new AgentProperties());
        var view=manager.project("s","u","t","packet",10000,f.profiles.get(ProcurementStage.SOURCING).executionProfile());
        assertTrue(view.messages().stream().allMatch(m->m.runId().equals("new")));assertFalse(view.messages().stream().anyMatch(m->m.content().contains("OLD_RAW")));
    }
    @Test void recursiveSchemaRejectsCandidateEnumAndExtra(){
        var f=new Fixture();var schema=f.json.readTree(f.profiles.get(ProcurementStage.REQUISITION).outputSchema());
        var output=f.requisition("r");output.put("blockerCandidates",List.of(Map.of("type","FAKE","evidenceRefs",List.of("ref"))));
        assertThrows(StructuredOutputSpec.Invalid.class,()->new StructuredOutputSpec(RequisitionStageResult.class).parse(f.json.writeValueAsString(output)));
    }
    @Test void failedReceiptCannotBecomeEvidence(){var f=new Fixture();var request=new ToolCallRequest("x","failed",Map.of());f.executions.claim("r",request);f.executions.markFailed("failed",new ToolCallResult("x",false,"{}","failure",Map.of()));var evidence=new WorkflowEvidence(f.executions.findByRun("r"),f.json);assertThrows(IllegalArgumentException.class,()->evidence.require(f.json.valueToTree(List.of("tool-execution:failed"))));}

    @Test void proposalCannotPatchNewerCase(){
        var f=new Fixture();var service=new ProcurementCaseService(f.cases,new ProcurementCasePatchMerger());
        var c=service.ensureCase("t","s","u");var patch=f.json.convertValue(f.requisition("r").get("caseChanges"),ProcurementCasePatch.class);
        service.applyPatch("t","s","u",patch,"other",c.version());
        assertThrows(ProcurementCaseVersionConflictException.class,()->service.applyPatch("t","s","u",patch,"stale",c.version()));
    }
    @Test void compactionDoesNotImportPreviousStage(){
        var f=new Fixture();var timeline=new InMemoryTimelineStore();
        for(int i=0;i<8;i++)timeline.appendMessages("s","u","old",List.of(AgentMessageDraft.user("SECRET_OLD_STAGE ".repeat(40),1000)));
        for(int i=0;i<4;i++)timeline.appendMessages("s","u","new",List.of(AgentMessageDraft.user("fresh current facts ".repeat(40),1000)));
        var manager=new DefaultAgentContextManager(timeline,new ConservativeTokenEstimator(),new EmptyMemoryService(),new RuleBasedConversationSummarizer(),new AgentProperties());
        var view=manager.compact("s","u","t","new","packet",1000,"test",f.profiles.get(ProcurementStage.SOURCING).executionProfile());
        assertTrue(view.messages().stream().allMatch(m->m.runId().equals("new")));
        assertFalse(view.messages().stream().anyMatch(m->m.content().contains("SECRET_OLD_STAGE")));
    }
    @Test void missingDepartmentRequiresClarification(){var f=new Fixture();f.departmentMissing=true;var r=f.run();assertTrue(r.answer().contains("NEEDS_USER_INPUT"));assertEquals(1,f.stages.size());}
    @Test void incompleteRequisitionScanIsNotUserClarification(){var f=new Fixture();f.scanIncomplete=true;var r=f.run();assertEquals(AgentRunState.FAILED,r.state());assertTrue(r.answer().contains("canonical requisition scan unavailable"));assertEquals(1,f.stages.size());}
    @Test void phase4HistoryIsNotInStagePacket(){var f=new Fixture();f.run();for(var r:f.runs.recent(10)){assertTrue(r.request().metadata().containsKey("workflowCaseVersion"));assertEquals("PROCUREMENT",r.executionProfile().name());assertFalse(r.request().question().contains("assistant messages"));}}

    @Test void typedAdditionalPropertiesAcceptStringsOnly(){
        var f=new Fixture();var schema=f.json.readTree("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}");
        assertDoesNotThrow(()->StageOutputValidator.validate(schema,f.json.valueToTree(Map.of("gpuMemoryMinGb","24"))));
        assertThrows(IllegalArgumentException.class,()->StageOutputValidator.validate(schema,f.json.valueToTree(Map.of("gpuMemoryMinGb",24))));
    }

    @Test void workflowPublishesAuthoritativeFinalEvent(){
        var f=new Fixture();f.intent="ANALYZE";f.workflow.setTimeline(new InMemoryTimelineStore());
        var events=new ArrayList<AgentEvent>();var result=f.workflow.execute(f.input(),events::add);
        assertEquals(result.answer(),events.get(events.size()-1).content());
        assertEquals("COMPLETE",events.get(events.size()-1).payload().get("workflowOutcome"));
    }

    @Test void publicErpEntryUsesWorkflow(){
        var f=new Fixture();var delegate=mock(DefaultAgentRuntime.class);var entry=new ProcurementWorkflowRuntime(delegate,f.workflow,f.runs,f.json);
        org.springframework.test.util.ReflectionTestUtils.setField(entry,"enabled",true);
        var input=f.input();var request=new AgentRequest(input.conversationId(),input.userId(),input.question(),input.metadata(),"procurement-sourcing-rfq-v1");
        assertEquals(AgentRunState.WAITING_APPROVAL,entry.run(request,AgentEventListener.NOOP).state());verifyNoInteractions(delegate);
    }
    @Test void publicStatusQueryDoesNotCreateRun(){
        var f=new Fixture();var delegate=mock(DefaultAgentRuntime.class);var entry=new ProcurementWorkflowRuntime(delegate,f.workflow,f.runs,f.json);
        org.springframework.test.util.ReflectionTestUtils.setField(entry,"enabled",true);
        var input=f.input();var request=new AgentRequest(input.conversationId(),input.userId(),"现在采购到哪一步？",input.metadata(),"procurement-sourcing-rfq-v1");
        assertEquals(AgentRunState.COMPLETED,entry.run(request,AgentEventListener.NOOP).state());assertTrue(f.runs.recent(10).isEmpty());verifyNoInteractions(delegate);
    }

    @Test void oneMatchingSavedDemandIsNotItsOwnDuplicate(){var f=new Fixture();f.intent="ANALYZE";f.matchingRequisitions=1;assertEquals(AgentRunState.COMPLETED,f.run().state());assertEquals(2,f.stages.size());}
    @Test void twoDistinctMatchingDemandsBlockSourcing(){var f=new Fixture();f.matchingRequisitions=2;var r=f.run();assertTrue(r.answer().contains("DUPLICATE_REQUEST"));assertEquals(1,f.stages.size());}

    static final class Fixture {
        final ObjectMapper json=new ObjectMapper();final AgentProperties properties=new AgentProperties();
        final MemoryCaseStore cases=new MemoryCaseStore();final ProcurementV2TrialDriver.EvalRunStore runs=new ProcurementV2TrialDriver.EvalRunStore();
        final InMemoryToolExecutionStore executions=new InMemoryToolExecutionStore();final ProcurementV2TrialDriver.EvalApprovalStore approvalStore=new ProcurementV2TrialDriver.EvalApprovalStore();
        final LocalApprovalService approvals=new LocalApprovalService(approvalStore,properties);final ProcurementStageProfiles profiles=new ProcurementStageProfiles(properties,json);
        final AgentRuntime runtime=mock(AgentRuntime.class);final ProcurementRecommendationFinalizer finalizer=mock(ProcurementRecommendationFinalizer.class);
        final WorkflowFinalizeBinding binding=new WorkflowFinalizeBinding(runs,executions,cases,json);final DefaultAgentToolRuntime toolRuntime;final ProcurementWorkflowService workflow;
        final Instant evidenceTime=Instant.parse("2026-01-01T00:00:00Z");
        final String offerRef=EvidenceIdFactory.id("SUP","OFFER","test","record","snapshot",evidenceTime.toString(),"digest","offer");
        final SupplierEvidence offerEvidence=new SupplierEvidence(offerRef,"SUP","OFFER","test","offer",evidenceTime,"record","snapshot",evidenceTime,"digest");
        java.util.function.Consumer<Fixture> beforeRfq=f->{};
        java.util.function.BiConsumer<Fixture,String> itemReceipt=(f,id)->f.receipt(id,"item","mcp.erpnext.procurement_get_item",Map.of("record",Map.of("id","SKU","category","Workstation","active",f.active)));
        java.util.function.UnaryOperator<Map<String,Object>> sourcingOutput=value->value;
        List<String> rfqRefs=List.of();String rfqRecommendationOverride;String requisitionId="";
        ProcurementRfqApprovalPreparer preparer;ProcurementRfqGateway.CreateRequest writtenRequest;
        final List<String> stages=new ArrayList<>();int writes,matchingRequisitions;boolean departmentMissing,scanIncomplete,quantity=true,active=true,noEligible,failFinalize,unsupported,extraField,fakeEvidence;String intent="RFQ";
        Fixture(){this(null);}
        Fixture(java.util.function.Function<Fixture,ProcurementRfqGateway> gatewayFactory){
            var merger=new ProcurementCasePatchMerger();var handler=new ProcurementToolHandler(mock(ProcurementDataProvider.class),json,cases,new ProcurementCaseService(cases,merger),finalizer,merger,new ProcurementDecisionEngine());
            var prep=new ProcurementRfqApprovalPreparer(executions,cases,json);prep.setWorkflowBinding(binding);preparer=prep;
            var defaultGateway=mock(ProcurementRfqGateway.class);when(defaultGateway.create(any())).thenAnswer(i->{writes++;ProcurementRfqGateway.CreateRequest r=i.getArgument(0);writtenRequest=r;return new ProcurementRfqGateway.Receipt("rfq-1",r.idempotencyKey(),r.supplierId(),"CREATED",Instant.now(),"test");});
            var gateway=gatewayFactory==null?defaultGateway:gatewayFactory.apply(this);
            var rfq=new ProcurementRfqToolHandler(gateway,cases,executions,json);rfq.setWorkflowBinding(binding);
            var executor=new ContextualAgentCapabilityExecutor(){public ToolCallResult execute(ToolCallRequest r){throw new AssertionError();}public ToolCallResult execute(ToolCallRequest r,ToolExecutionContext c){return r.toolName().equals(ProcurementToolCatalog.CREATE_RFQ)?rfq.execute(r,c):handler.execute(r,c);}};
            var guards=mock(GuardrailService.class);when(guards.checkToolCall(any(),any(),any())).thenAnswer(i->((ToolDefinition)i.getArgument(0)).name().equals(ProcurementToolCatalog.CREATE_RFQ)?GuardrailDecision.requireApproval(GuardrailStage.TOOL,"HITL"):GuardrailDecision.allow(GuardrailStage.TOOL,"read"));
            toolRuntime=new DefaultAgentToolRuntime(guards,approvals,executions,executor,properties,List.of(prep),List.of(rfq));
            when(finalizer.assess(anyString(),anyString(),anyString(),any(),anySet())).thenAnswer(i->{
                if(failFinalize)throw new IllegalArgumentException("invalid Finalize");
                var offer=new SupplierOffer("SUP","SKU","Item",BigDecimal.TEN,"CNY",2,null,5,"",Map.of(),"test",Instant.now(),"record","snapshot",Instant.now(),"digest");
                return new ProcurementRecommendationFinalizer.Finalization(current(),new SourcingRecommendation(new SupplierCandidate("SUP","Supplier","test"),offer,List.of(),List.of(),List.of(),List.of(),List.of(offerRef),List.of(),0.9),null,List.of(offerEvidence));
            });
            when(runtime.run(any(),any(),any())).thenAnswer(i->{
                AgentRequest request=i.getArgument(0);AgentExecutionProfile profile=i.getArgument(1);String stage=request.metadata().get("procurementStage").toString();stages.add(stage);String id="run"+stages.size();
                var budget=new AgentRunBudget(profile.limits()).snapshot();runs.create(AgentRunRecord.create(id,id,"s",request,profile,budget));Map<String,Object> output;
                if(stage.equals("REQUISITION")){
                    var page=new LinkedHashMap<String,Object>();page.put("records",java.util.stream.IntStream.range(0,matchingRequisitions).mapToObj(n->Map.of("id","req"+n,"departmentId","V2-ALG","itemId","SKU","quantity",2,"status","Draft")).toList());page.put("nextOffset",scanIncomplete?50:null);receipt(id,"reqs","mcp.erpnext.procurement_list_requisitions",page);
                    itemReceipt.accept(this,id);
                    receipt(id,"budget","mcp.erpnext.procurement_get_budget_status",Map.of("record",Map.of("id","BUD","departmentId","V2-ALG","currency","CNY","available",1000)));
                    if(!requisitionId.isBlank())receipt(id,"subject","mcp.erpnext.procurement_get_requisition",Map.of("record",Map.of("id",requisitionId,"departmentId","V2-ALG","itemId","SKU","quantity",2,"status","Draft")));
                    output=requisition(id);
                }else if(stage.equals("SOURCING")){
                    receipt(id,"search",ProcurementToolCatalog.SUPPLIER_SEARCH,Map.of("eligibleSuppliers",noEligible?List.of():List.of(Map.of("supplierId","SUP")),"evidence",List.of(Map.of("evidenceId",offerRef))));
                    output=Map.of("status",noEligible?"NO_ELIGIBLE_SUPPLIER":"RECOMMENDATION","recommendation",noEligible?Map.of():Map.of("evaluatedCaseVersion",current().version(),"selectedSupplierId","SUP","evidenceRefs",List.of(offerRef),"tradeoffDimensions",List.of(),"confidence",0.9),"evidenceRefs",List.of(offerRef),"tradeoffs",List.of(),"riskFindings",List.of());
                    output=sourcingOutput.apply(output);
                }else {
                    beforeRfq.accept(this);
                    output=Map.of("requestRfq",true,"explanation","proposal only","recommendationRef",rfqRecommendationOverride==null?request.metadata().get("workflowFinalizeRef"):rfqRecommendationOverride,"evidenceRefs",rfqRefs);
                }
                String answer=json.writeValueAsString(output);
                try{
                    var typed=profile.structuredOutput().parse(answer);
                    runs.update(id,r->{var md=new LinkedHashMap<>(r.request().metadata());md.put(StructuredOutputSpec.RESULT,typed);md.put(StructuredOutputSpec.TYPE,profile.structuredOutput().outputType().getName());return r.withRequest(new AgentRequest("s","u",r.request().question(),md));});
                }catch(StructuredOutputSpec.Invalid ex){return new AgentRuntimeResult(id,"s",AgentRunState.FAILED,AgentStopReason.STRUCTURED_OUTPUT_INVALID,ex.code().name(),"",budget,List.of());}
                runs.update(id,r->r.finished(AgentRunState.COMPLETED,AgentRunPhase.FINISHED,answer,"",List.of(),List.of(),false,false,budget));
                return new AgentRuntimeResult(id,"s",AgentRunState.COMPLETED,AgentStopReason.COMPLETED,answer,"",budget,List.of());
            });
            when(runtime.resume(anyString(),any())).thenAnswer(i->{var r=runs.find(i.getArgument(0)).orElseThrow();var a=approvalStore.find(r.approvalId()).orElseThrow();
                if(a.status()==ApprovalStatus.REQUESTED)return new AgentRuntimeResult(r.runId(),"s",AgentRunState.WAITING_APPROVAL,AgentStopReason.WAITING_APPROVAL,"",a.approvalId(),r.budgetSnapshot(),List.of());
                var def=new ProcurementToolCatalog().definitions().stream().filter(d->d.name().equals(ProcurementToolCatalog.CREATE_RFQ)).findFirst().orElseThrow();
                var result=toolRuntime.executeApproved(a,def,ToolPolicyContext.from(r.runId(),"s","u",r.request().metadata()));
                return new AgentRuntimeResult(r.runId(),"s",result.result().success()?AgentRunState.COMPLETED:AgentRunState.FAILED,AgentStopReason.COMPLETED,"",a.approvalId(),r.budgetSnapshot(),List.of());});
            workflow=new ProcurementWorkflowService(runtime,runs,executions,toolRuntime,cases,profiles,finalizer,json);
        }
        Map<String,Object> requisition(String id){
            var patch=new LinkedHashMap<String,Object>(Map.of("productCategory","Workstation","productDescription","SKU","budget",1000,"requiredDeliveryDays",10,"currency","CNY"));if(quantity)patch.put("quantity",2);
            var output=new LinkedHashMap<String,Object>();output.put("intent",intent);output.put("caseChanges",patch);output.put("subjectRequisitionId",requisitionId);output.put("departmentId",departmentMissing?"":"V2-ALG");output.put("evidenceRefs",List.of(fakeEvidence?"fake":"tool-execution:"+id+"-budget"));
            output.put("blockerCandidates",unsupported?List.of(Map.of("type","BUDGET_INSUFFICIENT","evidenceRefs",List.of("tool-execution:"+id+"-budget"))):List.of());output.put("clarifications",List.of());output.put("deferredChecks",List.of());if(extraField)output.put("caseId","forged");return output;
        }
        void receipt(String run,String suffix,String tool,Object value){var request=new ToolCallRequest(tool,run+"-"+suffix,Map.of());executions.claim(run,request);executions.markSucceeded(request.requestId(),new ToolCallResult(tool,true,json.writeValueAsString(value),"",Map.of()));}
        AgentRequest input(){return new AgentRequest("s","u","purchase",Map.of("tenantId","t","authenticatedRoles",Set.of("USER")));}
        AgentRuntimeResult run(){return workflow.execute(input(),AgentEventListener.NOOP);}
        ProcurementCase current(){return cases.findByTenantUserAndConversationId("t","u","s").orElse(null);}
    }
}
