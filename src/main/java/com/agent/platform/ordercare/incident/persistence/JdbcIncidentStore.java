package com.agent.platform.ordercare.incident.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.ordercare.incident.application.TaskResultCommitResult;
import com.agent.platform.ordercare.incident.application.TaskResultSubmission;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceCandidate;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.model.TaskEventCategory;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.model.TaskLeaseClaim;
import com.agent.platform.storage.AgentStorageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class JdbcIncidentStore implements IncidentStore,
        AgentTaskStore,
        EvidenceStore,
        TaskEventStore,
        IncidentTaskResultPersistence {

    private static final int MAX_EVENT_LIMIT = 10_000;

    private final AgentStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final IncidentCommitFailureInjector failureInjector;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    @Autowired
    public JdbcIncidentStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, IncidentCommitFailureInjector.NOOP);
    }

    JdbcIncidentStore(AgentStorageProperties properties,
                      ObjectMapper objectMapper,
                      IncidentCommitFailureInjector failureInjector) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.failureInjector = failureInjector == null ? IncidentCommitFailureInjector.NOOP : failureInjector;
    }

    @Override
    public IncidentRecord create(IncidentRecord incident) {
        validateIncident(incident);
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_incident(
                         incident_id, commander_run_id, reviewer_run_id, conversation_id,
                         scenario_id, status, snapshot_json, delegation_plan_json,
                         assessment_json, clarification_count, max_clarifications,
                         next_event_sequence, version, created_at, updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                     """)) {
            bindIncident(statement, incident);
            statement.executeUpdate();
            return incident;
        } catch (SQLException exception) {
            throw storageFailure("Failed to create incident: " + incident.incidentId(), exception);
        }
    }

    @Override
    public IncidentRecord createForDispatch(String dispatchRequestId, IncidentRecord incident) {
        validateIncident(incident);
        requireText(dispatchRequestId, "dispatchRequestId");
        ensureSchema();
        Optional<IncidentRecord> existing = findByDispatchRequestId(dispatchRequestId);
        if (existing.isPresent()) return sameDispatchScope(existing.get(), incident);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_incident(
                         incident_id, commander_run_id, reviewer_run_id, conversation_id,
                         scenario_id, status, snapshot_json, delegation_plan_json,
                         assessment_json, clarification_count, max_clarifications,
                         next_event_sequence, version, created_at, updated_at, dispatch_request_id
                     ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bindIncident(statement, incident);
            statement.setString(16, dispatchRequestId.trim());
            statement.executeUpdate();
            return incident;
        }
        catch (SQLException exception) {
            return findByDispatchRequestId(dispatchRequestId)
                    .map(value -> sameDispatchScope(value, incident))
                    .orElseThrow(() -> storageFailure("Failed to create incident dispatch binding", exception));
        }
    }

    @Override
    public Optional<IncidentRecord> findByDispatchRequestId(String dispatchRequestId) {
        if (!hasText(dispatchRequestId)) return Optional.empty();
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT incident_id FROM agent_incident WHERE dispatch_request_id = ?")) {
            statement.setString(1, dispatchRequestId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? loadIncident(connection, resultSet.getString(1), false) : Optional.empty();
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to read incident dispatch binding", exception);
        }
    }

    private IncidentRecord sameDispatchScope(IncidentRecord existing, IncidentRecord requested) {
        if (!existing.snapshot().scopeHash().equals(requested.snapshot().scopeHash())) {
            throw new IllegalArgumentException("dispatchRequestId is bound to another incident scope");
        }
        return existing;
    }

    @Override
    public Optional<IncidentRecord> find(String incidentId) {
        if (!hasText(incidentId)) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            return loadIncident(connection, incidentId.trim(), false);
        } catch (SQLException exception) {
            throw storageFailure("Failed to read incident: " + incidentId, exception);
        }
    }

    @Override
    public Optional<IncidentSnapshot> findSnapshot(String snapshotId) {
        if (!hasText(snapshotId)) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT snapshot_json
                     FROM agent_incident
                     WHERE snapshot_json ->> 'snapshotId' = ?
                     """)) {
            statement.setString(1, snapshotId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(fromJson(resultSet.getString("snapshot_json"), IncidentSnapshot.class))
                        : Optional.empty();
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to read incident snapshot: " + snapshotId, exception);
        }
    }

    @Override
    public Optional<IncidentAggregate> findAggregate(String incidentId, int eventLimit) {
        if (!hasText(incidentId)) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            Optional<IncidentRecord> incident = loadIncident(connection, incidentId.trim(), false);
            if (incident.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new IncidentAggregate(
                    incident.get(),
                    loadTasks(connection, incidentId.trim()),
                    loadEvidence(connection, incidentId.trim()),
                    loadEvents(connection, incidentId.trim(), -1, eventLimit)));
        } catch (SQLException exception) {
            throw storageFailure("Failed to read incident aggregate: " + incidentId, exception);
        }
    }

    @Override
    public IncidentRecord transitionStatus(String incidentId,
                                           long expectedVersion,
                                           IncidentStatus targetStatus,
                                           TaskEventActorType actorType,
                                           String actorId,
                                           String idempotencyKey) {
        requireText(incidentId, "incidentId");
        requireText(actorId, "actorId");
        requireText(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(targetStatus, "targetStatus");
        Objects.requireNonNull(actorType, "actorType");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                IncidentRecord current = loadIncident(connection, incidentId, true)
                        .orElseThrow(() -> new IllegalArgumentException("incident not found: " + incidentId));
                // 幂等：同一迁移不重复执行
                Optional<TaskEventRecord> previous = findEventByIdempotencyKey(connection, idempotencyKey);
                if (previous.isPresent()) {
                    assertSameTransition(previous.get(), targetStatus.name());
                    connection.commit();
                    return loadIncident(connection, incidentId, false).orElseThrow();
                }
                //  CAS 乐观锁：防止并发覆盖
                if (current.version() != expectedVersion) {
                    throw new IncidentCasConflictException(
                            "incident version mismatch: expected=" + expectedVersion + ", actual=" + current.version());
                }
                //  合法迁移校验：防止非法跳转。状态机保证状态之间不能非法的跳转
                if (!canTransition(current.status(), targetStatus)) {
                    throw new IllegalStateException(
                            "invalid incident transition: " + current.status() + " -> " + targetStatus);
                }
                Instant now = Instant.now();
                Map<String, Object> payload = transitionPayload(
                        current.status().name(), targetStatus.name(), expectedVersion, expectedVersion + 1);
                // 审计事件
                appendEvent(connection, new TaskEventRecord(
                        UUID.randomUUID().toString(),
                        incidentId,
                        null,
                        null,
                        allocateEventSequence(connection, incidentId),
                        TaskEventType.INCIDENT_STATE_CHANGED,
                        TaskEventCategory.LIFECYCLE,
                        actorType,
                        actorId,
                        null,
                        null,
                        0,
                        incidentId,
                        null,
                        idempotencyKey,
                        payload,
                        now));
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_incident
                        SET status = ?, version = version + 1, updated_at = ?
                        WHERE incident_id = ? AND version = ?
                        """)) {
                    statement.setString(1, targetStatus.name());
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setString(3, incidentId);
                    statement.setLong(4, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new IncidentCasConflictException("incident CAS update failed: " + incidentId);
                    }
                }
                IncidentRecord updated = loadIncident(connection, incidentId, false).orElseThrow();
                connection.commit();
                return updated;
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to transition incident: " + incidentId, exception);
        }
    }

    @Override
    public IncidentRecord updateDetails(String incidentId,
                                        long expectedVersion,
                                        String commanderRunId,
                                        String reviewerRunId,
                                        Map<String, Object> delegationPlan,
                                        Map<String, Object> assessment,
                                        boolean incrementClarification) {
        requireText(incidentId, "incidentId");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                IncidentRecord current = loadIncident(connection, incidentId, true)
                        .orElseThrow(() -> new IllegalArgumentException("incident not found: " + incidentId));
                if (current.version() != expectedVersion) {
                    throw new IncidentCasConflictException(
                            "incident version mismatch: expected=" + expectedVersion + ", actual=" + current.version());
                }
                if (incrementClarification
                        && current.clarificationCount() >= current.maxClarifications()) {
                    throw new IllegalStateException("incident clarification budget exhausted: " + incidentId);
                }
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_incident
                        SET commander_run_id = COALESCE(?, commander_run_id),
                            reviewer_run_id = COALESCE(?, reviewer_run_id),
                            delegation_plan_json = CASE WHEN ? IS NULL THEN delegation_plan_json ELSE ?::jsonb END,
                            assessment_json = CASE WHEN ? IS NULL THEN assessment_json ELSE ?::jsonb END,
                            clarification_count = clarification_count + ?,
                            version = version + 1,
                            updated_at = ?
                        WHERE incident_id = ? AND version = ?
                        """)) {
                    statement.setString(1, blankToNull(commanderRunId));
                    statement.setString(2, blankToNull(reviewerRunId));
                    String planJson = delegationPlan == null ? null : toJson(delegationPlan);
                    String assessmentJson = assessment == null ? null : toJson(assessment);
                    statement.setString(3, planJson);
                    statement.setString(4, planJson);
                    statement.setString(5, assessmentJson);
                    statement.setString(6, assessmentJson);
                    statement.setInt(7, incrementClarification ? 1 : 0);
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.setString(9, incidentId);
                    statement.setLong(10, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new IncidentCasConflictException("incident detail CAS update failed: " + incidentId);
                    }
                }
                IncidentRecord updated = loadIncident(connection, incidentId, false).orElseThrow();
                connection.commit();
                return updated;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to update incident details: " + incidentId, exception);
        }
    }

    @Override
    public AgentTaskRecord create(AgentTaskRecord task) {
        validateTask(task);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                lockIncident(connection, task.incidentId());
                insertTask(connection, task);
                appendEvent(connection, new TaskEventRecord(
                        UUID.randomUUID().toString(),
                        task.incidentId(),
                        task.taskId(),
                        task.childRunId(),
                        allocateEventSequence(connection, task.incidentId()),
                        TaskEventType.TASK_ASSIGNMENT,
                        TaskEventCategory.COMMUNICATION,
                        TaskEventActorType.ORCHESTRATOR,
                        "incident-orchestrator",
                        "INCIDENT_COMMANDER",
                        task.role(),
                        1,
                        task.taskId(),
                        null,
                        "task-assignment:" + task.incidentId() + ":" + task.clientTaskKey(),
                        Map.of(
                                "taskId", task.taskId(),
                                "objective", task.objective(),
                                "requiredEvidenceSubtypes", task.requiredEvidenceSubtypes()),
                        task.createdAt()));
                connection.commit();
                return task;
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to create incident task: " + task.taskId(), exception);
        }
    }

    @Override
    public AgentTaskRecord createOrGet(AgentTaskRecord task) {
        validateTask(task);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                // 所有同一 Incident 的任务创建先锁定父记录，使“先查再插”在多实例下仍然串行。
                lockIncident(connection, task.incidentId());
                Optional<AgentTaskRecord> existing = loadTaskByClientKey(
                        connection, task.incidentId(), task.clientTaskKey());
                if (existing.isPresent()) {
                    assertSameTaskIdentity(existing.get(), task);
                    connection.commit();
                    return existing.get();
                }
                insertTask(connection, task);
                appendEvent(connection, new TaskEventRecord(
                        UUID.randomUUID().toString(), task.incidentId(), task.taskId(), task.childRunId(),
                        allocateEventSequence(connection, task.incidentId()), TaskEventType.TASK_ASSIGNMENT,
                        TaskEventCategory.COMMUNICATION, TaskEventActorType.ORCHESTRATOR,
                        "incident-subagent-tool", "INCIDENT_COMMANDER", task.role(), 1,
                        task.taskId(), null,
                        "task-assignment:" + task.incidentId() + ":" + task.clientTaskKey(),
                        Map.of(
                                "taskId", task.taskId(),
                                "objective", task.objective(),
                                "requiredEvidenceSubtypes", task.requiredEvidenceSubtypes(),
                                "delegatedAsTool", true),
                        task.createdAt()));
                connection.commit();
                return task;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to create or read incident task: " + task.clientTaskKey(), exception);
        }
    }

    @Override
    public Optional<AgentTaskRecord> findTask(String taskId) {
        if (!hasText(taskId)) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            return loadTask(connection, taskId.trim(), false);
        } catch (SQLException exception) {
            throw storageFailure("Failed to read incident task: " + taskId, exception);
        }
    }

    @Override
    public List<AgentTaskRecord> listTasks(String incidentId) {
        if (!hasText(incidentId)) {
            return List.of();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            return loadTasks(connection, incidentId.trim());
        } catch (SQLException exception) {
            throw storageFailure("Failed to list incident tasks: " + incidentId, exception);
        }
    }

    @Override
    public TaskLeaseClaim claimTask(String taskId,
                                    long expectedVersion,
                                    String owner,
                                    Instant leaseUntil,
                                    boolean allowExpiredTakeover) {
        requireText(taskId, "taskId");
        requireText(owner, "owner");
        Instant now = Instant.now();
        if (leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be in the future");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentTaskRecord identity = loadTask(connection, taskId, false)
                        .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
                lockIncident(connection, identity.incidentId());
                AgentTaskRecord current = loadTask(connection, taskId, true).orElseThrow();
                if (current.version() != expectedVersion) {
                    throw new IncidentCasConflictException("task claim version mismatch: " + taskId);
                }
                boolean initial = current.status() == AgentTaskStatus.PENDING
                        || current.status() == AgentTaskStatus.RETRY_PENDING;
                boolean expired = (current.status() == AgentTaskStatus.CLAIMED
                        || current.status() == AgentTaskStatus.RUNNING)
                        && (current.claimUntil() == null || !current.claimUntil().isAfter(now));
                if (!initial && !(allowExpiredTakeover && expired)) {
                    connection.commit();
                    return new TaskLeaseClaim(current, current.status(), false, false);
                }
                if (expired && current.attempt() + 1 >= current.maxAttempts()) {
                    String reason = "stale task takeover retry budget exhausted";
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE agent_task SET status = 'FAILED', claimed_by = NULL, claim_until = NULL,
                                last_error = ?, version = version + 1, updated_at = ?
                            WHERE task_id = ? AND version = ?
                            """)) {
                        statement.setString(1, reason);
                        statement.setTimestamp(2, Timestamp.from(now));
                        statement.setString(3, taskId);
                        statement.setLong(4, expectedVersion);
                        if (statement.executeUpdate() != 1) {
                            throw new IncidentCasConflictException("task exhausted takeover CAS failed: " + taskId);
                        }
                    }
                    AgentTaskRecord failed = loadTask(connection, taskId, false).orElseThrow();
                    appendEvent(connection, new TaskEventRecord(
                            UUID.randomUUID().toString(), failed.incidentId(), taskId, failed.childRunId(),
                            allocateEventSequence(connection, failed.incidentId()), TaskEventType.TASK_STATE_CHANGED,
                            TaskEventCategory.CONTROL, TaskEventActorType.SYSTEM, owner, null, null, 0,
                            taskId, null, "task-lease-exhausted:" + taskId + ":" + failed.fencingToken(),
                            Map.of("sourceStatus", current.status().name(), "targetStatus", "FAILED",
                                    "reason", reason), now));
                    connection.commit();
                    return new TaskLeaseClaim(failed, current.status(), false, true);
                }
                AgentTaskStatus previous = current.status();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_task
                        SET status = 'CLAIMED', claimed_by = ?, claim_until = ?,
                            fencing_token = fencing_token + 1, last_heartbeat_at = ?,
                            attempt = CASE WHEN ? THEN attempt + 1 ELSE attempt END,
                            version = version + 1, updated_at = ?
                        WHERE task_id = ? AND version = ?
                        """)) {
                    statement.setString(1, owner);
                    statement.setTimestamp(2, Timestamp.from(leaseUntil));
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setBoolean(4, expired);
                    statement.setTimestamp(5, Timestamp.from(now));
                    statement.setString(6, taskId);
                    statement.setLong(7, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new IncidentCasConflictException("task lease claim CAS failed: " + taskId);
                    }
                }
                AgentTaskRecord claimed = loadTask(connection, taskId, false).orElseThrow();
                appendEvent(connection, new TaskEventRecord(
                        UUID.randomUUID().toString(), claimed.incidentId(), taskId, claimed.childRunId(),
                        allocateEventSequence(connection, claimed.incidentId()),
                        expired ? TaskEventType.TASK_LEASE_RECOVERED : TaskEventType.TASK_LEASE_CLAIMED,
                        TaskEventCategory.CONTROL, TaskEventActorType.SYSTEM, owner,
                        null, null, 0, taskId, null,
                        "task-lease:" + taskId + ":" + claimed.fencingToken(),
                        Map.of("previousStatus", previous.name(), "owner", owner,
                                "fencingToken", claimed.fencingToken(), "leaseUntil", leaseUntil.toString(),
                                "takeover", expired), now));
                connection.commit();
                return new TaskLeaseClaim(claimed, previous, true, expired);
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to claim incident task: " + taskId, exception);
        }
    }

    @Override
    public AgentTaskRecord renewTaskLease(String taskId,
                                          String owner,
                                          long fencingToken,
                                          Instant leaseUntil) {
        Instant now = Instant.now();
        if (leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be in the future");
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_task
                     SET claim_until = ?, last_heartbeat_at = ?, updated_at = ?
                     WHERE task_id = ? AND claimed_by = ? AND fencing_token = ?
                       AND status IN ('CLAIMED','RUNNING')
                     """)) {
            statement.setTimestamp(1, Timestamp.from(leaseUntil));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, taskId);
            statement.setString(5, owner);
            statement.setLong(6, fencingToken);
            if (statement.executeUpdate() != 1) {
                throw new IncidentCasConflictException("task lease renewal rejected by fencing token: " + taskId);
            }
            return findTask(taskId).orElseThrow();
        } catch (SQLException exception) {
            throw storageFailure("Failed to renew incident task lease: " + taskId, exception);
        }
    }

    @Override
    public AgentTaskRecord transitionLeasedTask(String taskId,
                                                long expectedVersion,
                                                AgentTaskStatus targetStatus,
                                                String childRunId,
                                                String lastError,
                                                String owner,
                                                long fencingToken,
                                                TaskEventActorType actorType,
                                                String actorId,
                                                String idempotencyKey) {
        AgentTaskRecord current = findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (!current.leaseOwnedBy(owner, fencingToken, Instant.now())) {
            throw new IncidentCasConflictException("task transition rejected by expired/stale lease: " + taskId);
        }
        return transitionTask(taskId, expectedVersion, targetStatus, childRunId, lastError,
                actorType, actorId, idempotencyKey);
    }

    @Override
    public List<AgentTaskRecord> listStaleTasks(Instant now, int limit) {
        if (now == null || limit < 1) return List.of();
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM agent_task
                     WHERE status IN ('CLAIMED','RUNNING') AND (claim_until IS NULL OR claim_until <= ?)
                     ORDER BY claim_until ASC
                     LIMIT ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, Math.min(limit, 100));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentTaskRecord> result = new ArrayList<>();
                while (resultSet.next()) result.add(readTask(resultSet));
                return List.copyOf(result);
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to list stale incident tasks", exception);
        }
    }

    @Override
    public AgentTaskRecord transitionTask(String taskId,
                                          long expectedVersion,
                                          AgentTaskStatus targetStatus,
                                          String childRunId,
                                          String lastError,
                                          TaskEventActorType actorType,
                                          String actorId,
                                          String idempotencyKey) {
        requireText(taskId, "taskId");
        requireText(actorId, "actorId");
        requireText(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(targetStatus, "targetStatus");
        Objects.requireNonNull(actorType, "actorType");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentTaskRecord taskIdentity = loadTask(connection, taskId, false)
                        .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
                lockIncident(connection, taskIdentity.incidentId());
                AgentTaskRecord current = loadTask(connection, taskId, true).orElseThrow();
                Optional<TaskEventRecord> previous = findEventByIdempotencyKey(connection, idempotencyKey);
                if (previous.isPresent()) {
                    assertSameTransition(previous.get(), targetStatus.name());
                    connection.commit();
                    return loadTask(connection, taskId, false).orElseThrow();
                }
                if (current.version() != expectedVersion) {
                    throw new IncidentCasConflictException(
                            "task version mismatch: expected=" + expectedVersion + ", actual=" + current.version());
                }
                if (!canTransition(current.status(), targetStatus)) {
                    throw new IllegalStateException(
                            "invalid task transition: " + current.status() + " -> " + targetStatus);
                }
                if (targetStatus == AgentTaskStatus.RETRY_PENDING
                        && current.attempt() + 1 >= current.maxAttempts()) {
                    throw new IllegalStateException("task retry budget exhausted: " + taskId);
                }
                Instant now = Instant.now();
                Map<String, Object> payload = transitionPayload(
                        current.status().name(), targetStatus.name(), expectedVersion, expectedVersion + 1);
                TaskEventType eventType = targetStatus == AgentTaskStatus.RETRY_PENDING
                        ? TaskEventType.TASK_RETRY_SCHEDULED
                        : TaskEventType.TASK_STATE_CHANGED;
                TaskEventCategory category = targetStatus == AgentTaskStatus.RETRY_PENDING
                        ? TaskEventCategory.CONTROL
                        : TaskEventCategory.LIFECYCLE;
                appendEvent(connection, new TaskEventRecord(
                        UUID.randomUUID().toString(),
                        current.incidentId(),
                        taskId,
                        childRunId == null ? current.childRunId() : childRunId,
                        allocateEventSequence(connection, current.incidentId()),
                        eventType,
                        category,
                        actorType,
                        actorId,
                        null,
                        null,
                        0,
                        taskId,
                        null,
                        idempotencyKey,
                        payload,
                        now));
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_task
                        SET status = ?,
                            child_run_id = COALESCE(?, child_run_id),
                            first_child_run_id = COALESCE(first_child_run_id, ?),
                            last_error = ?,
                            attempt = CASE WHEN ? = 'RETRY_PENDING' THEN attempt + 1 ELSE attempt END,
                            claimed_by = CASE WHEN ? IN ('WAITING_CLARIFICATION','SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN NULL ELSE claimed_by END,
                            claim_until = CASE WHEN ? IN ('WAITING_CLARIFICATION','SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN NULL ELSE claim_until END,
                            version = version + 1,
                            updated_at = ?
                        WHERE task_id = ? AND version = ?
                        """)) {
                    statement.setString(1, targetStatus.name());
                    statement.setString(2, blankToNull(childRunId));
                    statement.setString(3, blankToNull(childRunId));
                    statement.setString(4, blankToNull(lastError));
                    statement.setString(5, targetStatus.name());
                    statement.setString(6, targetStatus.name());
                    statement.setString(7, targetStatus.name());
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.setString(9, taskId);
                    statement.setLong(10, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new IncidentCasConflictException("task CAS update failed: " + taskId);
                    }
                }
                AgentTaskRecord updated = loadTask(connection, taskId, false).orElseThrow();
                connection.commit();
                return updated;
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to transition incident task: " + taskId, exception);
        }
    }

    @Override
    public AgentTaskRecord bindChildRun(String taskId,
                                        long expectedVersion,
                                        String childRunId,
                                        String idempotencyKey) {
        requireText(taskId, "taskId");
        requireText(childRunId, "childRunId");
        requireText(idempotencyKey, "idempotencyKey");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentTaskRecord taskIdentity = loadTask(connection, taskId, false)
                        .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
                lockIncident(connection, taskIdentity.incidentId());
                AgentTaskRecord current = loadTask(connection, taskId, true).orElseThrow();
                Optional<TaskEventRecord> previous = findEventByIdempotencyKey(connection, idempotencyKey);
                if (previous.isPresent()) {
                    connection.commit();
                    return loadTask(connection, taskId, false).orElseThrow();
                }
                if (current.version() != expectedVersion || current.status() != AgentTaskStatus.RUNNING) {
                    throw new IncidentCasConflictException("task changed before childRun binding: " + taskId);
                }
                if (hasText(current.childRunId())
                        && !current.childRunId().equals(childRunId)
                        && current.attempt() == 0) {
                    throw new IncidentIdempotencyConflictException("task is already bound to another childRunId");
                }
                Instant now = Instant.now();
                appendEvent(connection, new TaskEventRecord(
                        UUID.randomUUID().toString(), current.incidentId(), taskId, childRunId,
                        allocateEventSequence(connection, current.incidentId()),
                        TaskEventType.TASK_STATE_CHANGED, TaskEventCategory.LIFECYCLE,
                        TaskEventActorType.RUNTIME, "agent-runtime", null, null, 0,
                        taskId, null, idempotencyKey,
                        Map.of("sourceStatus", current.status().name(),
                                "targetStatus", current.status().name(),
                                "childRunId", childRunId,
                                "childRunBound", true), now));
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_task
                        SET child_run_id = ?, first_child_run_id = COALESCE(first_child_run_id, ?),
                            version = version + 1, updated_at = ?
                        WHERE task_id = ? AND version = ?
                        """)) {
                    statement.setString(1, childRunId);
                    statement.setString(2, childRunId);
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setString(4, taskId);
                    statement.setLong(5, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new IncidentCasConflictException("task childRun CAS update failed: " + taskId);
                    }
                }
                AgentTaskRecord updated = loadTask(connection, taskId, false).orElseThrow();
                connection.commit();
                return updated;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to bind child run: " + taskId, exception);
        }
    }

    @Override
    public List<EvidenceRecord> listEvidence(String incidentId) {
        if (!hasText(incidentId)) {
            return List.of();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            return loadEvidence(connection, incidentId.trim());
        } catch (SQLException exception) {
            throw storageFailure("Failed to list incident evidence: " + incidentId, exception);
        }
    }

    @Override
    public TaskEventRecord appendEvent(TaskEventRecord event) {
        Objects.requireNonNull(event, "event");
        validateEvent(event);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<TaskEventRecord> previous = findEventByIdempotencyKey(
                        connection, event.idempotencyKey());
                if (previous.isPresent()) {
                    connection.commit();
                    return previous.get();
                }
                TaskEventRecord persisted = new TaskEventRecord(
                        event.eventId(), event.incidentId(), event.taskId(), event.childRunId(),
                        allocateEventSequence(connection, event.incidentId()), event.eventType(),
                        event.eventCategory(), event.actorType(), event.actorId(), event.senderRole(),
                        event.recipientRole(), event.messageDepth(), event.correlationId(),
                        event.causationId(), event.idempotencyKey(), event.payload(), event.createdAt());
                appendEvent(connection, persisted);
                connection.commit();
                return persisted;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to append incident event: " + event.incidentId(), exception);
        }
    }

    @Override
    public List<TaskEventRecord> loadEventsAfter(String incidentId, long afterSequence, int limit) {
        if (!hasText(incidentId)) {
            return List.of();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            return loadEvents(connection, incidentId.trim(), afterSequence, limit);
        } catch (SQLException exception) {
            throw storageFailure("Failed to load incident events: " + incidentId, exception);
        }
    }

    @Override
    public TaskResultCommitResult commitTaskResult(TaskResultSubmission submission) {
        validateSubmission(submission);
        ensureSchema();
        String submissionHash = submissionHash(submission);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                lockIncident(connection, submission.incidentId());
                AgentTaskRecord current = loadTask(connection, submission.taskId(), true)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "task not found: " + submission.taskId()));
                Optional<TaskEventRecord> priorEvent = findEventByIdempotencyKey(
                        connection,
                        submission.idempotencyKey());
                if (priorEvent.isPresent()) {
                    TaskResultCommitResult duplicate = duplicateCommitResult(
                            connection,
                            current,
                            priorEvent.get(),
                            submissionHash);
                    connection.commit();
                    return duplicate;
                }
                validateSubmissionAgainstTask(submission, current);
                if (!submission.leaseOwner().isBlank()
                        && !current.leaseOwnedBy(submission.leaseOwner(), submission.fencingToken(), Instant.now())) {
                    throw new IncidentCasConflictException(
                            "task result rejected by expired/stale fencing token: " + submission.taskId());
                }

                Instant now = Instant.now();
                List<EvidenceRecord> evidence = new ArrayList<>();
                for (EvidenceCandidate candidate : submission.evidence()) {
                    validateCandidate(candidate);
                    EvidenceRecord record = toEvidenceRecord(submission, candidate, now);
                    insertEvidence(connection, record);
                    evidence.add(record);
                }
                failureInjector.after(IncidentCommitStage.EVIDENCE_WRITTEN);

                long sequence = allocateEventSequence(connection, submission.incidentId());
                Map<String, Object> eventPayload = new LinkedHashMap<>();
                eventPayload.put("submissionHash", submissionHash);
                eventPayload.put("evidenceIds", evidence.stream().map(EvidenceRecord::evidenceId).toList());
                eventPayload.put("targetStatus", submission.targetStatus().name());
                eventPayload.put("expectedVersion", submission.expectedVersion());
                eventPayload.put("resultingVersion", submission.expectedVersion() + 1);
                eventPayload.put("outputSummary", submission.outputSummary());
                TaskEventRecord event = new TaskEventRecord(
                        UUID.randomUUID().toString(),
                        submission.incidentId(),
                        submission.taskId(),
                        submission.childRunId(),
                        sequence,
                        TaskEventType.EVIDENCE_SUBMITTED,
                        TaskEventCategory.COMMUNICATION,
                        TaskEventActorType.AGENT,
                        submission.childRunId(),
                        current.role(),
                        "INCIDENT_REVIEWER",
                        1,
                        submission.taskId(),
                        null,
                        submission.idempotencyKey(),
                        eventPayload,
                        now);
                appendEvent(connection, event);
                failureInjector.after(IncidentCommitStage.EVENT_WRITTEN);
                failureInjector.after(IncidentCommitStage.BEFORE_TASK_CAS);

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_task
                        SET status = ?, output_summary_json = ?::jsonb,
                            claimed_by = NULL, claim_until = NULL,
                            version = version + 1, updated_at = ?
                        WHERE task_id = ? AND incident_id = ? AND version = ?
                        """)) {
                    statement.setString(1, submission.targetStatus().name());
                    statement.setString(2, toJson(submission.outputSummary()));
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setString(4, submission.taskId());
                    statement.setString(5, submission.incidentId());
                    statement.setLong(6, submission.expectedVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new IncidentCasConflictException(
                                "task result CAS update failed: " + submission.taskId());
                    }
                }

                AgentTaskRecord updated = loadTask(connection, submission.taskId(), false).orElseThrow();
                connection.commit();
                return new TaskResultCommitResult(updated, evidence, event, false);
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (IncidentIdempotencyConflictException exception) {
            try {
                recordIdempotencyRejection(submission, submissionHash, exception.getMessage());
            } catch (RuntimeException auditFailure) {
                exception.addSuppressed(auditFailure);
            }
            throw exception;
        } catch (SQLException exception) {
            throw storageFailure("Failed to commit incident task result: " + submission.taskId(), exception);
        }
    }

    private void recordIdempotencyRejection(TaskResultSubmission submission,
                                            String submissionHash,
                                            String reason) {
        String auditIdempotencyKey = "idempotency-rejected:"
                + sha256(submission.idempotencyKey() + ":" + submissionHash).substring(0, 32);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                lockIncident(connection, submission.incidentId());
                if (findEventByIdempotencyKey(connection, auditIdempotencyKey).isPresent()) {
                    connection.commit();
                    return;
                }
                AgentTaskRecord task = loadTask(connection, submission.taskId(), false)
                        .orElseThrow(() -> new IllegalArgumentException("task not found: " + submission.taskId()));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("rejectedIdempotencyKey", submission.idempotencyKey());
                payload.put("submissionHash", submissionHash);
                payload.put("reason", reason);
                appendEvent(connection, new TaskEventRecord(
                        UUID.randomUUID().toString(),
                        submission.incidentId(),
                        submission.taskId(),
                        submission.childRunId(),
                        allocateEventSequence(connection, submission.incidentId()),
                        TaskEventType.IDEMPOTENCY_REJECTED,
                        TaskEventCategory.CONTROL,
                        TaskEventActorType.SYSTEM,
                        "incident-task-result-committer",
                        null,
                        null,
                        0,
                        task.taskId(),
                        null,
                        auditIdempotencyKey,
                        payload,
                        Instant.now()));
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to record incident idempotency rejection", exception);
        }
    }

    private TaskResultCommitResult duplicateCommitResult(Connection connection,
                                                         AgentTaskRecord current,
                                                         TaskEventRecord event,
                                                         String submissionHash) throws SQLException {
        String storedHash = String.valueOf(event.payload().getOrDefault("submissionHash", ""));
        if (!submissionHash.equals(storedHash)) {
            throw new IncidentIdempotencyConflictException(
                    "task result idempotency key was reused with a different payload: "
                            + event.idempotencyKey());
        }
        List<String> evidenceIds = stringList(event.payload().get("evidenceIds"));
        List<EvidenceRecord> evidence = loadEvidenceByIds(connection, evidenceIds);
        AgentTaskRecord task = loadTask(connection, current.taskId(), false).orElseThrow();
        return new TaskResultCommitResult(task, evidence, event, true);
    }

    private EvidenceRecord toEvidenceRecord(TaskResultSubmission submission,
                                            EvidenceCandidate candidate,
                                            Instant createdAt) {
        Map<String, Object> hashPayload = new LinkedHashMap<>();
        hashPayload.put("evidenceClass", candidate.evidenceClass().name());
        hashPayload.put("evidenceSubtype", candidate.evidenceSubtype().name());
        hashPayload.put("sourceSystem", candidate.sourceSystem());
        hashPayload.put("sourceReference", candidate.sourceReference());
        hashPayload.put("queryParameters", candidate.queryParameters());
        hashPayload.put("observedAt", candidate.observedAt().toString());
        hashPayload.put("facts", candidate.facts());
        hashPayload.put("status", candidate.status().name());
        hashPayload.put("supersedesEvidenceId", candidate.supersedesEvidenceId());
        return new EvidenceRecord(
                UUID.randomUUID().toString(),
                submission.incidentId(),
                submission.taskId(),
                submission.childRunId(),
                candidate.evidenceClass(),
                candidate.evidenceSubtype(),
                candidate.sourceSystem().trim(),
                candidate.sourceReference().trim(),
                candidate.queryParameters(),
                candidate.observedAt(),
                candidate.facts(),
                sha256(canonicalJson(hashPayload)),
                candidate.status(),
                blankToNull(candidate.supersedesEvidenceId()),
                candidate.idempotencyKey().trim(),
                createdAt);
    }

    private void insertEvidence(Connection connection, EvidenceRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_evidence(
                    evidence_id, incident_id, task_id, child_run_id, evidence_class,
                    evidence_subtype, source_system, source_reference,
                    query_parameters_json, observed_at, facts_json, payload_hash,
                    status, supersedes_evidence_id, idempotency_key, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, record.evidenceId());
            statement.setString(2, record.incidentId());
            statement.setString(3, record.taskId());
            statement.setString(4, record.childRunId());
            statement.setString(5, record.evidenceClass().name());
            statement.setString(6, record.evidenceSubtype().name());
            statement.setString(7, record.sourceSystem());
            statement.setString(8, record.sourceReference());
            statement.setString(9, toJson(record.queryParameters()));
            statement.setTimestamp(10, Timestamp.from(record.observedAt()));
            statement.setString(11, toJson(record.facts()));
            statement.setString(12, record.payloadHash());
            statement.setString(13, record.status().name());
            statement.setString(14, record.supersedesEvidenceId());
            statement.setString(15, record.idempotencyKey());
            statement.setTimestamp(16, Timestamp.from(record.createdAt()));
            statement.executeUpdate();
        }
    }

    private void insertTask(Connection connection, AgentTaskRecord task) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_task(
                    task_id, incident_id, client_task_key, task_type, role, objective,
                    priority, dependencies_json, required_evidence_json, input_payload_json,
                    output_summary_json, status, attempt, max_attempts, child_run_id,
                    first_child_run_id, deadline_at, claimed_by, claim_until, fencing_token,
                    last_heartbeat_at, last_error,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                          ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, task.taskId());
            statement.setString(2, task.incidentId());
            statement.setString(3, task.clientTaskKey());
            statement.setString(4, task.taskType());
            statement.setString(5, task.role());
            statement.setString(6, task.objective());
            statement.setInt(7, task.priority());
            statement.setString(8, toJson(task.dependencies()));
            statement.setString(9, toJson(task.requiredEvidenceSubtypes()));
            statement.setString(10, toJson(task.inputPayload()));
            statement.setString(11, task.outputSummary().isEmpty() ? null : toJson(task.outputSummary()));
            statement.setString(12, task.status().name());
            statement.setInt(13, task.attempt());
            statement.setInt(14, task.maxAttempts());
            statement.setString(15, blankToNull(task.childRunId()));
            statement.setString(16, blankToNull(task.firstChildRunId()));
            statement.setTimestamp(17, Timestamp.from(task.deadlineAt()));
            statement.setString(18, blankToNull(task.claimedBy()));
            setTimestamp(statement, 19, task.claimUntil());
            statement.setLong(20, task.fencingToken());
            setTimestamp(statement, 21, task.lastHeartbeatAt());
            statement.setString(22, blankToNull(task.lastError()));
            statement.setLong(23, task.version());
            statement.setTimestamp(24, Timestamp.from(task.createdAt()));
            statement.setTimestamp(25, Timestamp.from(task.updatedAt()));
            statement.executeUpdate();
        }
    }

    private void appendEvent(Connection connection, TaskEventRecord event) throws SQLException {
        validateEvent(event);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_task_event(
                    event_id, incident_id, task_id, child_run_id, event_sequence,
                    event_type, event_category, actor_type, actor_id, sender_role,
                    recipient_role, message_depth, correlation_id, causation_id,
                    idempotency_key, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """)) {
            statement.setString(1, event.eventId());
            statement.setString(2, event.incidentId());
            statement.setString(3, blankToNull(event.taskId()));
            statement.setString(4, blankToNull(event.childRunId()));
            statement.setLong(5, event.eventSequence());
            statement.setString(6, event.eventType().name());
            statement.setString(7, event.eventCategory().name());
            statement.setString(8, event.actorType().name());
            statement.setString(9, event.actorId());
            statement.setString(10, blankToNull(event.senderRole()));
            statement.setString(11, blankToNull(event.recipientRole()));
            statement.setInt(12, event.messageDepth());
            statement.setString(13, blankToNull(event.correlationId()));
            statement.setString(14, blankToNull(event.causationId()));
            statement.setString(15, event.idempotencyKey());
            statement.setString(16, toJson(event.payload()));
            statement.setTimestamp(17, Timestamp.from(event.createdAt()));
            statement.executeUpdate();
        }
    }

    private long allocateEventSequence(Connection connection, String incidentId) throws SQLException {
        long sequence;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT next_event_sequence
                FROM agent_incident
                WHERE incident_id = ?
                FOR UPDATE
                """)) {
            statement.setString(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("incident not found: " + incidentId);
                }
                sequence = resultSet.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_incident
                SET next_event_sequence = next_event_sequence + 1, updated_at = ?
                WHERE incident_id = ?
                """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, incidentId);
            if (statement.executeUpdate() != 1) {
                throw new IncidentCasConflictException("event sequence allocation failed: " + incidentId);
            }
        }
        return sequence;
    }

    /**
     * 所有同时修改 Incident 与 Task/Evidence/Event 的事务都先锁协调根，再锁 Task。
     * 这条固定锁顺序避免并行 Specialist 在 FK/Task 锁与 eventSequence 行锁之间形成环路。
     */
    private void lockIncident(Connection connection, String incidentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT incident_id
                FROM agent_incident
                WHERE incident_id = ?
                FOR UPDATE
                """)) {
            statement.setString(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("incident not found: " + incidentId);
                }
            }
        }
    }

    private Optional<TaskEventRecord> findEventByIdempotencyKey(Connection connection,
                                                                 String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_task_event
                WHERE idempotency_key = ?
                FOR UPDATE
                """)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readEvent(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<IncidentRecord> loadIncident(Connection connection,
                                                   String incidentId,
                                                   boolean forUpdate) throws SQLException {
        String sql = "SELECT * FROM agent_incident WHERE incident_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readIncident(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<AgentTaskRecord> loadTask(Connection connection,
                                               String taskId,
                                               boolean forUpdate) throws SQLException {
        String sql = "SELECT * FROM agent_task WHERE task_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readTask(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<AgentTaskRecord> loadTaskByClientKey(Connection connection,
                                                          String incidentId,
                                                          String clientTaskKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_task WHERE incident_id = ? AND client_task_key = ?
                """)) {
            statement.setString(1, incidentId);
            statement.setString(2, clientTaskKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readTask(resultSet)) : Optional.empty();
            }
        }
    }

    private void assertSameTaskIdentity(AgentTaskRecord existing, AgentTaskRecord requested) {
        boolean sameScope = java.util.Objects.equals(
                existing.inputPayload().get("snapshotId"), requested.inputPayload().get("snapshotId"))
                && java.util.Objects.equals(
                existing.inputPayload().get("scopeHash"), requested.inputPayload().get("scopeHash"));
        if (!existing.role().equals(requested.role())
                || !existing.taskType().equals(requested.taskType())
                || !sameScope) {
            throw new IncidentIdempotencyConflictException(
                    "clientTaskKey is already bound to another role, task type or incident scope");
        }
    }

    private List<AgentTaskRecord> loadTasks(Connection connection, String incidentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_task
                WHERE incident_id = ?
                ORDER BY priority DESC, created_at, task_id
                """)) {
            statement.setString(1, incidentId);
            List<AgentTaskRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(readTask(resultSet));
                }
            }
            return List.copyOf(records);
        }
    }

    private List<EvidenceRecord> loadEvidence(Connection connection, String incidentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_evidence
                WHERE incident_id = ?
                ORDER BY created_at, evidence_id
                """)) {
            statement.setString(1, incidentId);
            List<EvidenceRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(readEvidence(resultSet));
                }
            }
            return List.copyOf(records);
        }
    }

    private List<TaskEventRecord> loadEvents(Connection connection,
                                             String incidentId,
                                             long afterSequence,
                                             int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_task_event
                WHERE incident_id = ? AND event_sequence > ?
                ORDER BY event_sequence
                LIMIT ?
                """)) {
            statement.setString(1, incidentId);
            statement.setLong(2, afterSequence);
            statement.setInt(3, Math.max(1, Math.min(limit, MAX_EVENT_LIMIT)));
            List<TaskEventRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(readEvent(resultSet));
                }
            }
            return List.copyOf(records);
        }
    }

    private List<EvidenceRecord> loadEvidenceByIds(Connection connection,
                                                   List<String> evidenceIds) throws SQLException {
        if (evidenceIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(evidenceIds.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_evidence WHERE evidence_id IN (" + placeholders + ") ORDER BY created_at, evidence_id")) {
            for (int index = 0; index < evidenceIds.size(); index++) {
                statement.setString(index + 1, evidenceIds.get(index));
            }
            List<EvidenceRecord> result = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(readEvidence(resultSet));
                }
            }
            return List.copyOf(result);
        }
    }

    private IncidentRecord readIncident(ResultSet resultSet) throws SQLException {
        return new IncidentRecord(
                resultSet.getString("incident_id"),
                resultSet.getString("commander_run_id"),
                resultSet.getString("reviewer_run_id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("scenario_id"),
                IncidentStatus.valueOf(resultSet.getString("status")),
                fromJson(resultSet.getString("snapshot_json"), IncidentSnapshot.class),
                mapFromJson(resultSet.getString("delegation_plan_json")),
                mapFromJson(resultSet.getString("assessment_json")),
                resultSet.getInt("clarification_count"),
                resultSet.getInt("max_clarifications"),
                resultSet.getLong("next_event_sequence"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private AgentTaskRecord readTask(ResultSet resultSet) throws SQLException {
        return new AgentTaskRecord(
                resultSet.getString("task_id"),
                resultSet.getString("incident_id"),
                resultSet.getString("client_task_key"),
                resultSet.getString("task_type"),
                resultSet.getString("role"),
                resultSet.getString("objective"),
                resultSet.getInt("priority"),
                stringListFromJson(resultSet.getString("dependencies_json")),
                evidenceSubtypeListFromJson(resultSet.getString("required_evidence_json")),
                mapFromJson(resultSet.getString("input_payload_json")),
                mapFromJson(resultSet.getString("output_summary_json")),
                AgentTaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt"),
                resultSet.getInt("max_attempts"),
                resultSet.getString("child_run_id"),
                resultSet.getString("first_child_run_id"),
                instant(resultSet, "deadline_at"),
                resultSet.getString("claimed_by"),
                nullableInstant(resultSet, "claim_until"),
                resultSet.getLong("fencing_token"),
                nullableInstant(resultSet, "last_heartbeat_at"),
                resultSet.getString("last_error"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private EvidenceRecord readEvidence(ResultSet resultSet) throws SQLException {
        return new EvidenceRecord(
                resultSet.getString("evidence_id"),
                resultSet.getString("incident_id"),
                resultSet.getString("task_id"),
                resultSet.getString("child_run_id"),
                EvidenceClass.valueOf(resultSet.getString("evidence_class")),
                EvidenceSubtype.valueOf(resultSet.getString("evidence_subtype")),
                resultSet.getString("source_system"),
                resultSet.getString("source_reference"),
                mapFromJson(resultSet.getString("query_parameters_json")),
                instant(resultSet, "observed_at"),
                mapFromJson(resultSet.getString("facts_json")),
                resultSet.getString("payload_hash"),
                EvidenceStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("supersedes_evidence_id"),
                resultSet.getString("idempotency_key"),
                instant(resultSet, "created_at"));
    }

    private TaskEventRecord readEvent(ResultSet resultSet) throws SQLException {
        return new TaskEventRecord(
                resultSet.getString("event_id"),
                resultSet.getString("incident_id"),
                resultSet.getString("task_id"),
                resultSet.getString("child_run_id"),
                resultSet.getLong("event_sequence"),
                TaskEventType.valueOf(resultSet.getString("event_type")),
                TaskEventCategory.valueOf(resultSet.getString("event_category")),
                TaskEventActorType.valueOf(resultSet.getString("actor_type")),
                resultSet.getString("actor_id"),
                resultSet.getString("sender_role"),
                resultSet.getString("recipient_role"),
                resultSet.getInt("message_depth"),
                resultSet.getString("correlation_id"),
                resultSet.getString("causation_id"),
                resultSet.getString("idempotency_key"),
                mapFromJson(resultSet.getString("payload_json")),
                instant(resultSet, "created_at"));
    }

    private void bindIncident(PreparedStatement statement, IncidentRecord incident) throws SQLException {
        statement.setString(1, incident.incidentId());
        statement.setString(2, blankToNull(incident.commanderRunId()));
        statement.setString(3, blankToNull(incident.reviewerRunId()));
        statement.setString(4, incident.conversationId());
        statement.setString(5, incident.scenarioId());
        statement.setString(6, incident.status().name());
        statement.setString(7, toJson(incident.snapshot()));
        statement.setString(8, toJson(incident.delegationPlan()));
        statement.setString(9, toJson(incident.assessment()));
        statement.setInt(10, incident.clarificationCount());
        statement.setInt(11, incident.maxClarifications());
        statement.setLong(12, incident.nextEventSequence());
        statement.setLong(13, incident.version());
        statement.setTimestamp(14, Timestamp.from(incident.createdAt()));
        statement.setTimestamp(15, Timestamp.from(incident.updatedAt()));
    }

    private void validateIncident(IncidentRecord incident) {
        Objects.requireNonNull(incident, "incident");
        requireText(incident.incidentId(), "incidentId");
        requireText(incident.conversationId(), "conversationId");
        requireText(incident.scenarioId(), "scenarioId");
        Objects.requireNonNull(incident.status(), "status");
        Objects.requireNonNull(incident.snapshot(), "snapshot");
        if (!incident.incidentId().equals(incident.snapshot().incidentId())) {
            throw new IllegalArgumentException("snapshot incidentId mismatch");
        }
        requireText(incident.snapshot().snapshotId(), "snapshotId");
        requireText(incident.snapshot().scopeHash(), "scopeHash");
        if (incident.nextEventSequence() < 1 || incident.version() < 0) {
            throw new IllegalArgumentException("incident sequence/version must be non-negative");
        }
        if (incident.maxClarifications() < 0 || incident.clarificationCount() > incident.maxClarifications()) {
            throw new IllegalArgumentException("invalid clarification budget");
        }
        Objects.requireNonNull(incident.createdAt(), "createdAt");
        Objects.requireNonNull(incident.updatedAt(), "updatedAt");
    }

    private void validateTask(AgentTaskRecord task) {
        Objects.requireNonNull(task, "task");
        requireText(task.taskId(), "taskId");
        requireText(task.incidentId(), "incidentId");
        requireText(task.clientTaskKey(), "clientTaskKey");
        requireText(task.taskType(), "taskType");
        requireText(task.role(), "role");
        requireText(task.objective(), "objective");
        Objects.requireNonNull(task.status(), "status");
        Objects.requireNonNull(task.deadlineAt(), "deadlineAt");
        Objects.requireNonNull(task.createdAt(), "createdAt");
        Objects.requireNonNull(task.updatedAt(), "updatedAt");
        if (task.maxAttempts() < 1 || task.maxAttempts() > 2 || task.attempt() < 0) {
            throw new IllegalArgumentException("Phase 1 task maxAttempts must be 1 or 2");
        }
        if (task.requiredEvidenceSubtypes().isEmpty()) {
            throw new IllegalArgumentException("requiredEvidenceSubtypes must not be empty");
        }
    }

    private void validateSubmission(TaskResultSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        requireText(submission.incidentId(), "incidentId");
        requireText(submission.taskId(), "taskId");
        requireText(submission.childRunId(), "childRunId");
        requireText(submission.idempotencyKey(), "idempotencyKey");
        Objects.requireNonNull(submission.targetStatus(), "targetStatus");
        if (submission.evidence().isEmpty()) {
            throw new IllegalArgumentException("task result must contain evidence");
        }
    }

    private void validateSubmissionAgainstTask(TaskResultSubmission submission, AgentTaskRecord task) {
        if (!submission.incidentId().equals(task.incidentId())) {
            throw new IllegalArgumentException("task does not belong to incident");
        }
        if (task.version() != submission.expectedVersion()) {
            throw new IncidentCasConflictException(
                    "task version mismatch: expected=" + submission.expectedVersion() + ", actual=" + task.version());
        }
        if (!Objects.equals(task.childRunId(), submission.childRunId())) {
            throw new IllegalArgumentException("childRunId does not own task result");
        }
        if (task.status() != AgentTaskStatus.RUNNING
                && task.status() != AgentTaskStatus.WAITING_CLARIFICATION) {
            throw new IllegalStateException("task cannot accept result in status: " + task.status());
        }
        if (!canTransition(task.status(), submission.targetStatus())) {
            throw new IllegalStateException(
                    "invalid task result transition: " + task.status() + " -> " + submission.targetStatus());
        }
    }

    private void validateCandidate(EvidenceCandidate candidate) {
        Objects.requireNonNull(candidate, "evidence candidate");
        Objects.requireNonNull(candidate.evidenceClass(), "evidenceClass");
        Objects.requireNonNull(candidate.evidenceSubtype(), "evidenceSubtype");
        requireText(candidate.sourceSystem(), "sourceSystem");
        requireText(candidate.sourceReference(), "sourceReference");
        Objects.requireNonNull(candidate.observedAt(), "observedAt");
        requireText(candidate.idempotencyKey(), "evidence idempotencyKey");
        if (candidate.facts().isEmpty()) {
            throw new IllegalArgumentException("evidence facts must not be empty");
        }
    }

    private void validateEvent(TaskEventRecord event) {
        requireText(event.eventId(), "eventId");
        requireText(event.incidentId(), "incidentId");
        requireText(event.actorId(), "actorId");
        requireText(event.idempotencyKey(), "event idempotencyKey");
        Objects.requireNonNull(event.eventType(), "eventType");
        Objects.requireNonNull(event.eventCategory(), "eventCategory");
        Objects.requireNonNull(event.actorType(), "actorType");
        if (event.eventCategory() == TaskEventCategory.COMMUNICATION) {
            requireText(event.senderRole(), "senderRole");
            requireText(event.recipientRole(), "recipientRole");
            if (event.messageDepth() < 1 || event.messageDepth() > 2) {
                throw new IllegalArgumentException("communication messageDepth must be 1 or 2");
            }
        } else if (event.messageDepth() != 0) {
            throw new IllegalArgumentException("internal event messageDepth must be 0");
        }
    }

    private boolean canTransition(IncidentStatus source, IncidentStatus target) {
        if (source == target) {
            return false;
        }
        if (target == IncidentStatus.FAILED || target == IncidentStatus.CANCELLED) {
            return !source.terminal();
        }
        return switch (source) {
            case CREATED -> target == IncidentStatus.PLANNING;
            case PLANNING -> target == IncidentStatus.INVESTIGATING;
            case INVESTIGATING -> target == IncidentStatus.CHECKING_CONSISTENCY;
            case CHECKING_CONSISTENCY -> target == IncidentStatus.REVIEWING;
            case REVIEWING -> target == IncidentStatus.CLARIFYING
                    || target == IncidentStatus.ASSESSED
                    || target == IncidentStatus.PARTIAL
                    || target == IncidentStatus.MANUAL_REVIEW;
            case CLARIFYING -> target == IncidentStatus.REVIEWING;
            default -> false;
        };
    }

    private boolean canTransition(AgentTaskStatus source, AgentTaskStatus target) {
        if (source == target) {
            return false;
        }
        return switch (source) {
            case PENDING -> target == AgentTaskStatus.CLAIMED || target == AgentTaskStatus.CANCELLED;
            case CLAIMED -> target == AgentTaskStatus.RUNNING
                    || target == AgentTaskStatus.RETRY_PENDING
                    || target == AgentTaskStatus.FAILED
                    || target == AgentTaskStatus.CANCELLED;
            case RUNNING -> target == AgentTaskStatus.WAITING_CLARIFICATION
                    || target == AgentTaskStatus.RETRY_PENDING
                    || target == AgentTaskStatus.SUCCEEDED
                    || target == AgentTaskStatus.FAILED
                    || target == AgentTaskStatus.TIMED_OUT
                    || target == AgentTaskStatus.CANCELLED;
            case WAITING_CLARIFICATION -> target == AgentTaskStatus.RUNNING
                    || target == AgentTaskStatus.SUCCEEDED
                    || target == AgentTaskStatus.CANCELLED;
            case RETRY_PENDING -> target == AgentTaskStatus.CLAIMED || target == AgentTaskStatus.CANCELLED;
            default -> false;
        };
    }

    private void assertSameTransition(TaskEventRecord previous, String targetStatus) {
        String previousTarget = String.valueOf(previous.payload().getOrDefault("targetStatus", ""));
        if (!targetStatus.equals(previousTarget)) {
            throw new IncidentIdempotencyConflictException(
                    "transition idempotency key reused for different target status: " + previous.idempotencyKey());
        }
    }

    private Map<String, Object> transitionPayload(String source,
                                                  String target,
                                                  long expectedVersion,
                                                  long resultingVersion) {
        return Map.of(
                "sourceStatus", source,
                "targetStatus", target,
                "expectedVersion", expectedVersion,
                "resultingVersion", resultingVersion);
    }

    private String submissionHash(TaskResultSubmission submission) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", submission.incidentId());
        payload.put("taskId", submission.taskId());
        payload.put("childRunId", submission.childRunId());
        payload.put("expectedVersion", submission.expectedVersion());
        payload.put("targetStatus", submission.targetStatus().name());
        payload.put("outputSummary", submission.outputSummary());
        List<Map<String, Object>> evidence = submission.evidence().stream()
                .map(candidate -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("evidenceClass", candidate.evidenceClass().name());
                    item.put("evidenceSubtype", candidate.evidenceSubtype().name());
                    item.put("sourceSystem", candidate.sourceSystem());
                    item.put("sourceReference", candidate.sourceReference());
                    item.put("queryParameters", candidate.queryParameters());
                    item.put("observedAt", candidate.observedAt().toString());
                    item.put("facts", candidate.facts());
                    item.put("status", candidate.status().name());
                    item.put("supersedesEvidenceId", candidate.supersedesEvidenceId());
                    item.put("idempotencyKey", candidate.idempotencyKey());
                    return item;
                })
                .toList();
        payload.put("evidence", evidence);
        return sha256(canonicalJson(payload));
    }

    private String canonicalJson(Object value) {
        return toJson(canonicalize(value));
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::canonicalize).toList();
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void ensureSchema() {
        if (schemaReady.get()) {
            return;
        }
        synchronized (schemaReady) {
            if (schemaReady.get()) {
                return;
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS agent_incident (
                            incident_id TEXT PRIMARY KEY,
                            commander_run_id TEXT UNIQUE,
                            reviewer_run_id TEXT UNIQUE,
                            conversation_id TEXT NOT NULL,
                            scenario_id TEXT NOT NULL,
                            status TEXT NOT NULL,
                            snapshot_json JSONB NOT NULL,
                            delegation_plan_json JSONB,
                            assessment_json JSONB,
                            clarification_count INT NOT NULL DEFAULT 0,
                            max_clarifications INT NOT NULL DEFAULT 1,
                            next_event_sequence BIGINT NOT NULL DEFAULT 1,
                            version BIGINT NOT NULL DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_incident_snapshot
                        ON agent_incident ((snapshot_json ->> 'snapshotId'))
                        """);
                statement.executeUpdate("ALTER TABLE agent_incident ADD COLUMN IF NOT EXISTS dispatch_request_id TEXT");
                statement.executeUpdate("""
                        CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_incident_dispatch_request
                        ON agent_incident(dispatch_request_id) WHERE dispatch_request_id IS NOT NULL
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_agent_incident_status_updated
                        ON agent_incident(status, updated_at DESC)
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS agent_task (
                            task_id TEXT PRIMARY KEY,
                            incident_id TEXT NOT NULL REFERENCES agent_incident(incident_id),
                            client_task_key TEXT NOT NULL,
                            task_type TEXT NOT NULL,
                            role TEXT NOT NULL,
                            objective TEXT NOT NULL,
                            priority INT NOT NULL,
                            dependencies_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                            required_evidence_json JSONB NOT NULL,
                            input_payload_json JSONB NOT NULL,
                            output_summary_json JSONB,
                            status TEXT NOT NULL,
                            attempt INT NOT NULL DEFAULT 0,
                            max_attempts INT NOT NULL DEFAULT 2,
                            child_run_id TEXT,
                            first_child_run_id TEXT,
                            deadline_at TIMESTAMPTZ NOT NULL,
                            claimed_by TEXT,
                            claim_until TIMESTAMPTZ,
                            fencing_token BIGINT NOT NULL DEFAULT 0,
                            last_heartbeat_at TIMESTAMPTZ,
                            last_error TEXT,
                            version BIGINT NOT NULL DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL,
                            UNIQUE(incident_id, client_task_key)
                        )
                        """);
                statement.executeUpdate("ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0");
                statement.executeUpdate("ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ");
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_agent_task_incident_status
                        ON agent_task(incident_id, status, priority DESC)
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_agent_task_stale_lease
                        ON agent_task(status, claim_until)
                        WHERE status IN ('CLAIMED','RUNNING')
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS agent_evidence (
                            evidence_id TEXT PRIMARY KEY,
                            incident_id TEXT NOT NULL REFERENCES agent_incident(incident_id),
                            task_id TEXT NOT NULL REFERENCES agent_task(task_id),
                            child_run_id TEXT NOT NULL,
                            evidence_class TEXT NOT NULL,
                            evidence_subtype TEXT NOT NULL,
                            source_system TEXT NOT NULL,
                            source_reference TEXT NOT NULL,
                            query_parameters_json JSONB NOT NULL,
                            observed_at TIMESTAMPTZ NOT NULL,
                            facts_json JSONB NOT NULL,
                            payload_hash CHAR(64) NOT NULL,
                            status TEXT NOT NULL,
                            supersedes_evidence_id TEXT,
                            idempotency_key TEXT NOT NULL UNIQUE,
                            created_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_agent_evidence_incident_subtype
                        ON agent_evidence(incident_id, evidence_subtype, created_at)
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS agent_task_event (
                            event_id TEXT PRIMARY KEY,
                            incident_id TEXT NOT NULL REFERENCES agent_incident(incident_id),
                            task_id TEXT REFERENCES agent_task(task_id),
                            child_run_id TEXT,
                            event_sequence BIGINT NOT NULL,
                            event_type TEXT NOT NULL,
                            event_category TEXT NOT NULL,
                            actor_type TEXT NOT NULL,
                            actor_id TEXT NOT NULL,
                            sender_role TEXT,
                            recipient_role TEXT,
                            message_depth INT NOT NULL DEFAULT 0,
                            correlation_id TEXT,
                            causation_id TEXT,
                            idempotency_key TEXT NOT NULL UNIQUE,
                            payload_json JSONB NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL,
                            UNIQUE(incident_id, event_sequence)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_agent_task_event_incident_sequence
                        ON agent_task_event(incident_id, event_sequence)
                        """);
                schemaReady.set(true);
            } catch (SQLException exception) {
                throw storageFailure("Failed to initialize incident command schema", exception);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (RuntimeException exception) {
            throw new AgentStorageException("Failed to serialize incident record", exception);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException exception) {
            throw new AgentStorageException("Failed to deserialize incident record", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapFromJson(String json) {
        if (!hasText(json)) {
            return Map.of();
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(
                objectMapper.readValue(json, Map.class)));
    }

    private List<String> stringListFromJson(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        return List.of(objectMapper.readValue(json, String[].class));
    }

    private List<EvidenceSubtype> evidenceSubtypeListFromJson(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        return List.of(objectMapper.readValue(json, EvidenceSubtype[].class));
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        return List.of(objectMapper.convertValue(value, String[].class));
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        if (value == null) {
            throw new AgentStorageException("Required timestamp is null: " + column, null);
        }
        return value.toInstant();
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private void setTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setTimestamp(index, value == null ? null : Timestamp.from(value));
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }

    private AgentStorageException storageFailure(String message, SQLException exception) {
        return new AgentStorageException(message, exception);
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
