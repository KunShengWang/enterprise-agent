package com.agent.platform.ordercare.incident.tool;

import com.agent.platform.ordercare.incident.application.IncidentEvidenceProjector;
import com.agent.platform.ordercare.incident.application.IncidentReviewerAgentService;
import com.agent.platform.ordercare.incident.application.IncidentSubAgentTaskService;
import com.agent.platform.ordercare.incident.application.IncidentTaskExecution;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.model.ReviewerAssessmentDraft;
import com.agent.platform.ordercare.incident.persistence.AgentTaskStore;
import com.agent.platform.ordercare.incident.persistence.EvidenceStore;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutionContext;
import com.agent.platform.workbench.budget.IncidentBudgetReservation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentSubAgentToolHandlerTests {

    @Test
    void specialistUsesTrustedIncidentContextInsteadOfModelArguments() {
        IncidentStore incidents = mock(IncidentStore.class);
        IncidentSubAgentTaskService tasks = mock(IncidentSubAgentTaskService.class);
        IncidentRecord incident = incident(IncidentStatus.INVESTIGATING);
        AgentTaskRecord task = task(AgentTaskStatus.WAITING_CLARIFICATION);
        when(incidents.find("inc-1")).thenReturn(Optional.of(incident));
        when(tasks.delegate(eq(incident.snapshot()), eq("parent-run-1"),
                eq(IncidentAgentRole.ORDER_ANALYST), eq("核对订单")))
                .thenReturn(new IncidentSubAgentTaskService.DelegationOutcome(
                        new IncidentTaskExecution(task, List.of(evidence()), List.of(), true), false));
        IncidentSubAgentToolHandler handler = new IncidentSubAgentToolHandler(
                incidents, tasks, new ObjectMapper());

        ToolCallResult result = handler.execute(
                new ToolCallRequest(
                        IncidentToolCatalog.DELEGATE_ORDER_ANALYST, "call-1",
                        Map.of("objective", "核对订单")),
                new ToolExecutionContext(
                        "parent-run-1", "session-1", "user-1", "tenant-1", Set.of(),
                        Map.of("incidentId", "inc-1", "runRole", "COMMANDER", "delegationDepth", 0)));

        assertTrue(result.success());
        assertEquals("task-1", result.metadata().get("taskId"));
        assertEquals("run-child-1", result.metadata().get("childRunId"));
        verify(incidents, never()).find("model-forged-incident");
    }

    @Test
    void specialistRejectsChildAgentRecursiveDelegation() {
        IncidentStore incidents = mock(IncidentStore.class);
        IncidentSubAgentTaskService tasks = mock(IncidentSubAgentTaskService.class);
        IncidentSubAgentToolHandler handler = new IncidentSubAgentToolHandler(
                incidents, tasks, new ObjectMapper());

        ToolCallResult result = handler.execute(
                new ToolCallRequest(
                        IncidentToolCatalog.DELEGATE_ORDER_ANALYST, "call-1",
                        Map.of("objective", "recursive")),
                new ToolExecutionContext(
                        "child-run", "session-1", "user-1", "tenant-1", Set.of(),
                        Map.of("incidentId", "inc-1", "runRole", "COMMANDER", "delegationDepth", 1)));

        assertEquals(false, result.success());
        assertEquals("DELEGATION_DEPTH_EXCEEDED", result.metadata().get("errorCode"));
        verify(tasks, never()).delegate(any(), any(), any(), any());
    }

    @Test
    void reviewerIsRejectedBeforeJavaConsistencyGateAndAllowedInReviewing() {
        IncidentStore incidents = mock(IncidentStore.class);
        AgentTaskStore tasks = mock(AgentTaskStore.class);
        EvidenceStore evidence = mock(EvidenceStore.class);
        TaskEventStore events = mock(TaskEventStore.class);
        ToolExecutionStore toolExecutions = mock(ToolExecutionStore.class);
        IncidentEvidenceProjector projector = mock(IncidentEvidenceProjector.class);
        IncidentReviewerAgentService reviewers = mock(IncidentReviewerAgentService.class);
        IncidentReviewerToolHandler handler = new IncidentReviewerToolHandler(
                incidents, tasks, evidence, events, toolExecutions, projector, reviewers,
                new ObjectMapper());
        ToolCallRequest request = new ToolCallRequest(
                IncidentToolCatalog.REVIEW_INCIDENT_EVIDENCE, "review-call", Map.of("objective", "review"));
        ToolExecutionContext context = new ToolExecutionContext(
                "parent-run", "session-1", "user-1", "tenant-1", Set.of(),
                Map.of("incidentId", "inc-1", "runRole", "COMMANDER", "delegationDepth", 0));

        when(incidents.find("inc-1")).thenReturn(Optional.of(incident(IncidentStatus.CHECKING_CONSISTENCY)));
        ToolCallResult gated = handler.execute(request, context);
        assertEquals("REVIEW_STATE_GATE_REJECTED", gated.metadata().get("errorCode"));
        verify(reviewers, never()).review(any(), any(), any(), any());

        IncidentRecord reviewing = incident(IncidentStatus.REVIEWING);
        when(incidents.find("inc-1")).thenReturn(Optional.of(reviewing));
        when(tasks.listTasks("inc-1")).thenReturn(List.of(task(AgentTaskStatus.WAITING_CLARIFICATION)));
        when(evidence.listEvidence("inc-1")).thenReturn(List.of(evidence()));
        when(events.loadEventsAfter("inc-1", 0, 10_000)).thenReturn(List.of());
        when(toolExecutions.findByRun("run-child-1")).thenReturn(List.of());
        when(projector.projectGaps(List.of())).thenReturn(List.of());
        ReviewerAssessmentDraft draft = new ReviewerAssessmentDraft(
                "reviewer-assessment-v1", List.of(), List.of(), List.of(), null, List.of());
        when(reviewers.review(eq(reviewing), any(), any(), any())).thenReturn(
                new IncidentReviewerAgentService.ReviewAgentOutcome(
                        "reviewer-run-1", draft, IncidentBudgetReservation.degraded("reviewer"), false));

        ToolCallResult allowed = handler.execute(request, context);
        assertTrue(allowed.success());
        assertEquals("reviewer-run-1", allowed.metadata().get("reviewerRunId"));
    }

    @Test
    void reviewerFailureIsNotRetriedByGenericToolRuntime() {
        IncidentStore incidents = mock(IncidentStore.class);
        AgentTaskStore tasks = mock(AgentTaskStore.class);
        EvidenceStore evidence = mock(EvidenceStore.class);
        TaskEventStore events = mock(TaskEventStore.class);
        ToolExecutionStore toolExecutions = mock(ToolExecutionStore.class);
        IncidentEvidenceProjector projector = mock(IncidentEvidenceProjector.class);
        IncidentReviewerAgentService reviewers = mock(IncidentReviewerAgentService.class);
        IncidentRecord reviewing = incident(IncidentStatus.REVIEWING);
        when(incidents.find("inc-1")).thenReturn(Optional.of(reviewing));
        when(tasks.listTasks("inc-1")).thenReturn(List.of(task(AgentTaskStatus.SUCCEEDED)));
        when(evidence.listEvidence("inc-1")).thenReturn(List.of(evidence()));
        when(events.loadEventsAfter("inc-1", 0, 10_000)).thenReturn(List.of());
        when(toolExecutions.findByRun("run-child-1")).thenReturn(List.of());
        when(projector.projectGaps(List.of())).thenReturn(List.of());
        when(reviewers.review(eq(reviewing), any(), any(), any()))
                .thenThrow(new IllegalStateException("reviewer unavailable"));
        IncidentReviewerToolHandler handler = new IncidentReviewerToolHandler(
                incidents, tasks, evidence, events, toolExecutions, projector, reviewers,
                new ObjectMapper());

        ToolCallResult result = handler.execute(
                new ToolCallRequest(
                        IncidentToolCatalog.REVIEW_INCIDENT_EVIDENCE, "review-call", Map.of("objective", "review")),
                new ToolExecutionContext(
                        "parent-run", "session-1", "user-1", "tenant-1", Set.of(),
                        Map.of("incidentId", "inc-1", "runRole", "COMMANDER", "delegationDepth", 0)));

        assertEquals(false, result.success());
        assertEquals(false, result.metadata().get("retryable"));
        assertEquals("REVIEWER_SUB_AGENT_FAILED", result.metadata().get("errorCode"));
    }

    private IncidentRecord incident(IncidentStatus status) {
        Instant now = Instant.now();
        return new IncidentRecord(
                "inc-1", null, null, "incident:inc-1", "ordercare-incident-command-v1",
                status, snapshot(), Map.of(), Map.of(), 0, 1, 1, 0, now, now);
    }

    private IncidentSnapshot snapshot() {
        Instant now = Instant.now();
        return new IncidentSnapshot(
                "snapshot-1", "inc-1", "batch-1", "ORDER_TIMEOUT", "tenant-1",
                new IncidentSnapshot.IncidentOrderScope(List.of("req-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of("queue-1")),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope-hash-1");
    }

    private AgentTaskRecord task(AgentTaskStatus status) {
        Instant now = Instant.now();
        return new AgentTaskRecord(
                "task-1", "inc-1", "subagent-order_analyst", "INCIDENT_INVESTIGATION",
                IncidentAgentRole.ORDER_ANALYST.name(), "inspect", 100, List.of(),
                List.of(EvidenceSubtype.ORDER_STATUS_SET), Map.of(), Map.of(), status,
                0, 2, "run-child-1", "run-child-1", now.plusSeconds(60), null, null,
                0, null, "", 1, now, now);
    }

    private EvidenceRecord evidence() {
        Instant now = Instant.now();
        return new EvidenceRecord(
                "evidence-1", "inc-1", "task-1", "run-child-1",
                EvidenceClass.FACT, EvidenceSubtype.ORDER_STATUS_SET, "floworder", "source",
                Map.of("toolCallId", "fact-call"), now, Map.of("count", 1), "hash",
                EvidenceStatus.ACCEPTED, null, "idempotency", now);
    }
}
