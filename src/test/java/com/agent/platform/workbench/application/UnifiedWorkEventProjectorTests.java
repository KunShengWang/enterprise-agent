package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchProjectionProperties;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.model.TaskEventCategory;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanEventRecord;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.workbench.model.ProjectedWorkEventDraft;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkProjectionSource;
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
        TaskEventStore incidents = mock(TaskEventStore.class);
        IncidentRecoveryPlanStore recovery = mock(IncidentRecoveryPlanStore.class);
        WorkbenchProjectionProperties properties = enabledProperties();
        List<WorkProjectionSource> sources = List.of(
                new WorkProjectionSource("work-1", "AGENT_RUN", "run-1"),
                new WorkProjectionSource("work-2", "INCIDENT", "inc-1"),
                new WorkProjectionSource("work-3", "RECOVERY_PLAN", "plan-1"));
        when(projection.listProjectionSources(anyInt())).thenReturn(sources);
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
                projection, timeline, incidents, recovery, properties).projectOnce();

        assertEquals(3, result.sourceCount());
        assertEquals(3, result.projectedEventCount());
        assertEquals(0, result.failedSourceCount());
        verify(projection).advanceProjectionCursor("work-1", "AGENT_RUN", "run-1", 0);
        ArgumentCaptor<ProjectedWorkEventDraft> drafts = ArgumentCaptor.forClass(ProjectedWorkEventDraft.class);
        verify(projection, times(3)).appendProjectedEvent(anyString(), drafts.capture());
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
        TaskEventStore incidents = mock(TaskEventStore.class);
        IncidentRecoveryPlanStore recovery = mock(IncidentRecoveryPlanStore.class);
        WorkbenchProjectionProperties properties = enabledProperties();
        when(projection.listProjectionSources(anyInt())).thenReturn(List.of(
                new WorkProjectionSource("work-1", "AGENT_RUN", "run-1"),
                new WorkProjectionSource("work-2", "INCIDENT", "inc-1")));
        when(projection.projectionCursor(anyString(), anyString(), anyString())).thenReturn(-1L);
        when(timeline.loadEventsAfter(anyString(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("runtime store unavailable"));
        when(incidents.loadEventsAfter(anyString(), anyLong(), anyInt())).thenReturn(List.of(
                new TaskEventRecord("incident-event", "inc-1", null, null, 0,
                        TaskEventType.INCIDENT_STATE_CHANGED, TaskEventCategory.LIFECYCLE,
                        TaskEventActorType.SYSTEM, "incident-store", null, null, 0,
                        "inc-1", null, "idem", Map.of(), Instant.now())));

        var result = new UnifiedWorkEventProjector(
                projection, timeline, incidents, recovery, properties).projectOnce();

        assertEquals(1, result.projectedEventCount());
        assertEquals(1, result.failedSourceCount());
        verify(projection).appendProjectedEvent(anyString(), any(ProjectedWorkEventDraft.class));
    }

    private WorkbenchProjectionProperties enabledProperties() {
        WorkbenchProjectionProperties properties = new WorkbenchProjectionProperties();
        properties.setEnabled(true);
        return properties;
    }
}
