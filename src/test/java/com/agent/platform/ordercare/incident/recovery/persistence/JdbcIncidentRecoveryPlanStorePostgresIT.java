package com.agent.platform.ordercare.incident.recovery.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException;
import com.agent.platform.ordercare.incident.persistence.IncidentIdempotencyConflictException;
import com.agent.platform.ordercare.incident.persistence.JdbcIncidentStore;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanItem;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanItemStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "INCIDENT_POSTGRES_IT", matches = "true")
class JdbcIncidentRecoveryPlanStorePostgresIT {

    private final AgentStorageProperties properties = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String incidentId;

    @AfterEach
    void cleanup() throws Exception {
        if (incidentId == null) return;
        try (Connection connection = DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword())) {
            delete(connection, "DELETE FROM agent_incident_recovery_plan WHERE incident_id = ?", incidentId);
            delete(connection, "DELETE FROM agent_task_event WHERE incident_id = ?", incidentId);
            delete(connection, "DELETE FROM agent_evidence WHERE incident_id = ?", incidentId);
            delete(connection, "DELETE FROM agent_task WHERE incident_id = ?", incidentId);
            delete(connection, "DELETE FROM agent_incident WHERE incident_id = ?", incidentId);
        }
    }

    @Test
    void persistsIdempotentPlanAndUsesVersionCas() {
        createIncident();
        JdbcIncidentRecoveryPlanStore store = new JdbcIncidentRecoveryPlanStore(properties, objectMapper);
        IncidentRecoveryPlanRecord created = plan("plan-1", "request-1", "digest-1");

        assertEquals(created, store.create(created));
        assertEquals(created, store.create(created));
        assertEquals(1, store.listByIncident(incidentId).size());

        IncidentRecoveryPlanRecord updated = new IncidentRecoveryPlanRecord(
                created.planId(), created.incidentId(), created.requestKey(), "run-planner",
                created.assessmentDigest(), RecoveryPlanStatus.PLANNING, RecoveryPlanOutcome.NOT_STARTED,
                null, List.of(), List.of(), 1, created.createdAt(), Instant.now());
        store.update(updated, 0);

        assertEquals(1, store.find(created.planId()).orElseThrow().version());
        assertThrows(IncidentCasConflictException.class, () -> store.update(updated, 0));
        assertThrows(IncidentIdempotencyConflictException.class,
                () -> store.create(plan("plan-other", "request-1", "digest-other")));
    }

    @Test
    void expiredRecoveryItemLeaseUsesMonotonicFencingToken() throws Exception {
        createIncident();
        JdbcIncidentRecoveryPlanStore first = new JdbcIncidentRecoveryPlanStore(properties, objectMapper);
        JdbcIncidentRecoveryPlanStore second = new JdbcIncidentRecoveryPlanStore(properties, objectMapper);
        Instant now = Instant.now();
        IncidentRecoveryPlanItem waiting = item(RecoveryPlanItemStatus.WAITING_APPROVAL, "", 0, null, 0);
        IncidentRecoveryPlanRecord created = new IncidentRecoveryPlanRecord(
                "lease-plan-" + incidentId, incidentId, "lease-request", "planner", "digest",
                RecoveryPlanStatus.WAITING_APPROVAL, RecoveryPlanOutcome.READY, null,
                List.of(waiting), List.of(), 0, now, now);
        first.create(created);

        var ownerA = first.claimItem(created.planId(), waiting.itemId(), "worker-a",
                Instant.now().plusMillis(150), false);
        assertTrue(ownerA.claimed());
        Thread.sleep(200);
        var ownerB = second.claimItem(created.planId(), waiting.itemId(), "worker-b",
                Instant.now().plusSeconds(30), true);

        assertTrue(ownerB.claimed());
        assertTrue(ownerB.takeover());
        assertEquals(2, ownerB.item().fencingToken());
        assertEquals(1, ownerB.item().takeoverCount());
        IncidentRecoveryPlanItem staleResult = item(
                RecoveryPlanItemStatus.RESOLVED, "worker-a", 1, null, 0);
        assertThrows(IncidentCasConflictException.class,
                () -> first.updateItemFenced(created.planId(), staleResult, "worker-a", 1));
        IncidentRecoveryPlanItem winner = item(
                RecoveryPlanItemStatus.RESOLVED, "worker-b", 2, null, 1);
        IncidentRecoveryPlanRecord completed = second.updateItemFenced(
                created.planId(), winner, "worker-b", 2);
        assertEquals(RecoveryPlanOutcome.RESOLVED, completed.outcome());
    }

    private void createIncident() {
        incidentId = "inc-phase2-it-" + UUID.randomUUID();
        Instant now = Instant.now();
        IncidentSnapshot snapshot = new IncidentSnapshot(
                "snap-" + incidentId, incidentId, "alert", "DLQ", "tenant",
                new IncidentSnapshot.IncidentOrderScope(List.of("REQ-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of()),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope");
        new JdbcIncidentStore(properties, objectMapper).create(new IncidentRecord(
                incidentId, null, null, "incident:" + incidentId, "scenario",
                IncidentStatus.ASSESSED, snapshot, Map.of(), Map.of(), 0, 1, 1, 0, now, now));
    }

    private IncidentRecoveryPlanRecord plan(String planId, String requestKey, String digest) {
        Instant now = Instant.now();
        return new IncidentRecoveryPlanRecord(
                planId + "-" + incidentId, incidentId, requestKey, "", digest,
                RecoveryPlanStatus.CREATED, RecoveryPlanOutcome.NOT_STARTED, null,
                List.of(), List.of(), 0, now, now);
    }

    private IncidentRecoveryPlanItem item(RecoveryPlanItemStatus status,
                                          String owner,
                                          long token,
                                          Instant leaseUntil,
                                          int takeovers) {
        return new IncidentRecoveryPlanItem(
                "item-1", "client-1", "REQUEST_ID", "REQ-1", "REPLAY", "reason",
                List.of("ev-1"), List.of(), status, null, "approval-1", "APPROVED",
                status == RecoveryPlanItemStatus.RESOLVED ? "SUBMITTED" : "NOT_STARTED",
                status == RecoveryPlanItemStatus.RESOLVED ? "RESOLVED" : "NOT_CONVERGED",
                null, "", owner, token, leaseUntil, Instant.now(), takeovers, Instant.now());
    }

    private AgentStorageProperties properties() {
        AgentStorageProperties result = new AgentStorageProperties();
        result.getDatasource().setUrl(environment(
                "AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent"));
        result.getDatasource().setUsername(environment("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        result.getDatasource().setPassword(environment("AGENT_STORAGE_POSTGRES_PASSWORD", ""));
        return result;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private void delete(Connection connection, String sql, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }
}
