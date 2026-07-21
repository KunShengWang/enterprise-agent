package com.agent.platform.workbench.application;

import com.agent.platform.ordercare.incident.application.IncidentTraceProjector;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.model.IncidentTrace;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.model.TaskEventCategory;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.trace.RuntimeTraceProjector;
import com.agent.platform.trace.TraceRun;
import com.agent.platform.trace.TraceSpan;
import com.agent.platform.trace.TraceSpanKind;
import com.agent.platform.trace.TraceSpanStatus;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedWorkExecutionTreeServiceTests {

    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant", "alice", Set.of("USER"));
    private final WorkbenchStore workbench = mock(WorkbenchStore.class);
    private final IncidentStore incidents = mock(IncidentStore.class);
    private final IncidentTraceProjector incidentTraces = mock(IncidentTraceProjector.class);
    private final IncidentRecoveryPlanStore plans = mock(IncidentRecoveryPlanStore.class);
    private final RuntimeTraceProjector runtimeTraces = mock(RuntimeTraceProjector.class);
    private final UnifiedWorkExecutionTreeService service = new UnifiedWorkExecutionTreeService(
            workbench, incidents, incidentTraces, plans, runtimeTraces);

    @Test
    void incidentTreeKeepsSyntheticCoordinatorAttemptsEvidenceConflictsAndAssessment() {
        AgentWorkItem work = work("INCIDENT_INVESTIGATION");
        when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        when(workbench.listLinks(principal, work.workItemId())).thenReturn(List.of(
                link(WorkLinkType.INCIDENT, "incident-1")));
        AgentTaskRecord task = task();
        EvidenceRecord firstEvidence = evidence("evidence-1", "run-specialist-1");
        EvidenceRecord secondEvidence = evidence("evidence-2", "run-specialist-2");
        IncidentAggregate aggregate = new IncidentAggregate(
                incident(), List.of(task), List.of(firstEvidence, secondEvidence), List.of(conflictEvent()));
        when(incidents.findAggregate("incident-1", 10_000)).thenReturn(Optional.of(aggregate));
        IncidentTrace trace = new IncidentTrace(
                "incident-1", coordinator(), List.of(
                new IncidentTrace.ChildRunTrace("COMMANDER", "", trace("run-commander", "COMPLETED", 1, 0)),
                new IncidentTrace.ChildRunTrace("SPECIALIST:ORDER_ANALYST:ATTEMPT_1", task.taskId(),
                        trace("run-specialist-1", "FAILED", 1, 1)),
                new IncidentTrace.ChildRunTrace("SPECIALIST:ORDER_ANALYST:ATTEMPT_2", task.taskId(),
                        trace("run-specialist-2", "COMPLETED", 2, 1)),
                new IncidentTrace.ChildRunTrace("REVIEWER", "", trace("run-reviewer", "COMPLETED", 1, 0))),
                Map.of("syntheticCoordinatorModelCalls", 0));
        when(incidentTraces.project("incident-1")).thenReturn(Optional.of(trace));
        when(plans.listByIncident("incident-1")).thenReturn(List.of());

        var tree = service.project(principal, work.workItemId());

        assertEquals("MULTI_AGENT", tree.treeType());
        assertTrue(tree.coordinator().synthetic());
        assertEquals(0, tree.coordinator().modelCalls());
        assertEquals(0, tree.metrics().syntheticCoordinatorModelCalls());
        assertEquals(List.of(1, 2), tree.agents().stream()
                .filter(node -> node.role().startsWith("SPECIALIST:"))
                .map(node -> node.attempt()).toList());
        assertEquals(List.of("FAILED", "SUCCEEDED"), tree.agents().stream()
                .filter(node -> node.role().startsWith("SPECIALIST:"))
                .map(node -> node.status()).toList());
        assertEquals(List.of("FAILED", "COMPLETED"), tree.agents().stream()
                .filter(node -> node.role().startsWith("SPECIALIST:"))
                .map(node -> node.runtimeStatus()).toList());
        assertEquals(List.of("evidence-1", "evidence-2"), tree.agents().stream()
                .filter(node -> node.role().startsWith("SPECIALIST:"))
                .flatMap(node -> node.evidence().stream())
                .map(EvidenceRecord::evidenceId).toList());
        assertEquals("COUNT_MISMATCH", tree.conflicts().get(0).conflictType());
        assertEquals("RESOLVED", tree.assessment().get("outcome"));
        assertEquals(5, tree.metrics().modelCalls());
    }

    @Test
    void recoveredSpecialistUsesAuthoritativeTaskStatusAndPreservesRuntimeWarning() {
        AgentWorkItem work = work("INCIDENT_INVESTIGATION");
        when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        when(workbench.listLinks(principal, work.workItemId())).thenReturn(List.of(
                link(WorkLinkType.INCIDENT, "incident-1")));
        AgentTaskRecord task = recoveredTask();
        IncidentAggregate aggregate = new IncidentAggregate(
                incident(), List.of(task), List.of(evidence("evidence-1", task.childRunId())), List.of());
        when(incidents.findAggregate("incident-1", 10_000)).thenReturn(Optional.of(aggregate));
        TraceRun failedRuntime = trace(task.childRunId(), "FAILED", 2, 1,
                "TOOL_BUDGET_EXHAUSTED");
        when(incidentTraces.project("incident-1")).thenReturn(Optional.of(new IncidentTrace(
                "incident-1", coordinator(), List.of(new IncidentTrace.ChildRunTrace(
                "SPECIALIST:ORDER_ANALYST:ATTEMPT_1", task.taskId(), failedRuntime)), Map.of())));
        when(plans.listByIncident("incident-1")).thenReturn(List.of());

        var node = service.project(principal, work.workItemId()).agents().stream()
                .filter(candidate -> candidate.role().startsWith("SPECIALIST:"))
                .findFirst().orElseThrow();

        assertEquals("SUCCEEDED", node.status());
        assertEquals("FAILED", node.runtimeStatus());
        assertEquals("", node.error());
        assertTrue(node.runtimeWarning().contains("TOOL_BUDGET_EXHAUSTED"));
        assertTrue(node.runtimeWarning().contains("first persisted read-only result"));
    }

    @Test
    void recoveryPlanWorkItemOnlyProjectsItsPlannerAndRelevantFacts() {
        AgentWorkItem work = work("INCIDENT_RECOVERY_PLAN");
        when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        when(workbench.listLinks(principal, work.workItemId())).thenReturn(List.of(
                link(WorkLinkType.RECOVERY_PLAN, "plan-1")));
        IncidentRecoveryPlanRecord plan = new IncidentRecoveryPlanRecord(
                "plan-1", "incident-1", "request", "run-planner", "digest",
                RecoveryPlanStatus.WAITING_APPROVAL, RecoveryPlanOutcome.NOT_STARTED,
                null, List.of(), List.of(), 0, Instant.now(), Instant.now());
        when(plans.find("plan-1")).thenReturn(Optional.of(plan));
        when(runtimeTraces.project("run-planner")).thenReturn(Optional.of(
                trace("run-planner", "COMPLETED", 1, 0)));
        when(incidents.findAggregate("incident-1", 10_000)).thenReturn(Optional.of(
                new IncidentAggregate(incident(), List.of(task()), List.of(), List.of())));

        var tree = service.project(principal, work.workItemId());

        assertEquals("RECOVERY_PLAN", tree.treeType());
        assertEquals(1, tree.agents().size());
        assertEquals("RECOVERY_PLANNER", tree.agents().get(0).role());
        assertEquals("plan-1", tree.agents().get(0).taskId());
        assertEquals(List.of(plan), tree.recoveryPlans());
    }

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

        verify(incidents, never()).findAggregate("incident-1", 10_000);
        verify(plans, never()).find("plan-1");
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

        verify(incidents, never()).findAggregate("incident-1", 10_000);
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

    private IncidentRecord incident() {
        Instant now = Instant.now();
        return new IncidentRecord(
                "incident-1", "run-commander", "run-reviewer", "conversation", "scenario",
                IncidentStatus.ASSESSED, null, Map.of(), Map.of("outcome", "RESOLVED"),
                0, 1, 10, 1, now.minusSeconds(30), now);
    }

    private AgentTaskRecord task() {
        Instant now = Instant.now();
        return new AgentTaskRecord(
                "task-order", "incident-1", "order", "FACT_QUERY", "ORDER_ANALYST",
                "Compare order facts", 1, List.of(), List.of(EvidenceSubtype.ORDER_STATUS_SET),
                Map.of(), Map.of(), AgentTaskStatus.SUCCEEDED, 1, 2,
                "run-specialist-2", "run-specialist-1", now.plusSeconds(60),
                "", null, 0, null, "", 1, now, now);
    }

    private AgentTaskRecord recoveredTask() {
        Instant now = Instant.now();
        return new AgentTaskRecord(
                "task-order", "incident-1", "order", "FACT_QUERY", "ORDER_ANALYST",
                "Compare order facts", 1, List.of(), List.of(EvidenceSubtype.ORDER_STATUS_SET),
                Map.of(), Map.of("recoveredFromDuplicateToolRequest", true), AgentTaskStatus.SUCCEEDED, 0, 2,
                "run-specialist-1", "run-specialist-1", now.plusSeconds(60),
                "", null, 0, null, "", 1, now, now);
    }

    private EvidenceRecord evidence(String evidenceId, String runId) {
        Instant now = Instant.now();
        return new EvidenceRecord(
                evidenceId, "incident-1", "task-order", runId, EvidenceClass.FACT,
                EvidenceSubtype.ORDER_STATUS_SET, "floworder", "orders", Map.of(), now,
                Map.of("recordCount", 1), "hash", EvidenceStatus.ACCEPTED, "", evidenceId, now);
    }

    private TaskEventRecord conflictEvent() {
        return new TaskEventRecord(
                "event-conflict", "incident-1", null, null, 7,
                TaskEventType.EVIDENCE_CONFLICT_DETECTED, TaskEventCategory.CONTROL,
                TaskEventActorType.SYSTEM, "checker", null, null, 0,
                "incident-1", "", "conflict-key",
                Map.of("conflictId", "conflict-1", "conflictType", "COUNT_MISMATCH",
                        "severity", "HIGH", "metricKey", "recordCount",
                        "relatedEvidenceIds", List.of("evidence-1", "evidence-2"), "status", "OPEN"),
                Instant.now());
    }

    private TraceSpan coordinator() {
        Instant now = Instant.now();
        return new TraceSpan(
                "coordinator", "incident-1", "", "incident.coordinator.synthetic",
                TraceSpanKind.SYSTEM, TraceSpanStatus.COMPLETED, "deterministic", now, now,
                1, "", "", "", Map.of("synthetic", true, "excludedFromModelMetrics", true));
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
