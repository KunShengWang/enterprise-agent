package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.tool.ToolCallRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAgentRuntimeStateTests {

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
        verify(fixture.runControlStore, never()).acquireSessionLease(anyString(), anyString(), anyString(), any());
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
        verify(fixture.toolRuntime, never()).execute(anyString(), anyString(), anyString(), any(), any(), any());
    }

    private static final class Fixture {
        private final AgentProperties properties = new AgentProperties();
        private final AgentTimelineStore timelineStore = mock(AgentTimelineStore.class);
        private final AgentRunStore runStore = mock(AgentRunStore.class);
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
                    properties, timelineStore, runStore, contextManager, modelGateway,
                    capabilityRegistry, toolRuntime, guardrailService, approvalService,
                    new ConservativeTokenEstimator(), runControlStore, memoryService
            );
        }
    }
}
