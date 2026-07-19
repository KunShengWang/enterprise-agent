package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.config.IncidentWorkerIdentity;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceCandidate;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.persistence.AgentTaskStore;
import com.agent.platform.ordercare.incident.persistence.EvidenceStore;
import com.agent.platform.runtime.AgentContinuationRuntime;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentStopReason;
import com.agent.platform.runtime.ToolExecutionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentTaskSchedulerTests {

    private IncidentTaskResultCommitter resultCommitter;
    private ToolExecutionStore toolExecutionStore;
    private IncidentEvidenceProjector evidenceProjector;
    private IncidentTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        resultCommitter = mock(IncidentTaskResultCommitter.class);
        toolExecutionStore = mock(ToolExecutionStore.class);
        evidenceProjector = mock(IncidentEvidenceProjector.class);
        IncidentCommandProperties properties = new IncidentCommandProperties();
        properties.setMaxParallelSpecialists(1);
        properties.setPhase3Enabled(false);
        scheduler = new IncidentTaskScheduler(
                mock(AgentTaskStore.class),
                mock(EvidenceStore.class),
                resultCommitter,
                mock(AgentContinuationRuntime.class),
                toolExecutionStore,
                evidenceProjector,
                new IncidentExecutionProfileFactory(),
                properties,
                new IncidentWorkerIdentity(properties));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void commitsDurableFactsInsteadOfRetryingAfterDuplicateToolRequest() {
        AgentTaskRecord running = task(AgentTaskStatus.RUNNING);
        EvidenceCandidate candidate = candidate();
        EvidenceRecord evidence = evidence();
        when(toolExecutionStore.findByRun("run-1")).thenReturn(List.of());
        when(evidenceProjector.project(List.of())).thenReturn(List.of(candidate));
        when(evidenceProjector.projectGaps(List.of())).thenReturn(List.of());
        when(resultCommitter.commit(any())).thenReturn(new TaskResultCommitResult(
                task(AgentTaskStatus.SUCCEEDED), List.of(evidence), null, false));

        IncidentTaskExecution execution = scheduler.recoverPersistedFactsAfterDuplicateToolRequest(
                running, stopped(AgentStopReason.TOOL_BUDGET_EXHAUSTED));

        assertNotNull(execution);
        assertTrue(execution.successful());
        assertEquals(AgentTaskStatus.SUCCEEDED, execution.task().status());
        assertEquals(List.of(evidence), execution.evidence());

        ArgumentCaptor<TaskResultSubmission> submission = ArgumentCaptor.forClass(TaskResultSubmission.class);
        verify(resultCommitter).commit(submission.capture());
        assertEquals(AgentTaskStatus.SUCCEEDED, submission.getValue().targetStatus());
        assertEquals(1, submission.getValue().evidence().size());
        assertEquals(true, submission.getValue().outputSummary()
                .get("recoveredFromDuplicateToolRequest"));
        assertEquals("TOOL_BUDGET_EXHAUSTED", submission.getValue().outputSummary()
                .get("runtimeStopReason"));
    }

    @Test
    void doesNotRecoverOtherRuntimeFailures() {
        IncidentTaskExecution execution = scheduler.recoverPersistedFactsAfterDuplicateToolRequest(
                task(AgentTaskStatus.RUNNING), stopped(AgentStopReason.MODEL_ERROR));

        assertNull(execution);
        verify(toolExecutionStore, never()).findByRun(any());
        verify(resultCommitter, never()).commit(any());
    }

    @Test
    void doesNotHideToolBudgetFailureWhenNoFactWasPersisted() {
        when(toolExecutionStore.findByRun("run-1")).thenReturn(List.of());
        when(evidenceProjector.project(List.of())).thenReturn(List.of());

        IncidentTaskExecution execution = scheduler.recoverPersistedFactsAfterDuplicateToolRequest(
                task(AgentTaskStatus.RUNNING), stopped(AgentStopReason.TOOL_BUDGET_EXHAUSTED));

        assertNull(execution);
        verify(resultCommitter, never()).commit(any());
    }

    @Test
    void onlyTransientRuntimeFailuresAreRetried() {
        assertTrue(IncidentTaskScheduler.isRetryableStopReason(AgentStopReason.MODEL_ERROR));
        assertTrue(IncidentTaskScheduler.isRetryableStopReason(AgentStopReason.TOOL_ERROR));
        assertTrue(IncidentTaskScheduler.isRetryableStopReason(AgentStopReason.TIMEOUT));
        assertTrue(IncidentTaskScheduler.isRetryableStopReason(AgentStopReason.INTERNAL_ERROR));

        assertFalse(IncidentTaskScheduler.isRetryableStopReason(AgentStopReason.GUARDRAIL_BLOCKED));
        assertFalse(IncidentTaskScheduler.isRetryableStopReason(AgentStopReason.TOOL_BUDGET_EXHAUSTED));
        assertFalse(IncidentTaskScheduler.isRetryableStopReason(AgentStopReason.MODEL_BUDGET_EXHAUSTED));
        assertFalse(IncidentTaskScheduler.isRetryableStopReason(AgentStopReason.CANCELLED));
    }

    @Test
    void specialistQuestionContainsTaskDataButNotTrustedToolInstructions() {
        String prompt = scheduler.specialistPrompt(
                task(AgentTaskStatus.PENDING), snapshot(), IncidentAgentRole.ORDER_ANALYST);

        assertTrue(prompt.contains("objective: inspect orders"));
        assertTrue(prompt.contains("snapshotId: snapshot-1"));
        assertFalse(prompt.contains("必须调用"));
        assertFalse(prompt.contains("TOOL_RESULT"));
        assertFalse(prompt.contains("不得重试"));
        assertFalse(prompt.contains("floworder_incident_order_facts"));
    }

    private AgentRuntimeResult stopped(AgentStopReason reason) {
        return new AgentRuntimeResult(
                "run-1", "session-1", AgentRunState.FAILED, reason,
                "", "", null, List.of());
    }

    private AgentTaskRecord task(AgentTaskStatus status) {
        Instant now = Instant.now();
        return new AgentTaskRecord(
                "task-1", "incident-1", "client-task-1", "SPECIALIST",
                IncidentAgentRole.ORDER_ANALYST.name(), "inspect orders", 1,
                List.of(), List.of(EvidenceSubtype.ORDER_STATUS_SET), Map.of(), Map.of(), status,
                0, 2, "run-1", "run-1", now.plusSeconds(60), "", null,
                0, null, "", 4, now, now);
    }

    private EvidenceCandidate candidate() {
        return new EvidenceCandidate(
                EvidenceClass.FACT, EvidenceSubtype.ORDER_STATUS_SET,
                "floworder", "snapshot-1", Map.of("toolCallId", "call-1"),
                Instant.now(), Map.of("recordCount", 1), EvidenceStatus.ACCEPTED,
                null, "candidate-1");
    }

    private EvidenceRecord evidence() {
        return new EvidenceRecord(
                "evidence-1", "incident-1", "task-1", "run-1",
                EvidenceClass.FACT, EvidenceSubtype.ORDER_STATUS_SET,
                "floworder", "snapshot-1", Map.of("toolCallId", "call-1"),
                Instant.now(), Map.of("recordCount", 1), "hash", EvidenceStatus.ACCEPTED,
                null, "candidate-1", Instant.now());
    }

    private IncidentSnapshot snapshot() {
        Instant now = Instant.now();
        return new IncidentSnapshot(
                "snapshot-1", "incident-1", "batch-1", "ORDER_STATE_INCONSISTENCY", "tenant-1",
                new IncidentSnapshot.IncidentOrderScope(List.of("request-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of("queue-1")),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope-hash-1");
    }
}
