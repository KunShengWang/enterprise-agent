package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.llm.ConfiguredLlmCostCalculator;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class DefaultAgentRuntimeStateTests {

    @Test
    void repeatedModelToolCallIdsReceiveDifferentRuntimeExecutionIds() {
        Fixture fixture = new Fixture();
        AgentToolCall modelCall = new AgentToolCall(
                "call-1", "ticket_close", Map.of("id", "T1"), "close ticket"
        );

        AgentToolCall firstRunCall = fixture.runtime().assignExecutionId(modelCall);
        AgentToolCall secondRunCall = fixture.runtime().assignExecutionId(modelCall);

        assertNotEquals(modelCall.toolCallId(), firstRunCall.toolCallId());
        assertNotEquals(firstRunCall.toolCallId(), secondRunCall.toolCallId());
        assertEquals(modelCall.toolName(), firstRunCall.toolName());
        assertEquals(modelCall.arguments(), firstRunCall.arguments());
    }

    @Test
    void failedResumeClaimReturnsCurrentStateWithoutExecutingTool() {
        Fixture fixture = new Fixture();
        AgentRunLimits limits = AgentRunLimits.from(fixture.properties);
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "restricted", "prompt", Set.of("ticket_status"), limits, false
        );
        ToolCallRequest request = new ToolCallRequest("ticket_status", "call-1", Map.of("id", "T1"));
        AgentRunRecord waiting = AgentRunRecord.create(
                        "run-1", "run-1", "session-1",
                        new AgentRequest("session-1", "user-1", "question", Map.of()),
                        profile, new AgentRunBudget(limits).snapshot())
                .waitingForApproval("approval-1", request, List.of(), List.of(), false,
                        new AgentRunBudget(limits).snapshot());
        AgentRunRecord alreadyClaimed = waiting.claimedForResume();
        ApprovalRecord approval = new ApprovalRecord(
                "approval-1", "run-1", "session-1", request, "reason",
                ApprovalStatus.APPROVED, "reviewer", "ok", Instant.now(), Instant.now()
        );
        when(fixture.runStore.find("run-1"))
                .thenReturn(Optional.of(waiting), Optional.of(alreadyClaimed));
        when(fixture.runStore.claimForResume("run-1")).thenReturn(Optional.empty());
        when(fixture.approvalService.find("approval-1")).thenReturn(Optional.of(approval));

        AgentRuntimeResult result = fixture.runtime().resume("run-1", AgentEventListener.NOOP);

        assertEquals(AgentRunState.RUNNING, result.state());
        verify(fixture.toolRuntime, never()).executeApproved(any(), any(), any());
        verify(fixture.runControlStore).acquireSessionLease(anyString(), anyString(), anyString(), any());
        verify(fixture.runControlStore).releaseSessionLease(anyString(), anyString());
    }

    @Test
    void unexpectedFailureConvergesPersistedRunToFailed() {
        Fixture fixture = new Fixture();
        AtomicReference<AgentRunRecord> persisted = new AtomicReference<>();
        when(fixture.runStore.create(any())).thenAnswer(invocation -> {
            AgentRunRecord record = invocation.getArgument(0);
            persisted.set(record);
            return record;
        });
        when(fixture.runStore.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        when(fixture.runStore.update(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.UnaryOperator<AgentRunRecord> updater = invocation.getArgument(1);
            AgentRunRecord updated = updater.apply(persisted.get());
            persisted.set(updated);
            return updated;
        });
        when(fixture.contextManager.project(anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("context database failed"));

        AgentRuntimeResult result = fixture.runtime().run(
                new AgentRequest("session-1", "user-1", "question", Map.of()),
                AgentEventListener.NOOP
        );

        assertEquals(AgentRunState.FAILED, result.state());
        assertEquals(AgentStopReason.INTERNAL_ERROR, result.stopReason());
        assertEquals(AgentRunState.FAILED, persisted.get().state());
        verify(fixture.runControlStore).releaseSessionLease(anyString(), anyString());
    }

    @Test
    void userPauseResumesTheSameRunIdFromPersistedCheckpoint() throws Exception {
        Fixture fixture = new Fixture();
        AtomicBoolean pauseSignal = new AtomicBoolean(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CountDownLatch firstModelStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstModel = new CountDownLatch(1);

        when(fixture.runStore.create(any())).thenAnswer(invocation -> {
            AgentRunRecord created = invocation.getArgument(0);
            fixture.persisted.set(created);
            return created;
        });
        when(fixture.runStore.find(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(fixture.persisted.get()));
        when(fixture.runStore.update(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.UnaryOperator<AgentRunRecord> updater = invocation.getArgument(1);
            AgentRunRecord updated = updater.apply(fixture.persisted.get());
            fixture.persisted.set(updated);
            return updated;
        });
        when(fixture.runStore.claimPausedForResume(anyString())).thenAnswer(invocation -> {
            AgentRunRecord current = fixture.persisted.get();
            if (current == null || current.state() != AgentRunState.PAUSED) {
                return Optional.empty();
            }
            AgentRunRecord claimed = current.claimedPausedForResume();
            fixture.persisted.set(claimed);
            return Optional.of(claimed);
        });
        when(fixture.runControlStore.requestPause(anyString())).thenAnswer(invocation -> {
            pauseSignal.set(true);
            return true;
        });
        when(fixture.runControlStore.pauseRequested(anyString())).thenAnswer(invocation -> pauseSignal.get());
        when(fixture.runControlStore.clearPauseRequest(anyString())).thenAnswer(invocation -> {
            pauseSignal.set(false);
            return true;
        });
        when(fixture.contextManager.project(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new AgentContextView(List.of(), 0, 0, false));
        when(fixture.modelGateway.nextTurn(any())).thenAnswer(invocation -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) {
                firstModelStarted.countDown();
                assertTrue(releaseFirstModel.await(5, TimeUnit.SECONDS));
                return finalTurn("first model result must not complete the paused run");
            }
            return finalTurn("resumed from the same checkpoint");
        });

        DefaultAgentRuntime runtime = fixture.runtime();
        CompletableFuture<AgentRuntimeResult> firstExecution = CompletableFuture.supplyAsync(() -> runtime.run(
                new AgentRequest("session-pause", "user-1", "long running task", Map.of()),
                AgentEventListener.NOOP
        ));

        assertTrue(firstModelStarted.await(5, TimeUnit.SECONDS));
        String originalRunId = fixture.persisted.get().runId();
        assertTrue(runtime.pause(originalRunId));
        releaseFirstModel.countDown();

        AgentRuntimeResult paused = firstExecution.get(5, TimeUnit.SECONDS);
        assertEquals(originalRunId, paused.runId());
        assertEquals(AgentRunState.PAUSED, paused.state());
        assertEquals(AgentStopReason.PAUSED, paused.stopReason());
        assertEquals(AgentRunPhase.MODEL_CALL, fixture.persisted.get().phase());
        assertTrue(fixture.persisted.get().budgetSnapshot().executionPaused());

        AgentRuntimeResult resumed = runtime.resume(originalRunId, AgentEventListener.NOOP);

        assertEquals(originalRunId, resumed.runId());
        assertEquals(AgentRunState.COMPLETED, resumed.state());
        assertEquals("resumed from the same checkpoint", resumed.answer());
        assertEquals(1, fixture.persisted.get().resumeCount());
        assertEquals(2, modelCalls.get());
        verify(fixture.runStore).claimPausedForResume(originalRunId);
    }

    @Test
    void pauseRequestedCheckpointCanRecoverAfterTheOriginalWorkerDisappears() {
        Fixture fixture = new Fixture();
        AgentRunLimits limits = AgentRunLimits.from(fixture.properties);
        AgentRunBudget budget = new AgentRunBudget(limits);
        budget.pauseExecution();
        AgentRunRecord pauseRequested = AgentRunRecord.create(
                        "run-pause-requested", "run-pause-requested", "session-pause-requested",
                        new AgentRequest("session-pause-requested", "user-1", "continue after crash", Map.of()),
                        new AgentExecutionProfile("default", "prompt", Set.of(), limits, false),
                        budget.snapshot())
                .pauseRequested(budget.snapshot());
        fixture.persisted.set(pauseRequested);
        when(fixture.runStore.find("run-pause-requested"))
                .thenAnswer(invocation -> Optional.of(fixture.persisted.get()));
        when(fixture.runStore.claimPausedForResume("run-pause-requested")).thenAnswer(invocation -> {
            AgentRunRecord claimed = fixture.persisted.get().claimedPausedForResume();
            fixture.persisted.set(claimed);
            return Optional.of(claimed);
        });
        when(fixture.runStore.update(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.UnaryOperator<AgentRunRecord> updater = invocation.getArgument(1);
            AgentRunRecord updated = updater.apply(fixture.persisted.get());
            fixture.persisted.set(updated);
            return updated;
        });
        when(fixture.runControlStore.clearPauseRequest("run-pause-requested")).thenReturn(true);
        when(fixture.contextManager.project(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new AgentContextView(List.of(), 0, 0, false));
        when(fixture.modelGateway.nextTurn(any())).thenReturn(finalTurn("recovered after pause request crash"));

        AgentRuntimeResult resumed = fixture.runtime().resume(
                "run-pause-requested", AgentEventListener.NOOP
        );

        assertEquals("run-pause-requested", resumed.runId());
        assertEquals(AgentRunState.COMPLETED, resumed.state());
        assertEquals("recovered after pause request crash", resumed.answer());
        assertEquals(1, fixture.persisted.get().resumeCount());
    }

    @Test
    void abandoningPausedRunClosesPendingToolCallForTheNextConversationRun() {
        Fixture fixture = new Fixture();
        AgentRunLimits limits = AgentRunLimits.from(fixture.properties);
        AgentRunBudget budget = new AgentRunBudget(limits);
        ToolCallRequest pending = new ToolCallRequest(
                "ticket_close", "tool-call-abandoned", Map.of("id", "T1")
        );
        AgentRunRecord paused = AgentRunRecord.create(
                        "run-abandoned", "run-abandoned", "session-shared",
                        new AgentRequest("session-shared", "user-1", "close ticket", Map.of()),
                        new AgentExecutionProfile("restricted", "prompt", Set.of("ticket_close"), limits, false),
                        budget.snapshot())
                .checkpoint(
                        AgentRunPhase.EXECUTING_TOOL, pending, List.of(), List.of(), false, budget.snapshot()
                );
        budget.pauseExecution();
        fixture.persisted.set(paused.paused(budget.snapshot()));
        when(fixture.runStore.find("run-abandoned"))
                .thenAnswer(invocation -> Optional.of(fixture.persisted.get()));
        when(fixture.runStore.update(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.UnaryOperator<AgentRunRecord> updater = invocation.getArgument(1);
            AgentRunRecord updated = updater.apply(fixture.persisted.get());
            fixture.persisted.set(updated);
            return updated;
        });
        when(fixture.runControlStore.requestCancellation("run-abandoned")).thenReturn(true);
        when(fixture.timelineStore.loadMessages("session-shared", 10_000)).thenReturn(List.of());

        boolean cancelled = fixture.runtime().cancel("run-abandoned");

        assertTrue(cancelled);
        assertEquals(AgentRunState.REJECTED, fixture.persisted.get().state());
        assertEquals(1, fixture.persisted.get().toolResults().size());
        ToolCallResult closure = fixture.persisted.get().toolResults().get(0);
        assertEquals("RUN_ABANDONED", closure.metadata().get("outcome"));
        assertEquals(false, closure.metadata().get("outcomeKnown"));
        verify(fixture.timelineStore).appendMessages(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(messages -> messages.size() == 1
                        && messages.get(0).type() == AgentMessageType.TOOL_RESULT
                        && "tool-call-abandoned".equals(messages.get(0).toolCallId()))
        );
        verify(fixture.toolRuntime, never()).execute(anyString(), anyString(), anyString(), any(), any(), any());
        verify(fixture.toolRuntime, never()).reconcileUncertain(any());
    }

    private static AgentModelTurn finalTurn(String answer) {
        return new AgentModelTurn(
                answer,
                List.of(),
                answer,
                new LlmUsage(10, 5, 15, 0, 0, "test-model", "test"),
                "stop"
        );
    }

    @Test
    void staleToolExecutionCheckpointRequiresManualReviewInsteadOfRepeatingSideEffect() {
        Fixture fixture = new Fixture();
        AgentRunLimits limits = AgentRunLimits.from(fixture.properties);
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "restricted", "prompt", Set.of("ticket_close"), limits, false
        );
        AgentRunBudget budget = new AgentRunBudget(limits);
        ToolCallRequest pending = new ToolCallRequest("ticket_close", "call-1", Map.of("id", "T1"));
        AtomicReference<AgentRunRecord> persisted = new AtomicReference<>(
                AgentRunRecord.create(
                                "run-1", "run-1", "session-1",
                                new AgentRequest("session-1", "user-1", "close ticket", Map.of()),
                                profile, budget.snapshot())
                        .checkpoint(AgentRunPhase.EXECUTING_TOOL, pending, List.of(), List.of(), false,
                                budget.snapshot())
        );
        when(fixture.runStore.find("run-1")).thenAnswer(invocation -> Optional.of(persisted.get()));
        when(fixture.runStore.update(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.UnaryOperator<AgentRunRecord> updater = invocation.getArgument(1);
            AgentRunRecord updated = updater.apply(persisted.get());
            persisted.set(updated);
            return updated;
        });

        AgentRuntimeResult result = fixture.runtime().resume("run-1", AgentEventListener.NOOP);

        assertEquals(AgentRunState.MANUAL_REVIEW, result.state());
        assertEquals(AgentRunState.MANUAL_REVIEW, persisted.get().state());
        assertEquals(1, persisted.get().toolResults().size());
        assertEquals("MANUAL_REVIEW", persisted.get().toolResults().get(0).metadata().get("outcome"));
        verify(fixture.timelineStore).appendMessages(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(messages -> messages.size() == 1
                        && messages.get(0).type() == AgentMessageType.TOOL_RESULT
                        && "call-1".equals(messages.get(0).toolCallId()))
        );
        verify(fixture.toolRuntime, never()).execute(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void approvedManualReviewClosesToolPairBeforeRunTerminates() {
        Fixture fixture = new Fixture();
        AgentRunLimits limits = AgentRunLimits.from(fixture.properties);
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "restricted", "prompt", Set.of("ticket_close"), limits, false
        );
        ToolCallRequest request = new ToolCallRequest("ticket_close", "call-review", Map.of("id", "T1"));
        AgentRunBudget budget = new AgentRunBudget(limits);
        AgentRunRecord waiting = AgentRunRecord.create(
                        "run-review", "run-review", "session-review",
                        new AgentRequest("session-review", "user-1", "close ticket", Map.of()),
                        profile, budget.snapshot())
                .waitingForApproval("approval-review", request, List.of(), List.of(), false, budget.snapshot());
        fixture.persisted.set(waiting);
        ApprovalRecord approval = new ApprovalRecord(
                "approval-review", "run-review", "session-review", request, "high risk",
                ApprovalStatus.APPROVED, "reviewer", "approved", Instant.now(), Instant.now().plusSeconds(60)
        );
        ToolDefinition definition = new ToolDefinition(
                "ticket_close", "close ticket", "{}", ToolRiskLevel.HIGH, Map.of()
        );
        ToolCallResult uncertain = new ToolCallResult(
                "ticket_close", false, "", "remote outcome is unknown",
                Map.of("manualReview", true, "retryable", false)
        );
        AgentToolRuntimeResult manualReview = new AgentToolRuntimeResult(
                AgentToolExecutionStatus.MANUAL_REVIEW, request, uncertain,
                GuardrailAction.ALLOW, "tool outcome requires manual review", "", false
        );
        when(fixture.runStore.find("run-review")).thenAnswer(invocation -> Optional.of(fixture.persisted.get()));
        when(fixture.runStore.claimForResume("run-review")).thenAnswer(invocation -> {
            AgentRunRecord claimed = fixture.persisted.get().claimedForResume();
            fixture.persisted.set(claimed);
            return Optional.of(claimed);
        });
        when(fixture.runStore.update(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.UnaryOperator<AgentRunRecord> updater = invocation.getArgument(1);
            AgentRunRecord updated = updater.apply(fixture.persisted.get());
            fixture.persisted.set(updated);
            return updated;
        });
        when(fixture.approvalService.find("approval-review")).thenReturn(Optional.of(approval));
        when(fixture.capabilityRegistry.findCapability("ticket_close")).thenReturn(Optional.of(definition));
        when(fixture.toolRuntime.executeApproved(any(), any(), any())).thenReturn(manualReview);

        AgentRuntimeResult result = fixture.runtime().resume("run-review", AgentEventListener.NOOP);

        assertEquals(AgentRunState.MANUAL_REVIEW, result.state());
        assertEquals(1, fixture.persisted.get().toolResults().size());
        ToolCallResult persistedResult = fixture.persisted.get().toolResults().get(0);
        assertEquals(false, persistedResult.success());
        assertEquals("MANUAL_REVIEW", persistedResult.metadata().get("outcome"));
        assertEquals(1, fixture.persisted.get().budgetSnapshot().toolCalls());
        verify(fixture.timelineStore).appendMessages(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(messages -> messages.size() == 1
                        && messages.get(0).type() == AgentMessageType.TOOL_RESULT
                        && "call-review".equals(messages.get(0).toolCallId()))
        );
    }

    @Test
    void persistedSuccessfulToolResultIsReusedWithoutDuplicateTimelineMessage() {
        Fixture fixture = new Fixture();
        ToolCallResult result = new ToolCallResult(
                "ticket_close", true, "closed", "", Map.of("source", "persisted")
        );
        AgentMessage existingResult = new AgentMessage(
                "message-1", "session-1", "run-1", 2, AgentMessageType.TOOL_RESULT,
                "closed", "call-1", "ticket_close", Map.of(), Map.of("success", true), 2, Instant.now()
        );
        when(fixture.timelineStore.loadMessages("session-1", 10_000)).thenReturn(List.of(existingResult));

        AgentRuntimeResult recovered = fixture.recoverCertainToolExecution(ToolExecutionState.SUCCEEDED, result);

        assertEquals(AgentRunState.COMPLETED, recovered.state());
        assertEquals(1, fixture.persisted.get().toolResults().size());
        assertEquals(true, fixture.persisted.get().toolResults().get(0).success());
        assertEquals(1, fixture.persisted.get().budgetSnapshot().toolCalls());
        verify(fixture.timelineStore, never()).appendMessages(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(messages -> messages.stream()
                        .anyMatch(message -> message.type() == AgentMessageType.TOOL_RESULT))
        );
        verify(fixture.toolRuntime, never()).execute(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void persistedFailedToolResultIsReturnedToModelForReplanning() {
        Fixture fixture = new Fixture();
        ToolCallResult result = new ToolCallResult(
                "ticket_close", false, "", "remote rejected close", Map.of("source", "persisted")
        );

        AgentRuntimeResult recovered = fixture.recoverCertainToolExecution(ToolExecutionState.FAILED, result);

        assertEquals(AgentRunState.COMPLETED, recovered.state());
        assertEquals(1, fixture.persisted.get().toolResults().size());
        assertEquals(false, fixture.persisted.get().toolResults().get(0).success());
        assertEquals("remote rejected close", fixture.persisted.get().toolResults().get(0).errorMessage());
        assertEquals(1, fixture.persisted.get().budgetSnapshot().toolCalls());
        verify(fixture.modelGateway).nextTurn(any());
        verify(fixture.toolRuntime, never()).execute(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void staleOrderCareToolCanBeReconciledBeforeManualReview() {
        Fixture fixture = new Fixture();
        ToolCallResult reconciled = new ToolCallResult(
                "floworder_recovery_execute", true, "reconciled", "",
                Map.of("recoveredAfterCrash", true, "actionRequestId", "act-1")
        );

        AgentRuntimeResult recovered = fixture.recoverUncertainToolExecution(reconciled);

        assertEquals(AgentRunState.COMPLETED, recovered.state());
        assertEquals(true, fixture.persisted.get().toolResults().get(0).success());
        verify(fixture.toolRuntime).reconcileUncertain(any(ToolExecutionRecord.class));
        verify(fixture.toolRuntime, never()).execute(anyString(), anyString(), anyString(), any(), any(), any());
    }

    private static final class Fixture {
        private final AgentProperties properties = new AgentProperties();
        private final AtomicReference<AgentRunRecord> persisted = new AtomicReference<>();
        private final AgentTimelineStore timelineStore = mock(AgentTimelineStore.class);
        private final AgentRunStore runStore = mock(AgentRunStore.class);
        private final ToolExecutionStore toolExecutionStore = mock(ToolExecutionStore.class);
        private final AgentContextManager contextManager = mock(AgentContextManager.class);
        private final AgentModelGateway modelGateway = mock(AgentModelGateway.class);
        private final AgentCapabilityRegistry capabilityRegistry = mock(AgentCapabilityRegistry.class);
        private final AgentToolRuntime toolRuntime = mock(AgentToolRuntime.class);
        private final GuardrailService guardrailService = mock(GuardrailService.class);
        private final ApprovalService approvalService = mock(ApprovalService.class);
        private final AgentRunControlStore runControlStore = mock(AgentRunControlStore.class);
        private final MemoryService memoryService = mock(MemoryService.class);

        private Fixture() {
            when(capabilityRegistry.listCapabilities()).thenReturn(List.of());
            when(guardrailService.checkInput(anyString()))
                    .thenReturn(GuardrailDecision.allow(GuardrailStage.INPUT, "ok"));
            when(guardrailService.checkOutput(anyString()))
                    .thenReturn(GuardrailDecision.allow(GuardrailStage.OUTPUT, "ok"));
            when(runControlStore.renewSessionLease(anyString(), anyString(), any())).thenReturn(true);
            when(runControlStore.cancellationRequested(anyString())).thenReturn(false);
            when(timelineStore.loadEvents(anyString(), anyInt())).thenReturn(List.of());
            when(timelineStore.appendEvent(anyString(), anyString(), anyString(), any()))
                    .thenAnswer(invocation -> new AgentEvent(
                            "event", invocation.getArgument(2), invocation.getArgument(0), 1,
                            ((AgentEventDraft) invocation.getArgument(3)).type(), "event", Map.of(), Instant.now()
                    ));
        }

        private DefaultAgentRuntime runtime() {
            return new DefaultAgentRuntime(
                    properties, timelineStore, runStore, toolExecutionStore, contextManager, modelGateway,
                    capabilityRegistry, toolRuntime, guardrailService, approvalService,
                    new ConservativeTokenEstimator(), runControlStore, memoryService,
                    new ConfiguredLlmCostCalculator(properties), new ToolResultProjector(properties)
            );
        }

        private AgentRuntimeResult recoverCertainToolExecution(ToolExecutionState state, ToolCallResult result) {
            AgentRunLimits limits = AgentRunLimits.from(properties);
            AgentExecutionProfile profile = new AgentExecutionProfile(
                    "restricted", "prompt", Set.of("ticket_close"), limits, false
            );
            AgentRunBudget budget = new AgentRunBudget(limits);
            ToolCallRequest pending = new ToolCallRequest("ticket_close", "call-1", Map.of("id", "T1"));
            persisted.set(
                    AgentRunRecord.create(
                                    "run-1", "run-1", "session-1",
                                    new AgentRequest("session-1", "user-1", "close ticket", Map.of()),
                                    profile, budget.snapshot())
                            .checkpoint(AgentRunPhase.EXECUTING_TOOL, pending, List.of(), List.of(), false,
                                    budget.snapshot())
            );
            when(runStore.find("run-1")).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
            when(runStore.update(anyString(), any())).thenAnswer(invocation -> {
                java.util.function.UnaryOperator<AgentRunRecord> updater = invocation.getArgument(1);
                AgentRunRecord updated = updater.apply(persisted.get());
                persisted.set(updated);
                return updated;
            });
            Instant now = Instant.now();
            when(toolExecutionStore.findToolExecution("call-1")).thenReturn(Optional.of(
                    new ToolExecutionRecord(
                            "call-1", "run-1", "ticket_close", state, pending, result,
                            1, result.errorMessage(), now, now
                    )
            ));
            when(contextManager.project(anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(new AgentContextView(List.of(), 0, 0, false));
            when(modelGateway.nextTurn(any())).thenReturn(new AgentModelTurn(
                    "replanned answer", List.of(), "replanned answer",
                    new LlmUsage(10, 5, 15, 0, 0, "test-model", "test"), "stop"
            ));
            return runtime().resume("run-1", AgentEventListener.NOOP);
        }

        private AgentRuntimeResult recoverUncertainToolExecution(ToolCallResult resolvedResult) {
            AgentRunLimits limits = AgentRunLimits.from(properties);
            AgentExecutionProfile profile = new AgentExecutionProfile(
                    "ordercare", "prompt", Set.of("floworder_recovery_execute"), limits, false
            );
            AgentRunBudget budget = new AgentRunBudget(limits);
            ToolCallRequest pending = new ToolCallRequest(
                    "floworder_recovery_execute", "tool-exec-1", Map.of("proposalId", "prop-1")
            );
            persisted.set(AgentRunRecord.create(
                            "run-1", "run-1", "session-1",
                            new AgentRequest("session-1", "user-1", "recover order", Map.of()),
                            profile, budget.snapshot())
                    .checkpoint(AgentRunPhase.EXECUTING_TOOL, pending, List.of(), List.of(), false,
                            budget.snapshot()));
            when(runStore.find("run-1")).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
            when(runStore.update(anyString(), any())).thenAnswer(invocation -> {
                java.util.function.UnaryOperator<AgentRunRecord> updater = invocation.getArgument(1);
                AgentRunRecord updated = updater.apply(persisted.get());
                persisted.set(updated);
                return updated;
            });
            ToolExecutionRecord running = ToolExecutionRecord.running("run-1", pending);
            ToolExecutionRecord succeeded = running.withResult(ToolExecutionState.SUCCEEDED, resolvedResult, "");
            when(toolExecutionStore.findToolExecution("tool-exec-1")).thenReturn(Optional.of(running));
            when(toolRuntime.reconcileUncertain(running)).thenReturn(succeeded);
            when(contextManager.project(anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(new AgentContextView(List.of(), 0, 0, false));
            when(modelGateway.nextTurn(any())).thenReturn(new AgentModelTurn(
                    "recovered answer", List.of(), "recovered answer",
                    new LlmUsage(10, 5, 15, 0, 0, "test-model", "test"), "stop"
            ));
            return runtime().resume("run-1", AgentEventListener.NOOP);
        }
    }
}
