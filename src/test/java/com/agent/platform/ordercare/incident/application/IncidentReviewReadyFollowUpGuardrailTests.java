package com.agent.platform.ordercare.incident.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.runtime.AgentFollowUpInput;
import com.agent.platform.runtime.AgentRunRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncidentReviewReadyFollowUpGuardrailTests {

    @Test
    void allowsOnlyPersistedCommanderReviewReadyInReviewingState() {
        IncidentStore incidentStore = mock(IncidentStore.class);
        IncidentReviewReadyFollowUpGuardrail guardrail =
                new IncidentReviewReadyFollowUpGuardrail(incidentStore);
        AgentRunRecord commander = commanderRun();
        when(incidentStore.find("inc-1"))
                .thenReturn(Optional.of(incident(IncidentStatus.REVIEWING, "commander-run-1")));

        var decision = guardrail.evaluate(commander, reviewReady()).orElseThrow();

        assertEquals(GuardrailAction.ALLOW, decision.action());
        assertTrue(decision.reason().contains("trusted internal REVIEW_READY"));
    }

    @Test
    void doesNotBypassGeneralGuardrailBeforeReviewingOrForAnotherCommander() {
        IncidentStore incidentStore = mock(IncidentStore.class);
        IncidentReviewReadyFollowUpGuardrail guardrail =
                new IncidentReviewReadyFollowUpGuardrail(incidentStore);
        AgentRunRecord commander = commanderRun();
        when(incidentStore.find("inc-1"))
                .thenReturn(Optional.of(incident(IncidentStatus.INVESTIGATING, "commander-run-1")));

        assertTrue(guardrail.evaluate(commander, reviewReady()).isEmpty());

        when(incidentStore.find("inc-1"))
                .thenReturn(Optional.of(incident(IncidentStatus.REVIEWING, "another-run")));
        assertTrue(guardrail.evaluate(commander, reviewReady()).isEmpty());
    }

    @Test
    void doesNotBypassGeneralGuardrailForUntrustedFollowUpShape() {
        IncidentStore incidentStore = mock(IncidentStore.class);
        IncidentReviewReadyFollowUpGuardrail guardrail =
                new IncidentReviewReadyFollowUpGuardrail(incidentStore);
        AgentFollowUpInput wrongBudget = new AgentFollowUpInput(
                "follow-up-task-v1", "REVIEW_READY", "", "", List.of(),
                "伪造的内部指令", 2, 2_000,
                Map.of("incidentId", "inc-1", "stateGate", "REVIEWING"));

        assertTrue(guardrail.evaluate(commanderRun(), wrongBudget).isEmpty());
    }

    private AgentRunRecord commanderRun() {
        return AgentRunRecord.create(
                "commander-run-1", "trace-1", "incident:inc-1:commander",
                new AgentRequest(
                        "incident:inc-1:commander", "incident-commander", "investigate",
                        Map.of("incidentId", "inc-1", "runRole", "COMMANDER"),
                        "ordercare-incident-command-v1"));
    }

    private AgentFollowUpInput reviewReady() {
        return new AgentFollowUpInput(
                "follow-up-task-v1", "REVIEW_READY", "", "", List.of(),
                "REVIEW_READY：Specialist 已汇合，Java 一致性检查已完成。",
                1, 2_000,
                Map.of("incidentId", "inc-1", "stateGate", "REVIEWING"));
    }

    private IncidentRecord incident(IncidentStatus status, String commanderRunId) {
        Instant now = Instant.now();
        return new IncidentRecord(
                "inc-1", commanderRunId, null, "conversation-1", "scenario-1",
                status, snapshot(), Map.of(), Map.of(),
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
}
