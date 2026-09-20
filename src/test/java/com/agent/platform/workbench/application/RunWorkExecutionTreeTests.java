package com.agent.platform.workbench.application;

import com.agent.platform.trace.RuntimeTraceProjector;
import com.agent.platform.trace.TraceRun;
import com.agent.platform.workbench.model.*;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The ordinary Run path must work without installing any domain contributor. */
class RunWorkExecutionTreeTests {
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant", "alice", Set.of("USER"));
    private final WorkbenchStore store = mock(WorkbenchStore.class);
    private final RuntimeTraceProjector traces = mock(RuntimeTraceProjector.class);
    private final UnifiedWorkExecutionTreeService service = new UnifiedWorkExecutionTreeService(store, traces, List.of());

    @ParameterizedTest
    @ValueSource(strings = {"GENERAL_AGENT", "PROCUREMENT_SOURCING"})
    void projectsRunAndMetricsWithoutLegacyBeans(String target) {
        var work = work(target);
        var now = Instant.now();
        when(store.findWorkItem(principal, "work")).thenReturn(Optional.of(work));
        when(store.listLinks(principal, "work")).thenReturn(List.of(link(WorkLinkType.RUN, "run")));
        var trace = new TraceRun("run", "conversation", "goal", "COMPLETED", now, now,
                100, "", 20, 10, 0.01, List.of(), List.of(), List.of(), Map.of("modelCalls", 2, "toolCalls", 1));
        when(traces.project("run")).thenReturn(Optional.of(trace));

        var tree = service.project(principal, "work");

        assertEquals("SINGLE_AGENT", tree.treeType());
        assertNull(tree.coordinator());
        assertEquals(1, tree.agents().size());
        assertEquals(target + ":run", tree.agents().get(0).nodeId());
        assertEquals(trace, tree.agents().get(0).trace());
        assertEquals(new UnifiedWorkExecutionTree.TreeMetrics(1, 2, 1, 20, 10, 0.01, 0, 0, 0), tree.metrics());
        assertTrue(tree.evidence().isEmpty());
        assertTrue(tree.recoveryPlans().isEmpty());
    }

    @Test
    void missingTraceKeepsWorkStatusAndZeroMetrics() {
        when(store.findWorkItem(principal, "work")).thenReturn(Optional.of(work("PROCUREMENT_SOURCING")));
        when(store.listLinks(principal, "work")).thenReturn(List.of(link(WorkLinkType.RUN, "missing")));
        var tree = service.project(principal, "work");
        assertEquals("RUNNING", tree.agents().get(0).status());
        assertNull(tree.agents().get(0).trace());
        assertEquals(0, tree.metrics().modelCalls());
    }

    @Test
    void noPrimaryLinkKeepsPendingShape() {
        when(store.findWorkItem(principal, "work")).thenReturn(Optional.of(work("PROCUREMENT_SOURCING")));
        var tree = service.project(principal, "work");
        assertEquals("PENDING", tree.treeType());
        assertEquals(UnifiedWorkExecutionTree.TreeMetrics.empty(), tree.metrics());
        verifyNoInteractions(traces);
    }

    @Test
    void ownershipAndPrimaryLinkValidationPrecedeContributorLookup() {
        var contributor = mock(WorkExecutionTreeContributor.class);
        var extended = new UnifiedWorkExecutionTreeService(store, traces, List.of(contributor));
        assertThrows(WorkbenchNotFoundException.class, () -> extended.project(principal, "foreign"));
        when(store.findWorkItem(principal, "work")).thenReturn(Optional.of(work("PROCUREMENT_SOURCING")));
        when(store.listLinks(principal, "work")).thenReturn(List.of(link(WorkLinkType.RUN, "one"), link(WorkLinkType.RUN, "two")));
        assertThrows(IllegalStateException.class, () -> extended.project(principal, "work"));
        verifyNoInteractions(contributor, traces);
    }

    @Test
    void multipleDomainContributorsFailClosed() {
        var one = mock(WorkExecutionTreeContributor.class);
        var two = mock(WorkExecutionTreeContributor.class);
        when(one.supports(WorkLinkType.INCIDENT)).thenReturn(true);
        when(two.supports(WorkLinkType.INCIDENT)).thenReturn(true);
        when(store.findWorkItem(principal, "work")).thenReturn(Optional.of(work("INCIDENT_INVESTIGATION")));
        when(store.listLinks(principal, "work")).thenReturn(List.of(link(WorkLinkType.INCIDENT, "incident")));
        var extended = new UnifiedWorkExecutionTreeService(store, traces, List.of(one, two));
        assertThrows(IllegalStateException.class, () -> extended.project(principal, "work"));
        verify(one, never()).project(any(), any());
        verify(two, never()).project(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INCIDENT", "RECOVERY_PLAN"})
    void missingDomainContributorReturnsReadOnlyRetirementWithoutChangingStatus(String type) {
        var historical = work("INCIDENT_INVESTIGATION");
        when(store.findWorkItem(principal, "work")).thenReturn(Optional.of(historical));
        when(store.listLinks(principal, "work")).thenReturn(List.of(link(WorkLinkType.valueOf(type), "old-id")));
        var tree = service.project(principal, "work");
        assertEquals("RETIRED", tree.treeType());
        assertEquals("old-id", tree.executionId());
        assertEquals(true, tree.assessment().get("readOnly"));
        assertEquals("RUNNING", tree.assessment().get("historicalExecutionState"));
        assertTrue(tree.agents().isEmpty());
        assertTrue(tree.evidence().isEmpty());
        assertTrue(tree.recoveryPlans().isEmpty());
        verifyNoInteractions(traces);
        verify(store).findWorkItem(principal, "work");
        verify(store).listLinks(principal, "work");
        verifyNoMoreInteractions(store);
    }

    private WorkLink link(WorkLinkType type, String id) {
        return new WorkLink("work", "dispatch", type, id, WorkLinkRelation.PRIMARY, Instant.now());
    }

    private AgentWorkItem work(String target) {
        Instant now = Instant.now();
        return new AgentWorkItem("work", "conversation", principal.tenantId(), principal.principalId(), "goal", "goal",
                WorkControlState.DISPATCHED, WorkExecutionState.RUNNING, WorkOutcome.UNDETERMINED,
                target, "", "", "", "decision", "input", "", "route", 1,
                now, null, "", "dispatch", 1, 1, now, now, null);
    }
}
