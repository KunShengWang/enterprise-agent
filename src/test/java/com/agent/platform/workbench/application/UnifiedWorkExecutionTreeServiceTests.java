package com.agent.platform.workbench.application;

import com.agent.platform.trace.RuntimeTraceProjector;
import com.agent.platform.trace.TraceRun;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.model.WorkLinkRelation;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnifiedWorkExecutionTreeServiceTests {

    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant", "alice", Set.of("USER"));
    private final WorkbenchStore workbench = mock(WorkbenchStore.class);
    private final RuntimeTraceProjector runtimeTraces = mock(RuntimeTraceProjector.class);
    private final UnifiedWorkExecutionTreeService service = new UnifiedWorkExecutionTreeService(
            workbench, runtimeTraces, List.of());

    @Test
    void primaryRunProjectsAsSingleAgentWithoutSyntheticCoordinator() {
        AgentWorkItem work = work("GENERAL_AGENT");
        when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        when(workbench.listLinks(principal, work.workItemId())).thenReturn(List.of(
                link(WorkLinkType.RUN, "run-general")));
        when(runtimeTraces.project("run-general")).thenReturn(Optional.of(
                trace("run-general", "COMPLETED", 2, 1)));

        var tree = service.project(principal, work.workItemId());

        assertEquals("SINGLE_AGENT", tree.treeType());
        assertEquals(null, tree.coordinator());
        assertEquals("GENERAL_AGENT", tree.agents().get(0).role());
        assertEquals(2, tree.metrics().modelCalls());
    }

    @Test
    void unknownOrForeignWorkItemCannotUseDomainStoresAsLookupOracle() {
        when(workbench.findWorkItem(principal, "foreign-work")).thenReturn(Optional.empty());

        assertThrows(WorkbenchNotFoundException.class,
                () -> service.project(principal, "foreign-work"));

        org.mockito.Mockito.verifyNoInteractions(runtimeTraces);
    }

    @Test
    void multiplePrimaryLinksFailClosedInsteadOfGuessingExecutionRoot() {
        AgentWorkItem work = work("INCIDENT_INVESTIGATION");
        when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        when(workbench.listLinks(principal, work.workItemId())).thenReturn(List.of(
                link(WorkLinkType.INCIDENT, "incident-1"),
                new WorkLink("work-1", "dispatch-2", WorkLinkType.RUN, "run-2",
                        WorkLinkRelation.PRIMARY, Instant.now())));

        assertThrows(IllegalStateException.class,
                () -> service.project(principal, work.workItemId()));

        org.mockito.Mockito.verifyNoInteractions(runtimeTraces);
    }

    @Test
    void legacyLinksAreReadOnlyWithoutDomainLookupOrStateMutation() {
        for (WorkLinkType type : List.of(WorkLinkType.INCIDENT, WorkLinkType.RECOVERY_PLAN)) {
            AgentWorkItem work = work(type == WorkLinkType.INCIDENT ? "INCIDENT_INVESTIGATION" : "INCIDENT_RECOVERY_PLAN");
            when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
            when(workbench.listLinks(principal, work.workItemId())).thenReturn(List.of(link(type, "saved-id")));
            var tree = service.project(principal, work.workItemId());
            assertEquals("RETIRED", tree.treeType());
            assertEquals(true, tree.assessment().get("readOnly"));
            assertEquals("RUNNING", tree.assessment().get("historicalExecutionState"));
            assertEquals(WorkExecutionState.RUNNING, work.executionState());
            assertTrue(tree.agents().isEmpty());
            org.mockito.Mockito.verifyNoInteractions(runtimeTraces);
        }
    }

    private AgentWorkItem work(String target) {
        Instant now = Instant.now();
        return new AgentWorkItem(
                "work-1", "conversation", principal.tenantId(), principal.principalId(), "goal", "goal",
                WorkControlState.DISPATCHED, WorkExecutionState.RUNNING, WorkOutcome.UNDETERMINED,
                target, "", "", "", "decision", "input", "", "route", 1,
                now, null, "", "dispatch", 1, 1, now, now, null);
    }

    private WorkLink link(WorkLinkType type, String linkedId) {
        return new WorkLink("work-1", "dispatch", type, linkedId, WorkLinkRelation.PRIMARY, Instant.now());
    }

    private TraceRun trace(String runId, String status, long modelCalls, long toolCalls) {
        return trace(runId, status, modelCalls, toolCalls, "");
    }

    private TraceRun trace(String runId, String status, long modelCalls, long toolCalls,
                           String failureReason) {
        Instant now = Instant.now();
        return new TraceRun(
                runId, "conversation", "question", status, now.minusSeconds(1), now, 1000, failureReason,
                modelCalls * 10, modelCalls * 5, modelCalls * 0.001,
                List.of(), List.of(), List.of(),
                Map.of("modelCalls", modelCalls, "toolCalls", toolCalls));
    }
}
