package com.agent.platform.ordercare.incident.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.ordercare.incident.application.TaskResultSubmission;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceCandidate;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "INCIDENT_POSTGRES_IT", matches = "true")
class JdbcIncidentStorePostgresIT {

    private final AgentStorageProperties properties = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> incidentIds = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword())) {
            for (String incidentId : incidentIds) {
                delete(connection, "DELETE FROM agent_task_event WHERE incident_id = ?", incidentId);
                delete(connection, "DELETE FROM agent_evidence WHERE incident_id = ?", incidentId);
                delete(connection, "DELETE FROM agent_task WHERE incident_id = ?", incidentId);
                delete(connection, "DELETE FROM agent_incident WHERE incident_id = ?", incidentId);
            }
        }
    }

    @Test
    void commitsEvidenceEventSequenceAndTaskStateAtomically() {
        JdbcIncidentStore store = new JdbcIncidentStore(properties, objectMapper);
        Fixture fixture = runningTask(store);

        var result = store.commitTaskResult(submission(fixture, "submission-success", Map.of("count", 3)));

        assertFalse(result.duplicate());
        assertEquals(AgentTaskStatus.SUCCEEDED, result.task().status());
        assertEquals(3, result.task().version());
        assertEquals(1, result.evidence().size());
        assertEquals(TaskEventType.EVIDENCE_SUBMITTED, result.event().eventType());
        assertEquals(5, store.find(fixture.incidentId()).orElseThrow().nextEventSequence());
        assertEquals(1, store.listEvidence(fixture.incidentId()).size());
        assertEquals(4, store.loadEventsAfter(fixture.incidentId(), -1, 100).size());
    }

    @ParameterizedTest
    @EnumSource(IncidentCommitStage.class)
    void rollsBackEveryWriteWhenAnyCommitStageFails(IncidentCommitStage failureStage) {
        JdbcIncidentStore setupStore = new JdbcIncidentStore(properties, objectMapper);
        Fixture fixture = runningTask(setupStore);
        JdbcIncidentStore failingStore = new JdbcIncidentStore(
                properties,
                objectMapper,
                stage -> {
                    if (stage == failureStage) {
                        throw new InjectedCommitFailure(stage);
                    }
                });

        assertThrows(
                InjectedCommitFailure.class,
                () -> failingStore.commitTaskResult(submission(
                        fixture,
                        "submission-rollback-" + failureStage,
                        Map.of("stage", failureStage.name()))));

        AgentTaskRecord task = setupStore.findTask(fixture.taskId()).orElseThrow();
        assertEquals(AgentTaskStatus.RUNNING, task.status());
        assertEquals(2, task.version());
        assertTrue(setupStore.listEvidence(fixture.incidentId()).isEmpty());
        assertEquals(4, setupStore.find(fixture.incidentId()).orElseThrow().nextEventSequence());
        assertEquals(3, setupStore.loadEventsAfter(fixture.incidentId(), -1, 100).size());
    }

    @Test
    void duplicateSubmissionReturnsFirstCommitWithoutAppendingAgain() {
        JdbcIncidentStore store = new JdbcIncidentStore(properties, objectMapper);
        Fixture fixture = runningTask(store);
        TaskResultSubmission submission = submission(fixture, "submission-duplicate", Map.of("count", 3));

        var first = store.commitTaskResult(submission);
        var second = store.commitTaskResult(submission);

        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(first.event().eventId(), second.event().eventId());
        assertEquals(1, store.listEvidence(fixture.incidentId()).size());
        assertEquals(1, store.loadEventsAfter(fixture.incidentId(), -1, 100).stream()
                .filter(event -> event.eventType() == TaskEventType.EVIDENCE_SUBMITTED)
                .count());
        assertEquals(3, store.findTask(fixture.taskId()).orElseThrow().version());
    }

    @Test
    void conflictingIdempotencyPayloadRollsBackAndWritesIndependentControlAudit() {
        JdbcIncidentStore store = new JdbcIncidentStore(properties, objectMapper);
        Fixture fixture = runningTask(store);
        store.commitTaskResult(submission(fixture, "submission-conflict", Map.of("count", 3)));

        assertThrows(
                IncidentIdempotencyConflictException.class,
                () -> store.commitTaskResult(submission(
                        fixture,
                        "submission-conflict",
                        Map.of("count", 999))));

        assertEquals(1, store.listEvidence(fixture.incidentId()).size());
        assertEquals(1, store.loadEventsAfter(fixture.incidentId(), -1, 100).stream()
                .filter(event -> event.eventType() == TaskEventType.IDEMPOTENCY_REJECTED)
                .count());
        assertEquals(3, store.findTask(fixture.taskId()).orElseThrow().version());
    }

    @Test
    void casConflictWritesNoEvidenceOrEvent() {
        JdbcIncidentStore store = new JdbcIncidentStore(properties, objectMapper);
        Fixture fixture = runningTask(store);
        TaskResultSubmission stale = new TaskResultSubmission(
                fixture.incidentId(),
                fixture.taskId(),
                fixture.childRunId(),
                1,
                key(fixture.incidentId(), "stale"),
                AgentTaskStatus.SUCCEEDED,
                Map.of("count", 1),
                List.of(evidence(fixture, "stale")), "", 0);

        assertThrows(IncidentCasConflictException.class, () -> store.commitTaskResult(stale));

        assertTrue(store.listEvidence(fixture.incidentId()).isEmpty());
        assertEquals(3, store.loadEventsAfter(fixture.incidentId(), -1, 100).size());
        assertEquals(4, store.find(fixture.incidentId()).orElseThrow().nextEventSequence());
    }

    @Test
    void expiredTaskLeaseIsTakenOverAndOldFencingTokenCannotCommit() throws Exception {
        JdbcIncidentStore first = new JdbcIncidentStore(properties, objectMapper);
        JdbcIncidentStore second = new JdbcIncidentStore(properties, objectMapper);
        Fixture fixture = runningTask(first);
        try (Connection connection = DriverManager.getConnection(
                properties.getDatasource().getUrl(), properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_task SET claimed_by = 'worker-a', claim_until = ?,
                         fencing_token = 1, last_heartbeat_at = ? WHERE task_id = ?
                     """)) {
            statement.setObject(1, java.time.OffsetDateTime.now().minusSeconds(1));
            statement.setObject(2, java.time.OffsetDateTime.now().minusSeconds(2));
            statement.setString(3, fixture.taskId());
            statement.executeUpdate();
        }

        AgentTaskRecord stale = first.findTask(fixture.taskId()).orElseThrow();
        var takeover = second.claimTask(stale.taskId(), stale.version(), "worker-b",
                Instant.now().plusSeconds(30), true);

        assertTrue(takeover.claimed());
        assertTrue(takeover.takeover());
        assertEquals(2, takeover.task().fencingToken());
        assertEquals(1, takeover.task().attempt());
        AgentTaskRecord running = second.transitionLeasedTask(
                takeover.task().taskId(), takeover.task().version(), AgentTaskStatus.RUNNING,
                takeover.task().childRunId(), "", "worker-b", takeover.task().fencingToken(),
                TaskEventActorType.SYSTEM, "worker-b", key(fixture.incidentId(), "takeover-running"));
        assertThrows(IncidentCasConflictException.class, () -> first.commitTaskResult(
                new TaskResultSubmission(
                        fixture.incidentId(), fixture.taskId(), fixture.childRunId(), running.version(),
                        key(fixture.incidentId(), "old-owner-result"), AgentTaskStatus.SUCCEEDED,
                        Map.of("owner", "worker-a"), List.of(evidence(fixture, "old-owner")),
                        "worker-a", 1)));
        AgentTaskRecord renewed = second.renewTaskLease(
                running.taskId(), "worker-b", running.fencingToken(), Instant.now().plusSeconds(30));
        assertEquals("worker-b", renewed.claimedBy());
        assertEquals(2, renewed.fencingToken());
        assertTrue(first.listEvidence(fixture.incidentId()).isEmpty());
        assertEquals(1, first.loadEventsAfter(fixture.incidentId(), -1, 100).stream()
                .filter(event -> event.eventType() == TaskEventType.TASK_LEASE_RECOVERED)
                .count());

        try (Connection connection = DriverManager.getConnection(
                properties.getDatasource().getUrl(), properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE agent_task SET claim_until = ? WHERE task_id = ?")) {
            statement.setObject(1, java.time.OffsetDateTime.now().minusSeconds(1));
            statement.setString(2, fixture.taskId());
            statement.executeUpdate();
        }
        AgentTaskRecord expiredAgain = first.findTask(fixture.taskId()).orElseThrow();
        var exhausted = first.claimTask(expiredAgain.taskId(), expiredAgain.version(), "worker-c",
                Instant.now().plusSeconds(30), true);
        assertFalse(exhausted.claimed());
        assertTrue(exhausted.takeover());
        assertEquals(AgentTaskStatus.FAILED, exhausted.task().status());
        assertEquals("stale task takeover retry budget exhausted", exhausted.task().lastError());
    }

    private Fixture runningTask(JdbcIncidentStore store) {
        String suffix = UUID.randomUUID().toString();
        String incidentId = "it-inc-" + suffix;
        String taskId = "it-task-" + suffix;
        String childRunId = "it-run-" + suffix;
        incidentIds.add(incidentId);
        Instant now = Instant.now();
        IncidentSnapshot snapshot = new IncidentSnapshot(
                "it-snap-" + suffix,
                incidentId,
                "it-alert-" + suffix,
                "STOCK_RELEASE_DLQ_BACKLOG",
                "local-demo",
                new IncidentSnapshot.IncidentOrderScope(List.of("REQ-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of("orders.dlq")),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now.minusSeconds(30),
                now,
                now.plusSeconds(300),
                "scope-" + suffix);
        store.create(new IncidentRecord(
                incidentId,
                null,
                null,
                "conversation-" + suffix,
                "ordercare-incident-command-v1",
                IncidentStatus.CREATED,
                snapshot,
                Map.of(),
                Map.of(),
                0,
                1,
                1,
                0,
                now,
                now));
        store.create(new AgentTaskRecord(
                taskId,
                incidentId,
                "orders",
                "SPECIALIST_INVESTIGATION",
                "ORDER_ANALYST",
                "Inspect order terminal states",
                100,
                List.of(),
                List.of(EvidenceSubtype.ORDER_STATUS_SET),
                Map.of("snapshotId", snapshot.snapshotId()),
                Map.of(),
                AgentTaskStatus.PENDING,
                0,
                2,
                null,
                null,
                now.plusSeconds(120),
                null,
                null,
                0,
                null,
                null,
                0,
                now,
                now));
        store.transitionTask(
                taskId,
                0,
                AgentTaskStatus.CLAIMED,
                childRunId,
                null,
                TaskEventActorType.ORCHESTRATOR,
                "test-scheduler",
                key(incidentId, "claimed"));
        store.transitionTask(
                taskId,
                1,
                AgentTaskStatus.RUNNING,
                childRunId,
                null,
                TaskEventActorType.RUNTIME,
                childRunId,
                key(incidentId, "running"));
        return new Fixture(incidentId, taskId, childRunId);
    }

    private TaskResultSubmission submission(Fixture fixture,
                                            String idempotencySuffix,
                                            Map<String, Object> output) {
        return new TaskResultSubmission(
                fixture.incidentId(),
                fixture.taskId(),
                fixture.childRunId(),
                2,
                key(fixture.incidentId(), idempotencySuffix),
                AgentTaskStatus.SUCCEEDED,
                output,
                List.of(evidence(fixture, idempotencySuffix)), "", 0);
    }

    private EvidenceCandidate evidence(Fixture fixture, String suffix) {
        return new EvidenceCandidate(
                EvidenceClass.FACT,
                EvidenceSubtype.ORDER_STATUS_SET,
                "floworder-resource-service",
                "incident/order-facts/" + fixture.incidentId(),
                Map.of("snapshotId", "snapshot"),
                Instant.now(),
                Map.of("requestIds", List.of("REQ-1"), "recordCount", 1),
                EvidenceStatus.ACCEPTED,
                null,
                key(fixture.incidentId(), "evidence-" + suffix));
    }

    private String key(String incidentId, String suffix) {
        return incidentId + ":" + suffix;
    }

    private void delete(Connection connection, String sql, String incidentId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, incidentId);
            statement.executeUpdate();
        }
    }

    private AgentStorageProperties properties() {
        AgentStorageProperties properties = new AgentStorageProperties();
        properties.getDatasource().setUrl(environment(
                "AGENT_STORAGE_POSTGRES_URL",
                "jdbc:postgresql://localhost:5432/enterprise_agent"));
        properties.getDatasource().setUsername(environment("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        properties.getDatasource().setPassword(environment("AGENT_STORAGE_POSTGRES_PASSWORD", ""));
        return properties;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Fixture(String incidentId, String taskId, String childRunId) {
    }

    private static final class InjectedCommitFailure extends RuntimeException {
        private InjectedCommitFailure(IncidentCommitStage stage) {
            super("injected failure after " + stage);
        }
    }
}
