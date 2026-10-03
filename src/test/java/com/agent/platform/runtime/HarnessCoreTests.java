package com.agent.platform.runtime;

import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class HarnessCoreTests {
    private final AgentRunLimits limits = new AgentRunLimits(2, 10, 2, 10000, 10000, 0, 1000);
    @Test void turnBudgetStopsAtBoundary() {
        var budget = new AgentRunBudget(limits);
        budget.recordTurnStarted(); assertTrue(budget.beforeTurn().isEmpty());
        budget.recordTurnStarted(); assertEquals(AgentStopReason.MAX_TURNS, budget.beforeTurn().orElseThrow());
    }
    @Test void toolBudgetStopsBeforeExcessExecution() {
        var budget = new AgentRunBudget(limits);
        budget.recordToolCall(); budget.recordToolCall();
        assertEquals(AgentStopReason.TOOL_BUDGET_EXHAUSTED, budget.beforeToolCall().orElseThrow());
    }
    @Test void durationAndCancellationAreStructured() {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        var snapshot = new AgentRunBudgetSnapshot(0,0,0,0,0,0,now.minusSeconds(1),now,false);
        var budget = new AgentRunBudget(limits, snapshot, Clock.fixed(now, ZoneOffset.UTC));
        assertEquals(AgentStopReason.TIMEOUT, budget.beforeTurn().orElseThrow());
        budget.cancel(); assertEquals(AgentStopReason.CANCELLED, budget.beforeToolCall().orElseThrow());
    }
    @Test void statusMappingKeepsApprovalPausedAndCancellationDistinct() {
        assertEquals(RunStatus.PAUSED, RunStatus.from(AgentRunState.WAITING_APPROVAL, AgentStopReason.WAITING_APPROVAL));
        assertEquals(RunStatus.CANCELLED, RunStatus.from(AgentRunState.REJECTED, AgentStopReason.CANCELLED));
        assertEquals(RunStatus.COMPLETED, RunStatus.from(AgentRunState.COMPLETED, AgentStopReason.COMPLETED));
        for (var reason : List.of(AgentStopReason.MAX_TURNS, AgentStopReason.TOOL_BUDGET_EXHAUSTED,
                AgentStopReason.TIMEOUT, AgentStopReason.MODEL_ERROR, AgentStopReason.TOOL_ERROR, AgentStopReason.INTERNAL_ERROR))
            assertEquals(RunStatus.FAILED, RunStatus.from(AgentRunState.FAILED, reason));
    }
    @Test void everyOutcomeIsRepresentableWithoutMessageParsing() {
        assertEquals(ToolExecutionOutcome.SUCCESS, new ToolCallResult("t",true,"","",Map.of()).outcome());
        assertEquals(ToolExecutionOutcome.BUSINESS_ERROR, new ToolCallResult("t",false,"","missing supplier",Map.of()).outcome());
        for (var outcome : ToolExecutionOutcome.values()) {
            if (outcome == ToolExecutionOutcome.SUCCESS) continue;
            assertEquals(outcome, new ToolCallResult("t",false,"","arbitrary translated message",
                    Map.of("_harnessOutcome",outcome.name())).outcome());
        }
        assertEquals(ToolExecutionOutcome.VALIDATION_ERROR, new ToolCallResult("t",false,"","",Map.of("validation","failed")).outcome());
    }
    @Test void approvalDoesNotDispatchOrBecomeFatal() {
        var guard = mock(GuardrailService.class); var executor = mock(AgentCapabilityExecutor.class);
        when(guard.checkToolCall(any(),any(),any())).thenReturn(GuardrailDecision.requireApproval(GuardrailStage.TOOL,"approval"));
        var runtime = new DefaultAgentToolRuntime(guard,mock(ApprovalService.class),mock(ToolExecutionStore.class),executor,new AgentProperties());
        var result = runtime.execute("r","s","u",Map.of(),new AgentToolCall("c","t",Map.of(),""),definition(false));
        assertEquals(ToolExecutionOutcome.APPROVAL_REQUIRED,result.outcome());
        verifyNoInteractions(executor);
    }
    @Test void structuredBackendFailuresAreRetryableWithoutAddingRetries() {
        for (var metadata : List.of(Map.<String,Object>of("errorType","ERP_UNAVAILABLE"),
                Map.<String,Object>of("_harnessErrorCode","SERVICE_TIMEOUT"),
                Map.<String,Object>of("httpStatus",503))) {
            var result = new ToolCallResult("t",false,"","任意语言的错误信息",metadata);
            assertEquals(ToolExecutionOutcome.RETRYABLE_ERROR,result.outcome());
            assertFalse(result.metadata().containsKey("retryable"));
        }
        assertEquals(ToolExecutionOutcome.BUSINESS_ERROR,
                new ToolCallResult("t",false,"","",Map.of("errorCode","ERP_NOT_FOUND")).outcome());
    }
    @Test void deniedDoesNotDispatch() {
        var guard = mock(GuardrailService.class); var executor = mock(AgentCapabilityExecutor.class);
        when(guard.checkToolCall(any(),any(),any())).thenReturn(GuardrailDecision.block(GuardrailStage.TOOL,"denied"));
        var runtime = new DefaultAgentToolRuntime(guard,mock(ApprovalService.class),mock(ToolExecutionStore.class),executor,new AgentProperties());
        assertEquals(ToolExecutionOutcome.POLICY_DENIED,runtime.execute("r","s","u",Map.of(),new AgentToolCall("c","t",Map.of(),""),definition(false)).outcome());
        verifyNoInteractions(executor);
    }
    @Test void readTimeoutIsBoundedAndClassified() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var result = ToolExecutionDeadline.call(request(),definition(true),()->{new CountDownLatch(1).await();return null;});
            assertEquals(ToolExecutionOutcome.RETRYABLE_ERROR,result.outcome());
            assertEquals(false,result.metadata().get("retryable")); // Classification does not introduce retry.
        });
    }
    @Test void timedOutWriteRemainsUncertainAndIsNotRetried() {
        var guard = mock(GuardrailService.class); var store = mock(ToolExecutionStore.class);
        var executor = mock(AgentCapabilityExecutor.class);
        when(guard.checkToolCall(any(),any(),any())).thenReturn(GuardrailDecision.allow(GuardrailStage.TOOL,"allowed"));
        when(store.claim(any(),any())).thenReturn(ToolExecutionClaim.acquired());
        when(executor.execute(any())).thenAnswer(call->{new CountDownLatch(1).await();return null;});
        var runtime = new DefaultAgentToolRuntime(guard,mock(ApprovalService.class),store,executor,new AgentProperties());
        var result = runtime.execute("r","s","u",Map.of(),new AgentToolCall("c","t",Map.of(),""),definition(false));
        assertEquals(AgentToolExecutionStatus.MANUAL_REVIEW,result.status());
        assertEquals(ToolExecutionOutcome.FATAL_ERROR,result.outcome());
        verify(executor,times(1)).execute(any()); verify(store).markManualReview(eq("c"),any());
        verify(store,never()).markSucceeded(any(),any());
    }
    @Test void telemetryDoesNotChangeSuccessfulModelEnvelope() {
        var projector = new ToolResultProjector(new AgentProperties());
        var original = new ToolCallResult("t",true,"data","",Map.of("provider","local","attempts",1));
        var observed = new ToolCallResult("t",true,"data","",Map.of("provider","local","attempts",1,"_harnessDurationMs",1,"_harnessOutcome","SUCCESS"));
        assertEquals(projector.project("c",original,true),projector.project("c",observed,true));
    }
    @Test void expiredRunDeadlineNeverDispatchesEvenWithLongToolTimeout() {
        var definition = new ToolDefinition("t","tool","{}",ToolRiskLevel.LOW,
                Map.of("readOnly",true,"_harnessDeadlineEpochMillis",0));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var result = ToolExecutionDeadline.call(request(),definition,()->{calls.incrementAndGet();return null;});
        assertEquals(0,calls.get());
        assertEquals(ToolExecutionOutcome.FATAL_ERROR,result.outcome());
    }
    @Test void hiddenTelemetryDoesNotConsumeModelMetadataSlots() {
        var projector = new ToolResultProjector(new AgentProperties());
        var before = new LinkedHashMap<String,Object>();
        for (int i=0;i<40;i++) before.put("field"+i,i);
        var after = new LinkedHashMap<>(before);
        after.put("_harnessOutcome","SUCCESS");after.put("_harnessErrorCode","UNAVAILABLE");
        var visibleBefore=projector.project("c",new ToolCallResult("t",true,"data","",before),true);
        var visibleAfter=projector.project("c",new ToolCallResult("t",true,"data","",after),true);
        // Existing bounded metadata does not promise selection order above 32 entries.
        assertEquals(8,visibleBefore.metadata().get("metadataEntriesOmitted"));
        assertEquals(8,visibleAfter.metadata().get("metadataEntriesOmitted"));
        assertTrue(visibleAfter.metadata().keySet().stream().noneMatch(k->k.startsWith("_harness")));
    }
    @Test void lateNonCooperativeWriteCannotChangeUncertainClaimToSucceeded() throws Exception {
        var release = new CountDownLatch(1); var done = new CountDownLatch(1);
        var guard = mock(GuardrailService.class); var store = mock(ToolExecutionStore.class);
        var executor = mock(AgentCapabilityExecutor.class);
        when(guard.checkToolCall(any(),any(),any())).thenReturn(GuardrailDecision.allow(GuardrailStage.TOOL,"allowed"));
        when(store.claim(any(),any())).thenReturn(ToolExecutionClaim.acquired());
        when(executor.execute(any())).thenAnswer(call -> {
            while (release.getCount()>0) {
                try { release.await(); } catch (InterruptedException ignored) { /* external IO ignores cancellation */ }
            }
            done.countDown();
            return new ToolCallResult("t",true,"late write","",Map.of());
        });
        var runtime = new DefaultAgentToolRuntime(guard,mock(ApprovalService.class),store,executor,new AgentProperties());
        try {
            var result = runtime.execute("r","s","u",Map.of(),new AgentToolCall("c","t",Map.of(),""),definition(false));
            assertEquals(AgentToolExecutionStatus.MANUAL_REVIEW,result.status());
        } finally { release.countDown(); }
        assertTrue(done.await(2,java.util.concurrent.TimeUnit.SECONDS));
        verify(executor,times(1)).execute(any());
        verify(store).markManualReview(eq("c"),any());
        verify(store,never()).markSucceeded(any(),any());
    }
    @Test void metadataViewDoesNotMutateFrozenToolSchema() {
        var d=definition(true); var metadata=d.metadata();
        assertEquals(ToolRuntimeContract.Effect.READ_ONLY,d.runtimeContract().effect());
        assertEquals(ToolRuntimeContract.Source.LOCAL,d.runtimeContract().source());
        assertSame(metadata,d.metadata());
    }
    private ToolDefinition definition(boolean read) { return new ToolDefinition("t","tool","{}",ToolRiskLevel.LOW,Map.of("readOnly",read,"timeoutMillis",40)); }
    private ToolCallRequest request() { return new ToolCallRequest("t","c",Map.of()); }
}
