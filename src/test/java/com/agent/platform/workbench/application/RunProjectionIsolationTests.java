package com.agent.platform.workbench.application;

import com.agent.platform.config.*;
import com.agent.platform.runtime.*;
import com.agent.platform.workbench.model.*;
import com.agent.platform.workbench.persistence.*;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import com.agent.platform.workbench.budget.WorkItemBudgetGate;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RunProjectionIsolationTests {
    @Test
    void springConstructsAndRunsPublicProjectionAndRoutingWithoutAnyIncidentBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var projection = mock(WorkEventProjectionStore.class);
            var properties = properties();
            context.registerBean(WorkEventProjectionStore.class, () -> projection);
            context.registerBean(AgentTimelineStore.class, () -> mock(AgentTimelineStore.class));
            context.registerBean(AgentRunStore.class, () -> mock(AgentRunStore.class));
            context.registerBean(WorkbenchProjectionProperties.class, () -> properties);
            context.registerBean(DispatchStore.class, () -> mock(DispatchStore.class));
            context.registerBean(RoutingStore.class, () -> mock(RoutingStore.class));
            context.registerBean(WorkbenchStore.class, () -> mock(WorkbenchStore.class));
            context.registerBean(WorkbenchDispatchProperties.class, WorkbenchDispatchProperties::new);
            context.registerBean(UnifiedTaskRouter.class, () -> mock(UnifiedTaskRouter.class));
            context.registerBean(RoutePolicyValidator.class, () -> mock(RoutePolicyValidator.class));
            context.registerBean(RouteContextResolver.class, () -> mock(RouteContextResolver.class));
            context.registerBean(ExecutionTargetRegistry.class, ExecutionTargetRegistry::new);
            context.registerBean(WorkbenchRoutingProperties.class, WorkbenchRoutingProperties::new);
            context.registerBean(RoutingFailureInjector.class, () -> mock(RoutingFailureInjector.class));
            context.registerBean(RouteDecisionPostProcessor.class, () -> mock(RouteDecisionPostProcessor.class));
            context.registerBean(WorkItemBudgetGate.class, () -> WorkItemBudgetGate.NOOP);
            context.registerBean(ExecutionTargetCandidateResolver.class, ExecutionTargetCandidateResolver::new);
            context.register(UnifiedWorkEventProjector.class, RoutingCoordinator.class, RouteConfirmationService.class);
            context.refresh();
            assertNotNull(context.getBean(RoutingCoordinator.class));
            assertNotNull(context.getBean(RouteConfirmationService.class));
            assertEquals(0, context.getBean(UnifiedWorkEventProjector.class).projectOnce().failedSourceCount());
            verify(projection).claimProjectionSources(anyString(), any(), anyInt(), eq(Set.of("AGENT_RUN")));
            assertTrue(context.getBeansOfType(WorkEventProjectionContributor.class).isEmpty());
        }
    }

    @Test
    void generalAndProcurementReplayUsePersistedCursorsAfterFailureAndKeepDeltasSeparate() {
        for (String runId : List.of("general-run", "procurement-run")) {
            var store = mock(WorkEventProjectionStore.class);
            var timeline = mock(AgentTimelineStore.class);
            var runs = mock(AgentRunStore.class);
            var source = new WorkProjectionSource("work-" + runId, "AGENT_RUN", runId);
            var claim = new WorkProjectionClaim(source, "owner", 7, Instant.now().plusSeconds(60));
            when(store.claimProjectionSources(anyString(), any(), anyInt(), eq(Set.of("AGENT_RUN"))))
                    .thenReturn(List.of(claim));
            AtomicLong cursor = new AtomicLong(-1);
            when(store.projectionCursor(source.workItemId(), "AGENT_RUN", runId)).thenAnswer(i -> cursor.get());
            doAnswer(i -> { cursor.accumulateAndGet(i.getArgument(1), Math::max); return null; })
                    .when(store).advanceProjectionCursor(eq(claim), anyLong());
            var events = List.of(event(runId, 0, AgentEventType.RUN_STARTED),
                    event(runId, 1, AgentEventType.MODEL_DELTA), event(runId, 2, AgentEventType.HEARTBEAT),
                    event(runId, 3, AgentEventType.RUN_COMPLETED));
            when(timeline.loadEventsAfter(eq(runId), anyLong(), anyInt())).thenAnswer(i ->
                    events.stream().filter(e -> e.sequence() > (long) i.getArgument(1)).toList());
            var persisted = new LinkedHashMap<String, ProjectedWorkEventDraft>();
            var failOnce = new AtomicBoolean(true);
            when(store.appendProjectedEvent(eq(claim), any())).thenAnswer(i -> {
                ProjectedWorkEventDraft draft = i.getArgument(1);
                if (draft.sourceSequence() == 3 && failOnce.getAndSet(false)) throw new IllegalStateException("simulated crash before append");
                persisted.putIfAbsent(draft.sourceEventId(), draft);
                cursor.accumulateAndGet(draft.sourceSequence(), Math::max);
                return null;
            });
            AgentRunRecord run = mock(AgentRunRecord.class);
            when(run.runId()).thenReturn(runId);
            when(run.state()).thenReturn(AgentRunState.COMPLETED);
            when(run.version()).thenReturn(9L);
            when(run.resumeCount()).thenReturn(2);
            when(run.updatedAt()).thenReturn(Instant.now());
            when(runs.find(runId)).thenReturn(Optional.of(run));
            assertEquals(1, new UnifiedWorkEventProjector(store, timeline, runs, List.of(), properties()).projectOnce().failedSourceCount());
            assertEquals(2, cursor.get());
            var recovered = new UnifiedWorkEventProjector(store, timeline, runs, List.of(), properties());
            assertEquals(1, recovered.projectOnce().projectedEventCount());
            assertEquals(0, recovered.projectOnce().projectedEventCount());
            assertEquals(3, cursor.get());
            assertEquals(List.of(0L, 3L), persisted.values().stream().map(ProjectedWorkEventDraft::sourceSequence).toList());
            verify(store, times(3)).releaseProjectionClaim(claim);
            verify(store, times(2)).reconcileExecutionState(eq(claim), argThat(p -> p.sourceVersion() == 9
                    && p.sourceAttempt() == 2 && p.executionState() == WorkExecutionState.COMPLETED
                    && p.outcome() == WorkOutcome.ANSWERED));
        }
    }

    @Test
    void contributorCannotOverrideRunOrRegisterArbitrarySources() {
        for (String type : List.of("AGENT_RUN", "ARBITRARY_PLUGIN")) {
            WorkEventProjectionContributor extension = mock(WorkEventProjectionContributor.class);
            when(extension.sourceTypes()).thenReturn(Set.of(type));
            assertThrows(IllegalArgumentException.class, () -> new UnifiedWorkEventProjector(null, null, null, List.of(extension), properties()));
        }
    }

    private AgentEvent event(String runId, long sequence, AgentEventType type) {
        return new AgentEvent(runId + "-" + sequence, runId, "session", sequence, type, "content", Map.of(), Instant.now());
    }
    private WorkbenchProjectionProperties properties() {
        var properties = new WorkbenchProjectionProperties();
        properties.setEnabled(true);
        return properties;
    }
}
