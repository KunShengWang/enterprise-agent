package com.agent.platform.ordercare.incident.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentRiskLevel;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.runtime.AgentContinuationRuntime;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentFollowUpInput;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentStopReason;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.workbench.budget.IncidentBudgetGate;
import com.agent.platform.workbench.budget.IncidentBudgetReservation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentReviewerAgentServiceTests {

    @Test
    void retriesSemanticallyEmptyReviewerOutputOnceInTheSameRun() {
        IncidentExecutionProfileFactory profiles = mock(IncidentExecutionProfileFactory.class);
        AgentContinuationRuntime runtime = mock(AgentContinuationRuntime.class);
        IncidentBudgetGate budgets = mock(IncidentBudgetGate.class);
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentExecutionProfile reviewerProfile = new IncidentExecutionProfileFactory().reviewer();
        IncidentBudgetReservation reservation = IncidentBudgetReservation.degraded("reviewer");
        ObjectMapper objectMapper = new ObjectMapper();
        ReviewerAssessmentDraftParser parser = new ReviewerAssessmentDraftParser(objectMapper);
        IncidentReviewerAgentService service = new IncidentReviewerAgentService(
                profiles, runtime, parser, new IncidentAssessmentAssembler(), budgets, runStore, objectMapper);

        when(profiles.reviewer()).thenReturn(reviewerProfile);
        when(budgets.reserveIncidentRun(eq("inc-1"), eq("reviewer"), eq("REVIEWER"), eq(reviewerProfile)))
                .thenReturn(reservation);
        when(runtime.runUntilInputCheckpoint(any(), eq(reviewerProfile), any()))
                .thenReturn(result(
                        AgentRunState.WAITING_INPUT,
                        AgentStopReason.WAITING_INPUT,
                        "{\"schemaVersion\":\"reviewer-assessment-v1\",\"confirmedFacts\":[],"
                                + "\"rootCauseCandidates\":[],\"recommendations\":[],"
                                + "\"clarificationRequest\":null,\"acknowledgedConflictIds\":[]}"));
        when(runtime.continueWithInput(eq("reviewer-run-1"), any(), any()))
                .thenReturn(result(
                        AgentRunState.COMPLETED,
                        AgentStopReason.COMPLETED,
                        "{\"schemaVersion\":\"reviewer-assessment-v1\",\"confirmedFacts\":[{"
                                + "\"evidenceSubtype\":\"ORDER_STATUS_SET\","
                                + "\"statement\":\"订单终态事实已确认\","
                                + "\"evidenceIds\":[\"ev-order\"]}],"
                                + "\"rootCauseCandidates\":[{"
                                + "\"hypothesis\":\"死信队列无活跃消费者\","
                                + "\"supportingEvidenceIds\":[\"ev-order\"],"
                                + "\"relatedConflictIds\":[]}],"
                                + "\"recommendations\":[{"
                                + "\"action\":\"核查消费者缺失原因，并评估恢复消费者后消息重放的影响\","
                                + "\"evidenceIds\":[\"ev-order\"],\"conflictIds\":[]}],"
                                + "\"clarificationRequest\":null,\"acknowledgedConflictIds\":[]}"));

        var outcome = service.review(incident(), List.of(evidence()), List.of(), List.of());

        assertEquals("reviewer-run-1", outcome.reviewerRunId());
        assertEquals(1, outcome.draft().confirmedFacts().size());
        assertEquals(1, outcome.draft().rootCauseCandidates().size());
        assertTrue(outcome.valid());
        assertEquals(IncidentRiskLevel.MEDIUM, new IncidentAssessmentAssembler().assemble(
                snapshot(), List.of(evidence()), List.of(), List.of(), outcome.draft()).riskLevel());
        ArgumentCaptor<AgentRequest> initialRequest = ArgumentCaptor.forClass(AgentRequest.class);
        verify(runtime).runUntilInputCheckpoint(initialRequest.capture(), eq(reviewerProfile), any());
        String initialPrompt = initialRequest.getValue().question();
        assertTrue(initialPrompt.contains("\"evidenceSubtype\": \"ORDER_STATUS_SET\""));
        assertTrue(initialPrompt.contains("\"supportingEvidenceIds\""));
        assertTrue(initialPrompt.contains("\"relatedConflictIds\""));
        assertTrue(initialPrompt.contains("\"evidenceIds\""));
        assertTrue(initialPrompt.contains("\"conflictIds\""));
        assertTrue(initialPrompt.contains("每个不同的 evidenceSubtype 都必须单独生成至少一条 ConfirmedFact"));
        assertTrue(initialPrompt.contains("禁止使用错误字段 evidenceId、confidence、rationale"));
        ArgumentCaptor<AgentFollowUpInput> correction = ArgumentCaptor.forClass(AgentFollowUpInput.class);
        verify(runtime).continueWithInput(eq("reviewer-run-1"), correction.capture(), any());
        assertEquals("REVIEW_OUTPUT_CORRECTION", correction.getValue().followUpType());
        assertTrue(correction.getValue().question().contains("confirmedFacts 就不得为空"));
        assertTrue(correction.getValue().question().contains("ev-order"));
        assertTrue(correction.getValue().question().contains("\"supportingEvidenceIds\""));
        assertTrue(correction.getValue().question().contains("\"relatedConflictIds\""));
        assertTrue(correction.getValue().question().contains("禁止使用错误字段 evidenceId、confidence、rationale"));
        verify(budgets).settle(eq(reservation), any());
    }

    @Test
    void reportsValidationErrorsWhenCorrectionIsStillInvalid() {
        IncidentExecutionProfileFactory profiles = mock(IncidentExecutionProfileFactory.class);
        AgentContinuationRuntime runtime = mock(AgentContinuationRuntime.class);
        IncidentBudgetGate budgets = mock(IncidentBudgetGate.class);
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentExecutionProfile reviewerProfile = new IncidentExecutionProfileFactory().reviewer();
        IncidentBudgetReservation reservation = IncidentBudgetReservation.degraded("reviewer");
        ObjectMapper objectMapper = new ObjectMapper();
        IncidentReviewerAgentService service = new IncidentReviewerAgentService(
                profiles, runtime, new ReviewerAssessmentDraftParser(objectMapper),
                new IncidentAssessmentAssembler(), budgets, runStore, objectMapper);

        when(profiles.reviewer()).thenReturn(reviewerProfile);
        when(budgets.reserveIncidentRun(eq("inc-1"), eq("reviewer"), eq("REVIEWER"), eq(reviewerProfile)))
                .thenReturn(reservation);
        when(runtime.runUntilInputCheckpoint(any(), eq(reviewerProfile), any()))
                .thenReturn(result(AgentRunState.WAITING_INPUT, AgentStopReason.WAITING_INPUT,
                        "{\"schemaVersion\":\"reviewer-assessment-v1\",\"confirmedFacts\":[],"
                                + "\"rootCauseCandidates\":[],\"recommendations\":[],"
                                + "\"clarificationRequest\":null,\"acknowledgedConflictIds\":[]}"));
        when(runtime.continueWithInput(eq("reviewer-run-1"), any(), any()))
                .thenReturn(result(AgentRunState.COMPLETED, AgentStopReason.COMPLETED,
                        "{\"schemaVersion\":\"reviewer-assessment-v1\",\"confirmedFacts\":[{"
                                + "\"evidenceSubtype\":\"ORDER_STATUS_SET\","
                                + "\"statement\":\"订单终态事实已确认\","
                                + "\"evidenceIds\":[\"ev-order\"]}],"
                                + "\"rootCauseCandidates\":[],\"recommendations\":[{"
                                + "\"action\":\"建议立即重放死信消息\","
                                + "\"evidenceIds\":[\"ev-order\"],\"conflictIds\":[]}],"
                                + "\"clarificationRequest\":null,\"acknowledgedConflictIds\":[]}"));

        var outcome = service.review(incident(), List.of(evidence()), List.of(), List.of());

        assertEquals(false, outcome.valid());
        assertTrue(outcome.validationErrors().stream()
                .anyMatch(error -> error.contains("read-only")));
    }

    private AgentRuntimeResult result(AgentRunState state, AgentStopReason reason, String answer) {
        return new AgentRuntimeResult(
                "reviewer-run-1", "incident:inc-1:reviewer", state, reason,
                answer, "", null, List.of());
    }

    private IncidentRecord incident() {
        Instant now = Instant.now();
        return new IncidentRecord(
                "inc-1", "commander-run-1", null, "conversation-1", "scenario-1",
                IncidentStatus.REVIEWING, snapshot(), Map.of(), Map.of(),
                0, 1, 0, 0, now, now);
    }

    private IncidentSnapshot snapshot() {
        Instant now = Instant.now();
        return new IncidentSnapshot(
                "snap-1", "inc-1", "alert-1", "ORDER_STATE_INCONSISTENCY", "tenant-1",
                new IncidentSnapshot.IncidentOrderScope(List.of("REQ-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of()),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope-hash");
    }

    private EvidenceRecord evidence() {
        Instant now = Instant.now();
        return new EvidenceRecord(
                "ev-order", "inc-1", "task-1", "specialist-run-1", EvidenceClass.FACT,
                EvidenceSubtype.ORDER_STATUS_SET, "floworder", "order-facts", Map.of(), now,
                Map.of("scopeHash", "scope-hash"), "payload-hash", EvidenceStatus.ACCEPTED,
                "", "evidence-idempotency-key", now);
    }
}
