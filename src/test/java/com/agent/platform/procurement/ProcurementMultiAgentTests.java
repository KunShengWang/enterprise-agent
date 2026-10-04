package com.agent.platform.procurement;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.*;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.*;
import com.agent.platform.llm.*;
import com.agent.platform.memory.RuleBasedConversationSummarizer;
import com.agent.platform.procurement.agent.*;
import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.erp.ErpRfqApprovalGuard;
import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.agent.platform.procurement.tool.ProcurementToolCatalog.*;
import static com.agent.platform.procurement.ProcurementLiveEvalRuntimeHarness.*;

class ProcurementMultiAgentTests {
    final ObjectMapper json=new ObjectMapper();
    @Test void registryHasExactlyThreeDomainsAndFourRootToolsWithoutRecursion(){
        var registry=new AgentRegistry(new AgentProperties());
        assertEquals(3,registry.domains().size());assertEquals(AgentRegistry.ROOT_TOOLS,registry.supervisor().allowedCapabilities());
        for(var agent:registry.domains()){
            assertFalse(agent.profile().longTermMemoryEnabled());assertTrue(agent.contextPolicy().enabled());
            assertTrue(Collections.disjoint(agent.toolSet(),AgentRegistry.ROOT_TOOLS));
            if(!agent.agentId().equals(AgentRegistry.RFQ))assertFalse(agent.toolSet().contains(CREATE_RFQ));
            assertFalse(agent.toolSet().contains(COMMERCIAL_ANALYSIS));assertFalse(agent.toolSet().contains(DELIVERY_ANALYSIS));
        }
    }
    @Test void schemaRejectsProseUnknownEvidenceAndExtraFields(){
        assertThrows(IllegalArgumentException.class,()->SubAgentExecutor.validate(json.readTree("\"prose\""),Set.of()));
        assertThrows(IllegalArgumentException.class,()->SubAgentExecutor.validate(json.readTree("{\"status\":\"COMPLETE\",\"findings\":[],\"warnings\":[],\"evidenceRefs\":[\"fake\"]}"),Set.of()));
        SubAgentExecutor.validate(json.readTree("{\"status\":\"COMPLETE\",\"findings\":[],\"warnings\":[],\"evidenceRefs\":[\"real\"]}"),Set.of("real"));
        assertThrows(IllegalArgumentException.class,()->SubAgentExecutor.validate(json.readTree("{\"status\":\"COMPLETE\",\"findings\":[],\"warnings\":[],\"evidenceRefs\":[]}"),Set.of()));
    }
    @Test void childBudgetConsumesAggregateAndStopsBeforeNextCall(){
        var parent=new AgentRunBudget(new AgentRunLimits(2,2,2,20,20,0,60000));
        var child=new AgentRunBudget(new AgentRunLimits(20,20,20,1000,1000,0,60000)).withParent(parent);
        child.recordTurnStarted();child.recordModelCall(new LlmUsage(20,1,21,0,0,"test","test"),0);child.recordToolCall();
        assertEquals(1,parent.snapshot().turns());assertEquals(1,parent.snapshot().toolCalls());assertEquals(20,parent.snapshot().inputTokens());
        assertEquals(AgentStopReason.MODEL_BUDGET_EXHAUSTED,child.beforeModelCall().orElseThrow());
        parent.cancel();assertEquals(AgentStopReason.CANCELLED,child.beforeToolCall().orElseThrow());
    }
    @Test void rootLeaseCoversChildAndPersistedRootCancellationStopsChildTool(){
        var f=new Fixture();var control=mock(AgentRunControlStore.class);
        when(control.renewSessionLease(anyString(),anyString(),any())).thenReturn(true);
        when(control.cancellationRequested(anyString())).thenAnswer(i->f.modelRequests.size()>1
                && f.modelRequests.get(0).runId().equals(i.getArgument(0)));
        org.springframework.test.util.ReflectionTestUtils.setField(f.runtime,"runControlStore",control);
        var stopped=f.start();assertEquals(AgentRunState.FAILED,stopped.state(),f.debug());
        assertTrue(f.runs.recent(100).stream().anyMatch(r->"CANCELLED".equals(r.failureReason())));
        assertEquals(0,f.writes.get());assertTrue(f.approvals.recent(10).isEmpty());
        assertTrue(f.executions.all().stream().noneMatch(e->SUPPLIER_SEARCH.equals(e.toolName())));
        verify(control).acquireSessionLease(eq("conversation"),anyString(),anyString(),
                argThat(d->d.toMillis()>480_000));
    }
    @Test void approvedNestedRfqResumesSameChildAndExactRequestOnce(){
        var f=new Fixture();var waiting=f.start();
        assertEquals(AgentRunState.WAITING_APPROVAL,waiting.state(),f.debug());assertEquals(0,f.writes.get());
        var approval=f.approvals.find(waiting.approvalId()).orElseThrow();
        assertNotEquals(waiting.runId(),approval.runId());assertEquals(AgentRunState.WAITING_APPROVAL,f.runs.find(approval.runId()).orElseThrow().state());
        var childIds=f.runs.recent(100).stream().map(AgentRunRecord::runId).toList();
        assertEquals(3,childIds.size());
        assertEquals(1,f.runs.find(approval.runId()).orElseThrow().runtimeState().get("callDepth"));
        f.approvals.decide(approval.approvalId(),true,"reviewer","approved");
        var finished=f.runtime.resume(waiting.runId());
        assertEquals(AgentRunState.COMPLETED,finished.state(),f.debug());assertEquals(1,f.writes.get());
        assertEquals(childIds.size(),f.runs.recent(100).size());
        assertEquals(approval.toolCallRequest(),f.executions.findToolExecution(approval.toolCallRequest().requestId()).orElseThrow().request());
        assertEquals(AgentRunState.COMPLETED,f.runtime.resume(waiting.runId()).state());assertEquals(1,f.writes.get());
        assertTrue(f.modelRequests.stream().filter(r->!r.tools().stream().anyMatch(t->t.name().equals(AgentRegistry.SOURCE)))
                .allMatch(r->r.messages().stream().noneMatch(m->m.content().contains("ROOT_HISTORY_SENTINEL"))));
        assertTrue(f.modelRequests.stream().filter(r->r.tools().stream().anyMatch(t->t.name().equals(AgentRegistry.SOURCE)))
                .noneMatch(r->r.messages().stream().anyMatch(m->m.content().contains("\"evaluations\""))));
    }
    @Test void rejectedNestedRfqWritesNothing(){var f=new Fixture();var w=f.start();assertEquals(AgentRunState.WAITING_APPROVAL,w.state(),f.debug());f.approvals.decide(w.approvalId(),false,"reviewer","no");assertEquals(AgentRunState.COMPLETED,f.runtime.resume(w.runId()).state(),f.debug());assertEquals(0,f.writes.get());}
    @Test void nestedApprovalSurvivesSerializedRunReloadAndRuntimeRecreation() throws Exception {
        var f=new Fixture();var w=f.start();assertEquals(AgentRunState.WAITING_APPROVAL,w.state(),f.debug());
        for(var r:f.runs.recent(100)){
            var restored=json.readValue(json.writeValueAsString(r),AgentRunRecord.class);
            f.runs.update(r.runId(),old->restored);
        }
        for(var e:f.executions.all())f.executions.records.put(e.toolCallId(),json.readValue(json.writeValueAsString(e),ToolExecutionRecord.class));
        String[] names={"properties","timelineStore","runStore","toolExecutionStore","contextManager","modelGateway","capabilityRegistry","toolRuntime","guardrailService","followUpGuardrailPolicies","approvalService","tokenEstimator","runControlStore","memoryService","costCalculator","toolResultProjector"};
        Object[] args=Arrays.stream(names).map(n->org.springframework.test.util.ReflectionTestUtils.getField(f.runtime,n)).toArray();
        f.runtime=(DefaultAgentRuntime)DefaultAgentRuntime.class.getConstructors()[0].newInstance(args);
        f.approvals.decide(w.approvalId(),true,"reviewer","yes");
        assertEquals(AgentRunState.COMPLETED,f.runtime.resume(w.runId()).state(),f.debug());
        assertEquals(1,f.writes.get());assertEquals(3,f.runs.recent(100).size());
    }
    @Test void staleCaseAndPayloadTamperingAreRejected(){
        for(boolean stale:List.of(true,false)){
            var f=new Fixture();var w=f.start();assertEquals(AgentRunState.WAITING_APPROVAL,w.state(),f.debug());
            f.approvals.decide(w.approvalId(),true,"reviewer","yes");var a=f.approvals.find(w.approvalId()).orElseThrow();
            if(stale){var c=f.cases.findByTenantUserAndConversationId("tenant","buyer","conversation").orElseThrow();
                f.cases.saveIfVersion(new ProcurementCase(c.caseId(),c.tenantId(),c.conversationId(),c.userId(),c.status(),c.state(),c.createdAt(),Instant.now(),c.version()+1,"concurrent"),c.version());
                f.runtime.resume(w.runId());
            }else{
                var args=new LinkedHashMap<>(a.toolCallRequest().arguments());args.put("quantity",999);
                var tampered=new ApprovalRecord(a.approvalId(),a.runId(),a.conversationId(),new ToolCallRequest(CREATE_RFQ,a.toolCallRequest().requestId(),args),a.reason(),a.status(),a.reviewer(),a.decisionReason(),a.createdAt(),a.expiresAt(),a.decidedAt());
                f.tools.executeApproved(tampered,f.definition(CREATE_RFQ),new ToolPolicyContext(a.runId(),a.conversationId(),"buyer","tenant",Set.of("USER"),Map.of()));
            }assertEquals(0,f.writes.get());
        }
    }
    @Test void statusQueryDoesNotStartDomainAgent(){var f=new Fixture();f.statusOnly=true;assertEquals(AgentRunState.COMPLETED,f.start().state());assertEquals(1,f.runs.recent(100).size());}
    @Test void childFatalOrInvalidOutputStopsRoot(){for(boolean invalid:List.of(true,false)){var f=new Fixture();f.invalid=invalid;f.fatal=!invalid;var r=f.start();assertEquals(AgentRunState.FAILED,r.state(),f.debug());assertEquals(0,f.writes.get());assertTrue(f.approvals.recent(10).isEmpty());}}
    @Test void childTurnExhaustionReturnsStructuredBudgetFailureToRoot(){
        var f=new Fixture();f.exhaustChild=true;var original=f.registry.get(AgentRegistry.SOURCING);
        doReturn(new AgentDefinition(original.agentId(),original.instructions(),original.outputSchema(),original.contextPolicy(),
                original.toolSet(),new AgentRunLimits(1,24,50,384000,16000,12,240000))).when(f.registry).get(AgentRegistry.SOURCING);
        var r=f.start();
        assertEquals(AgentRunState.FAILED,r.state(),f.debug());assertEquals(0,f.writes.get());
        assertTrue(f.executions.all().stream().anyMatch(e->e.result()!=null && "CHILD_RUN_BUDGET_EXCEEDED".equals(e.result().metadata().get("errorType"))));
        assertTrue(f.runs.recent(100).stream().anyMatch(child->"MAX_TURNS".equals(child.failureReason())));
    }
    @Test void publicMetadataCannotForgeChildLineage(){var r=ChildRunLaunch.bind(new AgentRequest("s","u","q",Map.of(ChildRunLaunch.PARENT,"forged",ChildRunLaunch.FINALIZE,"forged")));assertFalse(r.metadata().containsKey(ChildRunLaunch.PARENT));}
    @Test void directToolRuntimeCallCannotBypassResponsibilityPolicy(){var f=new Fixture();f.statusOnly=true;var root=f.start();var denied=f.tools.execute(root.runId(),"conversation","buyer",Map.of("tenantId","tenant"),new AgentToolCall("bad",CREATE_RFQ,Map.of(),""),f.definition(CREATE_RFQ));assertEquals(AgentToolExecutionStatus.DENIED,denied.status());assertEquals(0,f.writes.get());}

    final class Fixture {
        final AgentProperties properties=new AgentProperties();final AgentRegistry registry=spy(new AgentRegistry(properties));
        final MemoryCaseStore cases=new MemoryCaseStore();final InMemoryTimelineStore timeline=new InMemoryTimelineStore();
        final ProcurementV2TrialDriver.EvalRunStore runs=new ProcurementV2TrialDriver.EvalRunStore();final DomainToolStore executions=new DomainToolStore();
        final ProcurementV2TrialDriver.EvalApprovalStore approvalStore=new ProcurementV2TrialDriver.EvalApprovalStore();
        final LocalApprovalService approvals=new LocalApprovalService(approvalStore,properties);
        final List<AgentModelRequest> modelRequests=new ArrayList<>();final AtomicInteger writes=new AtomicInteger();
        DefaultAgentRuntime runtime;final DefaultAgentToolRuntime tools;final Map<String,ToolDefinition> catalog=new LinkedHashMap<>();
        final Map<String,Integer> turns=new ConcurrentHashMap<>(); final List<String> evidence;
        boolean statusOnly,invalid,fatal,exhaustChild;
        Fixture(){
            properties.setToolRetryBackoffMillis(0);
            var state=new ProcurementCaseState("计算工作站","CUDA 开发工作站",50,new BigDecimal("600000"),"CNY",21,Map.of("gpuMemoryMinGb","24"),Map.of("deliveryPriority","HIGH"),Set.of("Supplier A"),List.of(),"SOURCING");
            cases.createIfAbsent(new ProcurementCase("case-1","tenant","conversation","buyer",ProcurementCaseStatus.SOURCING,state,Instant.now(),Instant.now(),1,"patch"));
            var provider=new AwsSyntheticProcurementProvider(json,new ProcurementDataProperties());
            evidence=new ArrayList<>();for(String id:List.of("supplier-b","supplier-d"))provider.getSupplierEvidence(id,state).stream().filter(e->e.evidenceType().equals("OFFER")).forEach(e->evidence.add(e.evidenceId()));
            var merger=new ProcurementCasePatchMerger();var engine=new ProcurementDecisionEngine();
            var handler=new ProcurementToolHandler(provider,json,cases,new ProcurementCaseService(cases,merger),new ProcurementRecommendationFinalizer(cases,provider,engine),merger,engine);
            var binding=new ProcurementDomainBinding(runs,executions);var preparer=new ProcurementRfqApprovalPreparer(executions,cases,json);preparer.setDomainBinding(binding);
            var guard=new ErpRfqApprovalGuard(approvalStore,runs,executions,preparer,json);var simulated=new SimulatedProcurementRfqGateway();
            ProcurementRfqGateway gateway=new ProcurementRfqGateway(){
                public Receipt create(CreateRequest request){guard.requireApproved(request);writes.incrementAndGet();return simulated.create(request);}
                public Optional<Receipt> findByIdempotencyKey(String key){return simulated.findByIdempotencyKey(key);}
            };
            var rfq=new ProcurementRfqToolHandler(gateway,cases,executions,json);rfq.setDomainBinding(binding);
            var deferred=mock(AgentRuntime.class);
            var executor=new SubAgentExecutor(deferred,runs,executions,cases,registry,json);var adapter=new AgentToolAdapter(executor,cases,json,runs);
            registry.definitions().forEach(t->catalog.put(t.name(),t));new ProcurementToolCatalog().definitions().forEach(t->catalog.put(t.name(),t));
            var capabilities=mock(AgentCapabilityRegistry.class);when(capabilities.listCapabilities()).thenAnswer(i->List.copyOf(catalog.values()));when(capabilities.findCapability(anyString())).thenAnswer(i->Optional.ofNullable(catalog.get(i.getArgument(0))));
            catalog.put("mcp.erpnext.procurement_get_budget_status",new ToolDefinition("mcp.erpnext.procurement_get_budget_status","budget",
                    "{\"type\":\"object\",\"x-businessRefs\":{\"departmentId\":\"department-test\"}}",ToolRiskLevel.LOW,Map.of()));
            executor.setCapabilities(capabilities);
            var policy=new DefaultToolPermissionPolicy();var guards=mock(GuardrailService.class);
            when(guards.checkInput(anyString())).thenReturn(GuardrailDecision.allow(GuardrailStage.INPUT,"test"));
            when(guards.checkOutput(anyString())).thenReturn(GuardrailDecision.allow(GuardrailStage.OUTPUT,"test"));
            when(guards.checkToolCall(any(),any(),any())).thenAnswer(i->policy.check(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
            ContextualAgentCapabilityExecutor capabilitiesExecutor=new ContextualAgentCapabilityExecutor(){
                public ToolCallResult execute(ToolCallRequest request){throw new IllegalStateException();}
                public ToolCallResult execute(ToolCallRequest request,ToolExecutionContext context){
                    if(adapter.supports(request.toolName()))return adapter.execute(request,context);
                    if(rfq.supports(request.toolName()))return rfq.execute(request,context);
                    return handler.execute(request,context);
                }
            };
            tools=new DefaultAgentToolRuntime(guards,approvals,executions,capabilitiesExecutor,properties,List.of(preparer),List.of(rfq));tools.setRunLinks(new AgentRunLinks(runs));tools.setChildBusinessScope(new ChildBusinessScope(runs,json));
            var memory=new EmptyMemoryService();var renderer=new ProcurementCaseContextRenderer(cases,json);
            var manager=new DefaultAgentContextManager(timeline,new ConservativeTokenEstimator(),memory,new RuleBasedConversationSummarizer(),properties,List.of(new DomainCaseContextProvider(renderer,new AgentRunLinks(runs))));
            runtime=new DefaultAgentRuntime(properties,timeline,runs,executions,manager,this::model,capabilities,tools,guards,List.of(),approvals,new ConservativeTokenEstimator(),new NoopRunControlStore(),memory,new ConfiguredLlmCostCalculator(properties),new ToolResultProjector(properties));
            runtime.setResultMetadataProviders(List.of(new ProcurementExecutionSummaries(runs,executions,cases,json)));
            when(deferred.run(any(),any(),any())).thenAnswer(i->runtime.run(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
            when(deferred.resume(anyString(),any())).thenAnswer(i->runtime.resume(i.getArgument(0),i.getArgument(1)));
        }
        ToolDefinition definition(String name){return catalog.get(name);}
        AgentRuntimeResult start(){timeline.openSession("conversation","buyer");timeline.appendMessages("conversation","buyer","old",List.of(AgentMessageDraft.user("ROOT_HISTORY_SENTINEL",10)));return runtime.run(new AgentRequest("conversation","buyer","比较后创建 RFQ",Map.of("tenantId","tenant","authenticatedRoles",Set.of("USER"))),registry.supervisor(),AgentEventListener.NOOP);}
        String debug(){return json.writeValueAsString(runs.recent(100));}
        AgentModelTurn model(AgentModelRequest request){
            modelRequests.add(request);int n=turns.merge(request.runId(),1,Integer::sum);String name=runs.find(request.runId()).orElseThrow().executionProfile().name();
            if(name.equals(AgentRegistry.SUPERVISOR)){
                if(statusOnly)return n==1?call(AgentRegistry.INSPECT,Map.of()):answer("当前寻源阶段");
                return switch(n){case 1->call(AgentRegistry.SOURCE,Map.of("objective","比较供应商"));case 2->call(AgentRegistry.PREPARE,Map.of("objective","创建 RFQ"));default->answer("处理完成");};
            }
            if(name.equals(AgentRegistry.SOURCING)){
                if(exhaustChild)return call(SUPPLIER_SEARCH,Map.of());
                if(fatal)throw new IllegalStateException("injected child fatal");
                return switch(n){case 1->call(SUPPLIER_SEARCH,Map.of());case 2->call(RECOMMENDATION_FINALIZE,Map.of("evaluatedCaseVersion",1,"selectedSupplierId","supplier-d","evidenceRefs",evidence,"tradeoffDimensions",List.of("DELIVERY"),"confidence",0.9));default->answer(invalid?"bad":json.writeValueAsString(Map.of("status","COMPLETE","findings",List.of("推荐已验证"),"warnings",List.of(),"evidenceRefs",evidence)));};
            }
            if(n==1)return call(CREATE_RFQ,Map.of());
            return answer(json.writeValueAsString(Map.of("status",writes.get()>0?"COMPLETE":"REJECTED","findings",List.of("审批已处理"),"warnings",List.of(),"evidenceRefs",evidence)));
        }
        AgentModelTurn call(String name,Map<String,Object> args){return new AgentModelTurn("",List.of(new AgentToolCall(UUID.randomUUID().toString(),name,args,"")),"",new LlmUsage(10,5,15,0,0,"test","test"),"tool_calls");}
        AgentModelTurn answer(String text){return new AgentModelTurn(text,List.of(),"",new LlmUsage(10,5,15,0,0,"test","test"),"stop");}
    }
    static final class DomainToolStore implements ToolExecutionStore {
        final Map<String,ToolExecutionRecord> records=new ConcurrentHashMap<>();
        public ToolExecutionClaim claim(String run,ToolCallRequest request){var old=records.putIfAbsent(request.requestId(),ToolExecutionRecord.running(run,request));return old==null?ToolExecutionClaim.acquired():ToolExecutionClaim.existing(old,"existing");}
        public void markSucceeded(String id,ToolCallResult result){set(id,result,ToolExecutionState.SUCCEEDED);}
        public void markFailed(String id,ToolCallResult result){set(id,result,ToolExecutionState.FAILED);}
        public void markWaitingApproval(String id,ToolCallResult result){set(id,result,ToolExecutionState.WAITING_APPROVAL);}
        void set(String id,ToolCallResult result,ToolExecutionState state){records.computeIfPresent(id,(k,v)->v.withResult(state,result,result.errorMessage()));}
        public void markManualReview(String id,String reason){set(id,new ToolCallResult("",false,"",reason,Map.of("manualReview",true)),ToolExecutionState.MANUAL_REVIEW);}
        public Optional<ToolExecutionRecord> findToolExecution(String id){return Optional.ofNullable(records.get(id));}
        public List<ToolExecutionRecord> findByRun(String id){return records.values().stream().filter(r->r.runId().equals(id)).toList();}
        List<ToolExecutionRecord> all(){return List.copyOf(records.values());}
    }
}
