package com.agent.platform.workbench.web;

import com.agent.platform.config.WorkbenchStreamProperties;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.model.WorkLinkRelation;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedWorkEventStreamServiceTests {

    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant", "alice", Set.of("USER"));

    @Test
    void lastEventIdAndQueryCursorsResolveMonotonically() {
        assertEquals(new UnifiedWorkStreamCursor(12, 9),
                UnifiedWorkStreamCursor.resolve(10, 9, "w:12;r:7"));
        assertEquals(new UnifiedWorkStreamCursor(-1, -1),
                UnifiedWorkStreamCursor.parse("invalid"));
        assertEquals("w:12;r:9", new UnifiedWorkStreamCursor(12, 9).encode());
    }

    @Test
    void sseUsesCompositeResumeTokenAsEventId() {
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        AgentTimelineStore timeline = mock(AgentTimelineStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        when(workbench.findWorkItem(principal, "work-1")).thenReturn(Optional.of(work("")));
        when(workbench.loadEvents(principal, "work-1", -1, 500)).thenReturn(List.of(workEvent(0)));
        UnifiedWorkEventStreamService service = new UnifiedWorkEventStreamService(
                workbench, timeline, runs, new WorkbenchStreamProperties());

        var event = service.stream(principal, "work-1", new UnifiedWorkStreamCursor(-1, -1))
                .blockFirst(Duration.ofSeconds(2));

        assertEquals("work-event", event.event());
        assertEquals("w:0;r:-1", event.id());
        assertEquals(event.id(), event.data().resumeToken());
    }

    @Test
    void replaysWorkEventsAndOnlyPrimaryRunModelDelta() {
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        AgentTimelineStore timeline = mock(AgentTimelineStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        AgentWorkItem work = work("run-primary");
        when(workbench.findWorkItem(principal, "work-1")).thenReturn(Optional.of(work));
        when(workbench.loadEvents(principal, "work-1", -1, 500)).thenReturn(List.of(workEvent(0)));
        when(workbench.listLinks(principal, "work-1")).thenReturn(List.of(
                new WorkLink("work-1", "dispatch", WorkLinkType.RUN, "run-primary",
                        WorkLinkRelation.PRIMARY, Instant.now()),
                new WorkLink("work-1", "dispatch-child", WorkLinkType.RUN, "run-child",
                        WorkLinkRelation.CHILD, Instant.now())));
        when(timeline.loadEventsAfter("run-primary", -1, 500)).thenReturn(List.of(
                runEvent("delta-1", 1, AgentEventType.MODEL_DELTA, "hello "),
                runEvent("tool-2", 2, AgentEventType.TOOL_COMPLETED, "tool"),
                runEvent("delta-3", 3, AgentEventType.MODEL_DELTA, "world")));
        UnifiedWorkEventStreamService service = new UnifiedWorkEventStreamService(
                workbench, timeline, runs, new WorkbenchStreamProperties());
        AtomicLong workCursor = new AtomicLong(-1);
        AtomicLong runCursor = new AtomicLong(-1);

        var items = service.poll(principal, "work-1", workCursor, runCursor, 1);

        assertEquals(List.of("WORK_EVENT", "MODEL_DELTA", "MODEL_DELTA"),
                items.stream().map(UnifiedWorkStreamItem::kind).toList());
        assertEquals("hello world", items.get(1).content() + items.get(2).content());
        assertEquals(0, workCursor.get());
        assertEquals(3, runCursor.get());
        assertEquals("w:0;r:3", items.get(2).resumeToken());
        verify(timeline, never()).loadEventsAfter(eq("run-child"), anyLong(), anyInt());
    }

    @Test
    void discoversRunningPrimaryRunByDispatchRequestBeforeWorkLinkExists() {
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        AgentTimelineStore timeline = mock(AgentTimelineStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        AgentWorkItem work = work("");
        AgentRequest request = new AgentRequest(work.conversationId(), principal.principalId(), "goal", Map.of(
                "workItemId", work.workItemId(),
                AgentRunStore.DISPATCH_REQUEST_METADATA_KEY, work.dispatchRequestId()));
        AgentRunRecord running = AgentRunRecord.create(
                "run-primary", "trace-primary", work.conversationId(), request);
        when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        when(workbench.loadEvents(principal, work.workItemId(), -1, 500)).thenReturn(List.of());
        when(workbench.listLinks(principal, work.workItemId())).thenReturn(List.of());
        when(runs.findByDispatchRequestId(work.dispatchRequestId())).thenReturn(Optional.of(running));
        when(timeline.loadEventsAfter("run-primary", -1, 500)).thenReturn(List.of(
                runEvent("delta-1", 0, AgentEventType.MODEL_DELTA, "live "),
                runEvent("delta-2", 1, AgentEventType.MODEL_DELTA, "answer")));
        UnifiedWorkEventStreamService service = new UnifiedWorkEventStreamService(
                workbench, timeline, runs, new WorkbenchStreamProperties());

        AtomicLong runCursor = new AtomicLong(-1);
        var first = service.poll(principal, work.workItemId(), new AtomicLong(-1), runCursor, 1);
        var reconnect = service.poll(principal, work.workItemId(), new AtomicLong(-1), runCursor, 2);
        AgentRunRecord completed = running.finished(AgentRunState.COMPLETED,
                com.agent.platform.runtime.AgentRunPhase.FINISHED, "live answer", "",
                List.of(), List.of(), false, false);

        assertEquals(AgentRunState.RUNNING, running.state());
        assertEquals("live answer", first.stream().map(UnifiedWorkStreamItem::content).reduce("", String::concat));
        assertEquals(completed.answer(), first.stream().map(UnifiedWorkStreamItem::content).reduce("", String::concat));
        assertTrue(reconnect.isEmpty());
        assertEquals(1, runCursor.get());
    }

    @Test
    void incidentChildRunWithoutPrimaryRunLinkNeverEntersMainAnswer() {
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        AgentTimelineStore timeline = mock(AgentTimelineStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        AgentWorkItem work = work("");
        when(workbench.findWorkItem(principal, "work-1")).thenReturn(Optional.of(work));
        when(workbench.loadEvents(principal, "work-1", -1, 500)).thenReturn(List.of());
        when(workbench.listLinks(principal, "work-1")).thenReturn(List.of(
                new WorkLink("work-1", "dispatch", WorkLinkType.INCIDENT, "incident-1",
                        WorkLinkRelation.PRIMARY, Instant.now())));
        UnifiedWorkEventStreamService service = new UnifiedWorkEventStreamService(
                workbench, timeline, runs, new WorkbenchStreamProperties());

        var items = service.poll(principal, "work-1", new AtomicLong(-1), new AtomicLong(-1), 1);

        assertTrue(items.isEmpty());
        verify(timeline, never()).loadEventsAfter(eq("child-run"), anyLong(), anyInt());
    }

    @Test
    void sequenceGapIsExplicitAndCursorDoesNotJump() {
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        AgentTimelineStore timeline = mock(AgentTimelineStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        when(workbench.findWorkItem(principal, "work-1")).thenReturn(Optional.of(work("")));
        when(workbench.loadEvents(principal, "work-1", 0, 500)).thenReturn(List.of(workEvent(2)));
        UnifiedWorkEventStreamService service = new UnifiedWorkEventStreamService(
                workbench, timeline, runs, new WorkbenchStreamProperties());
        AtomicLong workCursor = new AtomicLong(0);

        var items = service.poll(principal, "work-1", workCursor, new AtomicLong(-1), 1);

        assertEquals(1, items.size());
        assertEquals("GAP", items.get(0).kind());
        assertEquals(1L, items.get(0).payload().get("expectedSequence"));
        assertEquals(0, workCursor.get());
    }

    private AgentWorkItem work(String activeRunId) {
        Instant now = Instant.now();
        return new AgentWorkItem(
                "work-1", "conversation", principal.tenantId(), principal.principalId(), "goal", "goal",
                WorkControlState.DISPATCHED, WorkExecutionState.RUNNING, WorkOutcome.UNDETERMINED,
                "GENERAL_AGENT", activeRunId, "", "", "decision", "input", "", "route", 1,
                now, null, "", "dispatch", 1, 1, now, now, null);
    }

    private WorkEvent workEvent(long sequence) {
        Instant now = Instant.now();
        return new WorkEvent(
                "work-event-" + sequence, "work-1", sequence, "WORK_ITEM", "work-1",
                "source-event-" + sequence, sequence, WorkEventType.ROUTING_DECIDED,
                "ROUTED", "route decided", Map.of(), "work-1", "input", now, now);
    }

    private AgentEvent runEvent(String eventId, long sequence, AgentEventType type, String content) {
        return new AgentEvent(eventId, "run-primary", "session", sequence, type,
                content, Map.of(), Instant.now());
    }
}
