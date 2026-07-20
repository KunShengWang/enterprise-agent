package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchProjectionProperties;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.model.TaskEventCategory;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanEventRecord;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.workbench.model.ProjectedWorkEventDraft;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkProjectionSource;
import com.agent.platform.workbench.model.WorkProjectionClaim;
import com.agent.platform.workbench.model.WorkExecutionProjection;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.persistence.WorkEventProjectionStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedWorkEventProjectorTests {

    @Test
    void projectsThreeAuthoritativeSourcesAndKeepsModelDeltaOutOfWorkEvents() {
        WorkEventProjectionStore projection = mock(WorkEventProjectionStore.class);
        AgentTimelineStore timeline = mock(AgentTimelineStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        TaskEventStore incidents = mock(TaskEventStore.class);
        IncidentStore incidentStore = mock(IncidentStore.class);
        IncidentRecoveryPlanStore recovery = mock(IncidentRecoveryPlanStore.class);
        WorkbenchProjectionProperties properties = enabledProperties();
        List<WorkProjectionSource> sources = List.of(
                new WorkProjectionSource("work-1", "AGENT_RUN", "run-1"),
                new WorkProjectionSource("work-2", "INCIDENT", "inc-1"),
                new WorkProjectionSource("work-3", "RECOVERY_PLAN", "plan-1"));
        when(projection.claimProjectionSources(anyString(), any(), anyInt())).thenAnswer(invocation -> {
            String owner = invocation.getArgument(0);
            Instant until = invocation.getArgument(1);
            return sources.stream().map(source -> new WorkProjectionClaim(source, owner, 1, until)).toList();
        });
        when(projection.projectionCursor(anyString(), anyString(), anyString())).thenReturn(-1L);
        Instant now = Instant.now();
        when(timeline.loadEventsAfter("run-1", -1, properties.getEventBatchSize())).thenReturn(List.of(
                new AgentEvent("run-event-0", "run-1", "session", 0, AgentEventType.MODEL_DELTA,
                        "token", Map.of(), now),
                new AgentEvent("run-event-1", "run-1", "session", 1, AgentEventType.TOOL_COMPLETED,
                        "tool complete", Map.of("tool", "inspect"), now.plusMillis(1))));
        when(incidents.loadEventsAfter("inc-1", -1, properties.getEventBatchSize())).thenReturn(List.of(
                new TaskEventRecord("incident-event-0", "inc-1", "task-1", "child-run", 0,
                        TaskEventType.EVIDENCE_SUBMITTED, TaskEventCategory.COMMUNICATION,
                        TaskEventActorType.AGENT, "order-specialist", "ORDER_SPECIALIST", "REVIEWER", 0,
                        "inc-1", "cause-1", "idem-1", Map.of("summary", "evidence submitted"), now)));
        when(recovery.loadEventsAfter("plan-1", -1, properties.getEventBatchSize())).thenReturn(List.of(
                new RecoveryPlanEventRecord("plan-event-0", "plan-1", "inc-1", 0,
                        "RECOVERY_PLAN_CREATED", Map.of("status", "CREATED"), now)));

        var result = new UnifiedWorkEventProjector(
                projection, timeline, runs, incidents, incidentStore, recovery, properties).projectOnce();

        assertEquals(3, result.sourceCount());
        assertEquals(3, result.projectedEventCount());
        assertEquals(0, result.failedSourceCount());
        verify(projection).advanceProjectionCursor(any(WorkProjectionClaim.class), org.mockito.ArgumentMatchers.eq(0L));
        ArgumentCaptor<ProjectedWorkEventDraft> drafts = ArgumentCaptor.forClass(ProjectedWorkEventDraft.class);
        verify(projection, times(3)).appendProjectedEvent(any(WorkProjectionClaim.class), drafts.capture());
        assertEquals(List.of(
                        WorkEventType.RUN_EVENT_PROJECTED,
                        WorkEventType.INCIDENT_EVENT_PROJECTED,
                        WorkEventType.RECOVERY_PLAN_EVENT_PROJECTED),
                drafts.getAllValues().stream().map(ProjectedWorkEventDraft::eventType).toList());
        assertTrue(drafts.getAllValues().stream().noneMatch(draft -> "MODEL_DELTA".equals(draft.phase())));
    }

    @Test
    void oneSourceFailureDoesNotChangeOrBlockOtherSourceProjection() {
        WorkEventProjectionStore projection = mock(WorkEventProjectionStore.class);
        AgentTimelineStore timeline = mock(AgentTimelineStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        TaskEventStore incidents = mock(TaskEventStore.class);
        IncidentStore incidentStore = mock(IncidentStore.class);
        IncidentRecoveryPlanStore recovery = mock(IncidentRecoveryPlanStore.class);
        WorkbenchProjectionProperties properties = enabledProperties();
        List<WorkProjectionSource> sources = List.of(
                new WorkProjectionSource("work-1", "AGENT_RUN", "run-1"),
                new WorkProjectionSource("work-2", "INCIDENT", "inc-1"));
        when(projection.claimProjectionSources(anyString(), any(), anyInt())).thenAnswer(invocation -> {
            String owner = invocation.getArgument(0);
            Instant until = invocation.getArgument(1);
            return sources.stream().map(source -> new WorkProjectionClaim(source, owner, 1, until)).toList();
        });
        when(projection.projectionCursor(anyString(), anyString(), anyString())).thenReturn(-1L);
        when(timeline.loadEventsAfter(anyString(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("runtime store unavailable"));
        when(incidents.loadEventsAfter(anyString(), anyLong(), anyInt())).thenReturn(List.of(
                new TaskEventRecord("incident-event", "inc-1", null, null, 0,
                        TaskEventType.INCIDENT_STATE_CHANGED, TaskEventCategory.LIFECYCLE,
                        TaskEventActorType.SYSTEM, "incident-store", null, null, 0,
                        "inc-1", null, "idem", Map.of(), Instant.now())));

        var result = new UnifiedWorkEventProjector(
                projection, timeline, runs, incidents, incidentStore, recovery, properties).projectOnce();

        assertEquals(1, result.projectedEventCount());
        assertEquals(1, result.failedSourceCount());
        verify(projection).appendProjectedEvent(any(WorkProjectionClaim.class), any(ProjectedWorkEventDraft.class));
    }

    @Test
    void mapsAuthoritativeTerminalStatesWithoutDependingOnTerminalEventOrder() {
        WorkEventProjectionStore projection = mock(WorkEventProjectionStore.class);
        AgentTimelineStore timeline = mock(AgentTimelineStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        TaskEventStore incidentEvents = mock(TaskEventStore.class);
        IncidentStore incidents = mock(IncidentStore.class);
        IncidentRecoveryPlanStore recovery = mock(IncidentRecoveryPlanStore.class);
        List<WorkProjectionSource> sources = List.of(
                new WorkProjectionSource("work-completed", "AGENT_RUN", "run-completed"),
                new WorkProjectionSource("work-failed", "AGENT_RUN", "run-failed"),
                new WorkProjectionSource("work-cancelled", "AGENT_RUN", "run-cancelled"),
                new WorkProjectionSource("work-incident", "INCIDENT", "incident-assessed"),
                new WorkProjectionSource("work-plan", "RECOVERY_PLAN", "plan-resolved"));
        when(projection.claimProjectionSources(anyString(), any(), anyInt())).thenAnswer(invocation -> {
            String owner = invocation.getArgument(0);
            Instant until = invocation.getArgument(1);
            return sources.stream().map(source -> new WorkProjectionClaim(source, owner, 1, until)).toList();
        });
        when(projection.projectionCursor(anyString(), anyString(), anyString())).thenReturn(-1L);
        AgentRunRecord completedRun = run("run-completed", AgentRunState.COMPLETED, "");
        AgentRunRecord failedRun = run("run-failed", AgentRunState.FAILED, "boom");
        AgentRunRecord cancelledRun = run("run-cancelled", AgentRunState.REJECTED, "CANCELLED");
        when(runs.find("run-completed")).thenReturn(java.util.Optional.of(completedRun));
        when(runs.find("run-failed")).thenReturn(java.util.Optional.of(failedRun));
        when(runs.find("run-cancelled")).thenReturn(java.util.Optional.of(cancelledRun));
        IncidentRecord incident = mock(IncidentRecord.class);
        when(incident.incidentId()).thenReturn("incident-assessed");
        when(incident.status()).thenReturn(IncidentStatus.ASSESSED);
        when(incident.version()).thenReturn(2L);
        when(incident.updatedAt()).thenReturn(Instant.now());
        when(incidents.find("incident-assessed")).thenReturn(java.util.Optional.of(incident));
        IncidentRecoveryPlanRecord plan = mock(IncidentRecoveryPlanRecord.class);
        when(plan.planId()).thenReturn("plan-resolved");
        when(plan.status()).thenReturn(RecoveryPlanStatus.COMPLETED);
        when(plan.outcome()).thenReturn(RecoveryPlanOutcome.RESOLVED);
        when(plan.version()).thenReturn(3L);
        when(plan.updatedAt()).thenReturn(Instant.now());
        when(recovery.find("plan-resolved")).thenReturn(java.util.Optional.of(plan));

        new UnifiedWorkEventProjector(projection, timeline, runs, incidentEvents, incidents, recovery,
                enabledProperties()).projectOnce();

        ArgumentCaptor<WorkExecutionProjection> states = ArgumentCaptor.forClass(WorkExecutionProjection.class);
        verify(projection, times(5)).reconcileExecutionState(any(WorkProjectionClaim.class), states.capture());
        assertEquals(List.of(
                        WorkExecutionState.COMPLETED,
                        WorkExecutionState.FAILED,
                        WorkExecutionState.CANCELLED,
                        WorkExecutionState.COMPLETED,
                        WorkExecutionState.COMPLETED),
                states.getAllValues().stream().map(WorkExecutionProjection::executionState).toList());
        assertEquals(List.of(
                        WorkOutcome.ANSWERED,
                        WorkOutcome.FAILED,
                        WorkOutcome.CANCELLED,
                        WorkOutcome.ASSESSED,
                        WorkOutcome.RESOLVED),
                states.getAllValues().stream().map(WorkExecutionProjection::outcome).toList());
    }

    private AgentRunRecord run(String runId, AgentRunState state, String failureReason) {
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.runId()).thenReturn(runId);
        when(run.state()).thenReturn(state);
        when(run.failureReason()).thenReturn(failureReason);
        when(run.version()).thenReturn(1L);
        when(run.resumeCount()).thenReturn(0);
        when(run.updatedAt()).thenReturn(Instant.now());
        return run;
    }

    private WorkbenchProjectionProperties enabledProperties() {
        WorkbenchProjectionProperties properties = new WorkbenchProjectionProperties();
        properties.setEnabled(true);
        return properties;
    }
}
