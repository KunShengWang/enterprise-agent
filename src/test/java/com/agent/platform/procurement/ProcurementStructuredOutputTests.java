package com.agent.platform.procurement;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.*;
import com.agent.platform.llm.*;
import com.agent.platform.memory.RuleBasedConversationSummarizer;
import com.agent.platform.procurement.workflow.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.agent.platform.procurement.ProcurementLiveEvalRuntimeHarness.*;

class ProcurementStructuredOutputTests {
    static final String RFQ="{\"requestRfq\":true,\"explanation\":\"proposal\",\"recommendationRef\":\"finalize-ref\",\"evidenceRefs\":[]}";
    private StructuredOutputSpec rfq(){return new StructuredOutputSpec(RfqStageResult.class);}
    @Test void validRfqIsTyped(){assertInstanceOf(RfqStageResult.class,rfq().parse(RFQ));}
    @Test void validRequisitionIsTyped(){var f=new ProcurementWorkflowTests.Fixture();assertInstanceOf(RequisitionStageResult.class,new StructuredOutputSpec(RequisitionStageResult.class).parse(f.json.writeValueAsString(f.requisition("r"))));}
    @Test void validSourcingIsTyped(){assertInstanceOf(SourcingStageResult.class,new StructuredOutputSpec(SourcingStageResult.class).parse("{\"status\":\"NO_ELIGIBLE_SUPPLIER\",\"recommendation\":{},\"evidenceRefs\":[],\"tradeoffs\":[],\"riskFindings\":[]}"));}
    @Test void leadingProseRejected(){assertEquals(StructuredOutputSpec.Error.STRUCTURED_OUTPUT_PARSE_ERROR,assertThrows(StructuredOutputSpec.Invalid.class,()->rfq().parse("Here is analysis "+RFQ)).code());}
    @Test void trailingProseRejected(){assertThrows(StructuredOutputSpec.Invalid.class,()->rfq().parse(RFQ+" explanation"));}
    @Test void concatenatedJsonRejected(){assertThrows(StructuredOutputSpec.Invalid.class,()->rfq().parse(RFQ+RFQ));}
    @Test void missingFieldRejected(){assertThrows(StructuredOutputSpec.Invalid.class,()->rfq().parse("{\"requestRfq\":true,\"evidenceRefs\":[]}"));}
    @Test void wrongTypeRejected(){assertThrows(StructuredOutputSpec.Invalid.class,()->rfq().parse(RFQ.replace("true","\"true\"")));}
    @Test void nestedWrongTypeRejected(){assertThrows(StructuredOutputSpec.Invalid.class,()->rfq().parse(RFQ.replace("[]","[{}]")));}
    @Test void illegalEnumRejected(){assertThrows(StructuredOutputSpec.Invalid.class,()->new StructuredOutputSpec(SourcingStageResult.class).parse("{\"status\":\"INVALID\",\"recommendation\":{},\"evidenceRefs\":[],\"tradeoffs\":[],\"riskFindings\":[]}"));}
    @Test void incompleteRecommendationRejected(){assertThrows(StructuredOutputSpec.Invalid.class,()->new StructuredOutputSpec(SourcingStageResult.class).parse("{\"status\":\"RECOMMENDATION\",\"recommendation\":{},\"evidenceRefs\":[],\"tradeoffs\":[],\"riskFindings\":[]}"));}
    @Test void emptyIsExplicit(){assertEquals(StructuredOutputSpec.Error.STRUCTURED_OUTPUT_EMPTY,assertThrows(StructuredOutputSpec.Invalid.class,()->rfq().parse(" ")).code());}
    @Test void unknownFieldRejected(){assertThrows(StructuredOutputSpec.Invalid.class,()->rfq().parse(RFQ.replace("{","{\"hack\":1,")));}
    @Test void previousArrayBoundPreserved(){var json=new ObjectMapper();assertThrows(StructuredOutputSpec.Invalid.class,()->rfq().parse(json.writeValueAsString(Map.of("requestRfq",true,"explanation","x","recommendationRef","ref","evidenceRefs",Collections.nCopies(65,"ref")))));}
    @Test void previousPatchConstraintsPreserved(){var f=new ProcurementWorkflowTests.Fixture();for(var patch:List.of(Map.of("quantity",0),Map.of("currency","bad-currency"),Map.of("hardConstraintsUpsert",Map.of("unknown","1")),Map.of("preferencesUpsert",Map.of("deliveryPriority","LOW")),Map.of("fieldsToClear",List.of("unauthorized")))){var value=f.requisition("r");value.put("caseChanges",patch);assertThrows(StructuredOutputSpec.Invalid.class,()->new StructuredOutputSpec(RequisitionStageResult.class).parse(f.json.writeValueAsString(value)));}}
    @Test void typedProfileRoundTrip(){var mapper=new ObjectMapper();var p=new ProcurementStageProfiles(new AgentProperties(),mapper).get(ProcurementStage.RFQ).executionProfile();assertEquals(RfqStageResult.class,mapper.readValue(mapper.writeValueAsString(p),AgentExecutionProfile.class).structuredOutput().outputType());}
    @Test void validInitialHasNoRepair(){var f=new Harness(RFQ);var r=f.run();assertEquals(AgentRunState.COMPLETED,r.state(),r.answer());assertEquals(1,r.budget().modelCalls());assertEquals(0,f.rejections());assertInstanceOf(RfqStageResult.class,StructuredOutputSpec.stored(f.runs.find(r.runId()).orElseThrow(),RfqStageResult.class));}
    @Test void oneCorrectionSucceedsAndAllUsageCounted(){var f=new Harness("analysis "+RFQ,RFQ);var r=f.run();assertEquals(AgentRunState.COMPLETED,r.state(),r.answer());assertEquals(2,r.budget().modelCalls());assertEquals(200,r.budget().inputTokens());assertEquals(20,r.budget().outputTokens());assertEquals(1,f.rejections());var e=f.events.stream().filter(v->v.type()==AgentEventType.STRUCTURED_OUTPUT_VALIDATED).findFirst().orElseThrow();assertEquals(1,e.payload().get("structured_output_repair_count"));assertEquals(110L,e.payload().get("repair_tokens"));assertTrue(((Number)e.payload().get("repair_latency_ms")).doubleValue()>=0);}
    @Test void emptyCanUseOneCorrection(){var f=new Harness("",RFQ);assertEquals(AgentRunState.COMPLETED,f.run().state());assertEquals(1,f.rejections());}
    @Test void invalidAfterCorrectionFailsAndNeverCallsThird(){var f=new Harness("bad","bad",RFQ);var r=f.run();assertEquals(AgentRunState.FAILED,r.state());assertEquals(AgentStopReason.STRUCTURED_OUTPUT_INVALID,r.stopReason());assertEquals(2,r.budget().modelCalls());assertEquals(2,f.requests.size());assertTrue(r.answer().contains("REPAIR_EXHAUSTED"));assertFalse(f.runs.find(r.runId()).orElseThrow().request().metadata().containsKey(StructuredOutputSpec.RESULT));}
    @Test void untrustedRequestCannotEnableJsonMode(){assertFalse(StructuredOutputSpec.modelMetadata(Map.of(StructuredOutputSpec.JSON_MODE,true),null,0).containsKey(StructuredOutputSpec.JSON_MODE));}
    @Test void businessWorkflowDoesNotAdvanceWithoutTypedResult(){var f=new ProcurementWorkflowTests.Fixture();doReturn(new AgentRuntimeResult("failed","s",AgentRunState.FAILED,AgentStopReason.STRUCTURED_OUTPUT_INVALID,"failed","",null,List.of())).when(f.runtime).run(any(),any(),any());assertEquals(AgentRunState.FAILED,f.run().state());assertNull(f.current());assertEquals(0,f.writes);}
    @Test void correctionCannotDispatchTools(){var f=new Harness("bad",RFQ);f.toolTurn=2;var r=f.run();assertEquals(AgentStopReason.STRUCTURED_OUTPUT_INVALID,r.stopReason());assertEquals(2,f.requests.size());verifyNoInteractions(f.tools);}
    @Test void intermediateToolContentIsNotValidatedAsStageResult(){var f=new Harness("intermediate prose",RFQ);f.toolTurn=1;var r=f.run();assertEquals(AgentRunState.COMPLETED,r.state(),r.answer());assertEquals(0,f.rejections());assertEquals(1,r.budget().toolCalls());verify(f.tools).execute(any(),any(),any(),any(),any(),any());}
    @Test void correctionCannotBypassModelBudget(){var f=new Harness("bad",RFQ);f.maxCalls=1;var r=f.run();assertEquals(AgentStopReason.MODEL_BUDGET_EXHAUSTED,r.stopReason());assertEquals(1,f.requests.size());}
    @Test void correctionProtocolFailureDoesNotTriggerHiddenThirdCall(){var f=new Harness("bad",RFQ);f.protocolError=true;var r=f.run();assertEquals(AgentRunState.FAILED,r.state());assertEquals(2,f.requests.size());assertEquals(2,r.budget().modelCalls());}
    @Test void persistedCorrectionCannotBeRepeatedAfterPause(){var f=new Harness("bad",RFQ);var r=f.run();assertEquals(AgentRunState.COMPLETED,r.state());assertEquals(true,f.runs.find(r.runId()).orElseThrow().request().metadata().get(StructuredOutputSpec.REPAIR_ATTEMPTED));f.runs.update(r.runId(),stored->stored.paused(stored.budgetSnapshot()));var resumed=f.runtime.resume(r.runId(),f.events::add);assertEquals(AgentStopReason.STRUCTURED_OUTPUT_INVALID,resumed.stopReason());assertEquals(2,f.requests.size());}

    static class Harness {
        final List<AgentEvent> events=new ArrayList<>();final List<AgentModelRequest> requests=new ArrayList<>();
        final ProcurementV2TrialDriver.EvalRunStore runs=new ProcurementV2TrialDriver.EvalRunStore();final DefaultAgentRuntime runtime;
        final AgentToolRuntime tools=mock(AgentToolRuntime.class);int toolTurn;int maxCalls=20;boolean protocolError;
        Harness(String...responses){
            var properties=new AgentProperties();var timeline=new InMemoryTimelineStore();var executions=new InMemoryToolExecutionStore();var memory=new EmptyMemoryService();
            var manager=new DefaultAgentContextManager(timeline,new ConservativeTokenEstimator(),memory,new RuleBasedConversationSummarizer(),properties);
            var guard=mock(GuardrailService.class);when(guard.checkInput(any())).thenReturn(GuardrailDecision.allow(GuardrailStage.INPUT,"ok"));when(guard.checkOutput(any())).thenReturn(GuardrailDecision.allow(GuardrailStage.OUTPUT,"ok"));
            var registry=mock(AgentCapabilityRegistry.class);
            when(registry.listCapabilities()).thenReturn(List.of(new ToolDefinition("procurement_case_get","read","{}",ToolRiskLevel.LOW,Map.of("readOnly",true))));
            var definition=registry.listCapabilities().get(0);
            when(registry.findCapability("procurement_case_get")).thenReturn(Optional.of(definition));
            when(tools.execute(any(),any(),any(),any(),any(),any())).thenAnswer(call->{AgentToolCall tc=call.getArgument(4);return new AgentToolRuntimeResult(AgentToolExecutionStatus.COMPLETED,new ToolCallRequest(tc.toolName(),tc.toolCallId(),tc.arguments()),new ToolCallResult(tc.toolName(),true,"plain tool data","",Map.of()),GuardrailAction.ALLOW,"","",false);});
            var model=new AgentModelGateway(){public AgentModelTurn nextTurn(AgentModelRequest r){requests.add(r);if(protocolError&&requests.size()==2)throw new LlmCallException("MODEL_PROTOCOL_ERROR","bad protocol",null);return new AgentModelTurn(responses[requests.size()-1],requests.size()==toolTurn?List.of(new AgentToolCall("call","procurement_case_get",Map.of(),"")):List.of(),"raw",new LlmUsage(100,10,110,0,0,"mock","provider"),"STOP");}};
            runtime=new DefaultAgentRuntime(properties,timeline,runs,executions,manager,model,registry,tools,guard,List.of(),mock(ApprovalService.class),new ConservativeTokenEstimator(),new NoopRunControlStore(),memory,new ConfiguredLlmCostCalculator(properties),new ToolResultProjector(properties));
        }
        AgentRuntimeResult run(){var original=new ProcurementStageProfiles(new AgentProperties(),new ObjectMapper()).get(ProcurementStage.RFQ).executionProfile();var limits=new AgentRunLimits(20,maxCalls,20,200000,20000,0,60000);var profile=new AgentExecutionProfile(original.name(),original.systemPrompt(),Set.of("procurement_case_get"),limits,false,original.contextPolicy(),original.structuredOutput());return runtime.run(new AgentRequest("s","u","return JSON",Map.of("tenantId","t")),profile,events::add);}
        long rejections(){return events.stream().filter(e->e.type()==AgentEventType.STRUCTURED_OUTPUT_REJECTED).count();}
    }
}
