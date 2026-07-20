package com.agent.platform.ordercare.incident.recovery.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException;
import com.agent.platform.ordercare.incident.persistence.IncidentIdempotencyConflictException;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanItem;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryItemLeaseClaim;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanEventRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanItemStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.storage.AgentStorageException;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class JdbcIncidentRecoveryPlanStore implements IncidentRecoveryPlanStore {

    private final AgentStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcIncidentRecoveryPlanStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public IncidentRecoveryPlanRecord create(IncidentRecoveryPlanRecord plan) {
        validate(plan);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<IncidentRecoveryPlanRecord> existing = findByRequestKey(
                        connection, plan.incidentId(), plan.requestKey());
                if (existing.isPresent()) {
                    IncidentRecoveryPlanRecord current = existing.get();
                    if (!current.planId().equals(plan.planId())
                            || !current.assessmentDigest().equals(plan.assessmentDigest())) {
                        throw new IncidentIdempotencyConflictException(
                                "recovery plan requestKey already bound to another request");
                    }
                    connection.commit();
                    return current;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO agent_incident_recovery_plan(
                            plan_id, incident_id, request_key, planner_run_id, assessment_digest,
                            status, outcome, record_json, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        """)) {
                    bind(statement, plan);
                    statement.executeUpdate();
                }
                appendSnapshotEvent(connection, plan, "RECOVERY_PLAN_CREATED");
                connection.commit();
                return plan;
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                Optional<IncidentRecoveryPlanRecord> winner = findByRequestKey(
                        plan.incidentId(), plan.requestKey());
                if (winner.isPresent()) {
                    IncidentRecoveryPlanRecord current = winner.get();
                    if (current.planId().equals(plan.planId())
                            && current.assessmentDigest().equals(plan.assessmentDigest())) {
                        return current;
                    }
                    throw new IncidentIdempotencyConflictException(
                            "recovery plan requestKey was concurrently bound to another request");
                }
            }
            throw storageFailure("Failed to create incident recovery plan: " + plan.planId(), exception);
        }
    }

    @Override
    public Optional<IncidentRecoveryPlanRecord> find(String planId) {
        if (!hasText(planId)) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT record_json FROM agent_incident_recovery_plan WHERE plan_id = ?
                     """)) {
            statement.setString(1, planId.trim());
            return readOne(statement);
        } catch (SQLException exception) {
            throw storageFailure("Failed to read incident recovery plan: " + planId, exception);
        }
    }

    @Override
    public Optional<IncidentRecoveryPlanRecord> findByRequestKey(String incidentId, String requestKey) {
        if (!hasText(incidentId) || !hasText(requestKey)) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            return findByRequestKey(connection, incidentId.trim(), requestKey.trim());
        } catch (SQLException exception) {
            throw storageFailure("Failed to read recovery plan idempotency key", exception);
        }
    }

    @Override
    public List<IncidentRecoveryPlanRecord> listByIncident(String incidentId) {
        if (!hasText(incidentId)) {
            return List.of();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT record_json
                     FROM agent_incident_recovery_plan
                     WHERE incident_id = ?
                     ORDER BY created_at DESC
                     """)) {
            statement.setString(1, incidentId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<IncidentRecoveryPlanRecord> plans = new ArrayList<>();
                while (resultSet.next()) {
                    plans.add(fromJson(resultSet.getString("record_json")));
                }
                return List.copyOf(plans);
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to list incident recovery plans: " + incidentId, exception);
        }
    }

    @Override
    public List<RecoveryPlanEventRecord> loadEventsAfter(String planId, long afterSequence, int limit) {
        if (!hasText(planId) || limit < 1) return List.of();
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT event_id, plan_id, incident_id, event_sequence, event_type, payload, created_at
                     FROM agent_incident_recovery_plan_event
                     WHERE plan_id = ? AND event_sequence > ?
                     ORDER BY event_sequence ASC
                     LIMIT ?
                     """)) {
            statement.setString(1, planId.trim());
            statement.setLong(2, afterSequence);
            statement.setInt(3, Math.max(1, Math.min(limit, 10_000)));
            List<RecoveryPlanEventRecord> events = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(new RecoveryPlanEventRecord(
                            resultSet.getString("event_id"), resultSet.getString("plan_id"),
                            resultSet.getString("incident_id"), resultSet.getLong("event_sequence"),
                            resultSet.getString("event_type"), readMap(resultSet.getString("payload")),
                            resultSet.getTimestamp("created_at").toInstant()));
                }
            }
            return List.copyOf(events);
        } catch (SQLException exception) {
            throw storageFailure("Failed to load recovery plan events: " + planId, exception);
        }
    }

    @Override
    public IncidentRecoveryPlanRecord update(IncidentRecoveryPlanRecord next, long expectedVersion) {
        validate(next);
        if (next.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("next recovery plan version must equal expectedVersion + 1");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_incident_recovery_plan
                     SET planner_run_id = NULLIF(?, ''), status = ?, outcome = ?, record_json = ?::jsonb,
                         version = ?, updated_at = ?
                     WHERE plan_id = ? AND version = ? AND assessment_digest = ?
                       AND incident_id = ? AND request_key = ?
                     """)) {
                statement.setString(1, next.plannerRunId());
                statement.setString(2, next.status().name());
                statement.setString(3, next.outcome().name());
                statement.setString(4, toJson(next));
                statement.setLong(5, next.version());
                statement.setTimestamp(6, Timestamp.from(next.updatedAt()));
                statement.setString(7, next.planId());
                statement.setLong(8, expectedVersion);
                statement.setString(9, next.assessmentDigest());
                statement.setString(10, next.incidentId());
                statement.setString(11, next.requestKey());
                if (statement.executeUpdate() != 1) {
                    throw new IncidentCasConflictException(
                            "recovery plan CAS update failed: " + next.planId());
                }
            }
            appendSnapshotEvent(connection, next, "RECOVERY_PLAN_UPDATED");
            connection.commit();
            return next;
        } catch (SQLException exception) {
            throw storageFailure("Failed to update incident recovery plan: " + next.planId(), exception);
        }
    }

    @Override
    public RecoveryItemLeaseClaim claimItem(String planId,
                                            String itemId,
                                            String owner,
                                            Instant leaseUntil,
                                            boolean allowExpiredTakeover) {
        requireText(planId, "planId");
        requireText(itemId, "itemId");
        requireText(owner, "owner");
        if (leaseUntil == null || !leaseUntil.isAfter(Instant.now())) {
            throw new IllegalArgumentException("leaseUntil must be in the future");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                IncidentRecoveryPlanRecord current = loadForUpdate(connection, planId)
                        .orElseThrow(() -> new IllegalArgumentException("recovery plan not found: " + planId));
                IncidentRecoveryPlanItem item = item(current, itemId);
                Instant now = Instant.now();
                boolean takeover = item.status() == RecoveryPlanItemStatus.EXECUTING
                        && (item.leaseUntil() == null || !item.leaseUntil().isAfter(now));
                boolean initial = item.status() == RecoveryPlanItemStatus.WAITING_APPROVAL;
                if (!initial && !(allowExpiredTakeover && takeover)) {
                    connection.commit();
                    return new RecoveryItemLeaseClaim(current, item, false, false);
                }
                IncidentRecoveryPlanItem claimed = copyLease(
                        item, RecoveryPlanItemStatus.EXECUTING, owner, item.fencingToken() + 1,
                        leaseUntil, now, takeover ? item.takeoverCount() + 1 : item.takeoverCount());
                IncidentRecoveryPlanRecord next = replace(current, claimed,
                        RecoveryPlanStatus.EXECUTING, RecoveryPlanOutcome.READY);
                writeLocked(connection, next, current.version());
                connection.commit();
                return new RecoveryItemLeaseClaim(next, claimed, true, takeover);
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to claim recovery plan item: " + itemId, exception);
        }
    }

    @Override
    public IncidentRecoveryPlanRecord renewItemLease(String planId,
                                                     String itemId,
                                                     String owner,
                                                     long fencingToken,
                                                     Instant leaseUntil) {
        return mutateFenced(planId, itemId, owner, fencingToken, item -> {
            if (item.status() != RecoveryPlanItemStatus.EXECUTING) {
                throw new IncidentCasConflictException("recovery item is no longer executing: " + itemId);
            }
            Instant now = Instant.now();
            if (leaseUntil == null || !leaseUntil.isAfter(now)) {
                throw new IllegalArgumentException("leaseUntil must be in the future");
            }
            return copyLease(item, item.status(), owner, fencingToken, leaseUntil, now, item.takeoverCount());
        });
    }

    @Override
    public IncidentRecoveryPlanRecord updateItemFenced(String planId,
                                                       IncidentRecoveryPlanItem replacement,
                                                       String owner,
                                                       long fencingToken) {
        if (replacement == null) {
            throw new IllegalArgumentException("replacement is required");
        }
        return mutateFenced(planId, replacement.itemId(), owner, fencingToken, ignored -> replacement);
    }

    @Override
    public List<IncidentRecoveryPlanRecord> listStaleExecuting(Instant now, int limit) {
        if (now == null || limit < 1) {
            return List.of();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT record_json FROM agent_incident_recovery_plan
                     WHERE status = 'EXECUTING'
                     ORDER BY updated_at ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, Math.min(limit, 100));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<IncidentRecoveryPlanRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    IncidentRecoveryPlanRecord plan = fromJson(resultSet.getString("record_json"));
                    if (plan.items().stream().anyMatch(item -> item.status() == RecoveryPlanItemStatus.EXECUTING
                            && (item.leaseUntil() == null || !item.leaseUntil().isAfter(now)))) {
                        result.add(plan);
                    }
                }
                return List.copyOf(result);
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to list stale recovery executions", exception);
        }
    }

    private IncidentRecoveryPlanRecord mutateFenced(String planId,
                                                     String itemId,
                                                     String owner,
                                                     long fencingToken,
                                                     java.util.function.UnaryOperator<IncidentRecoveryPlanItem> mutation) {
        requireText(owner, "owner");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                IncidentRecoveryPlanRecord current = loadForUpdate(connection, planId)
                        .orElseThrow(() -> new IllegalArgumentException("recovery plan not found: " + planId));
                IncidentRecoveryPlanItem existing = item(current, itemId);
                if (!owner.equals(existing.executionOwner()) || fencingToken != existing.fencingToken()) {
                    throw new IncidentCasConflictException("stale recovery item fencing token: " + itemId);
                }
                IncidentRecoveryPlanRecord next = replace(current, mutation.apply(existing), null, null);
                writeLocked(connection, next, current.version());
                connection.commit();
                return next;
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed fenced recovery item update: " + itemId, exception);
        }
    }

    private Optional<IncidentRecoveryPlanRecord> loadForUpdate(Connection connection, String planId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT record_json FROM agent_incident_recovery_plan WHERE plan_id = ? FOR UPDATE
                """)) {
            statement.setString(1, planId);
            return readOne(statement);
        }
    }

    private void writeLocked(Connection connection,
                             IncidentRecoveryPlanRecord next,
                             long expectedVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_incident_recovery_plan
                SET status = ?, outcome = ?, record_json = ?::jsonb, version = ?, updated_at = ?
                WHERE plan_id = ? AND version = ?
                """)) {
            statement.setString(1, next.status().name());
            statement.setString(2, next.outcome().name());
            statement.setString(3, toJson(next));
            statement.setLong(4, next.version());
            statement.setTimestamp(5, Timestamp.from(next.updatedAt()));
            statement.setString(6, next.planId());
            statement.setLong(7, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new IncidentCasConflictException("recovery plan fenced update failed: " + next.planId());
            }
        }
        appendSnapshotEvent(connection, next, "RECOVERY_PLAN_UPDATED");
    }

    private void appendSnapshotEvent(Connection connection,
                                     IncidentRecoveryPlanRecord plan,
                                     String eventType) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", plan.planId());
        payload.put("incidentId", plan.incidentId());
        payload.put("status", plan.status().name());
        payload.put("outcome", plan.outcome().name());
        payload.put("plannerRunId", plan.plannerRunId());
        payload.put("version", plan.version());
        payload.put("record", plan);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_incident_recovery_plan_event(
                    event_id, plan_id, incident_id, event_sequence, event_type, payload, created_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """)) {
            statement.setString(1, "rpevt-" + UUID.randomUUID());
            statement.setString(2, plan.planId());
            statement.setString(3, plan.incidentId());
            statement.setLong(4, plan.version());
            statement.setString(5, eventType);
            statement.setString(6, objectMapper.writeValueAsString(payload));
            statement.setTimestamp(7, Timestamp.from(plan.updatedAt()));
            statement.executeUpdate();
        }
    }

    private IncidentRecoveryPlanRecord replace(IncidentRecoveryPlanRecord current,
                                               IncidentRecoveryPlanItem replacement,
                                               RecoveryPlanStatus forcedStatus,
                                               RecoveryPlanOutcome forcedOutcome) {
        List<IncidentRecoveryPlanItem> items = new ArrayList<>(current.items());
        boolean found = false;
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).itemId().equals(replacement.itemId())) {
                items.set(index, replacement);
                found = true;
                break;
            }
        }
        if (!found) throw new IllegalArgumentException("recovery plan item not found: " + replacement.itemId());
        RecoveryPlanStatus status = forcedStatus == null ? deriveStatus(items) : forcedStatus;
        RecoveryPlanOutcome outcome = forcedOutcome == null ? deriveOutcome(items) : forcedOutcome;
        return new IncidentRecoveryPlanRecord(
                current.planId(), current.incidentId(), current.requestKey(), current.plannerRunId(),
                current.assessmentDigest(), status, outcome, current.draft(), items,
                current.validationErrors(), current.version() + 1, current.createdAt(), Instant.now());
    }

    private RecoveryPlanStatus deriveStatus(List<IncidentRecoveryPlanItem> items) {
        if (items.stream().anyMatch(item -> item.status() == RecoveryPlanItemStatus.EXECUTING)) return RecoveryPlanStatus.EXECUTING;
        if (items.stream().anyMatch(item -> item.status() == RecoveryPlanItemStatus.WAITING_APPROVAL)) return RecoveryPlanStatus.WAITING_APPROVAL;
        return RecoveryPlanStatus.COMPLETED;
    }

    private RecoveryPlanOutcome deriveOutcome(List<IncidentRecoveryPlanItem> items) {
        if (items.stream().anyMatch(item -> item.status() == RecoveryPlanItemStatus.EXECUTING
                || item.status() == RecoveryPlanItemStatus.WAITING_APPROVAL)) return RecoveryPlanOutcome.READY;
        long resolved = items.stream().filter(item -> item.status() == RecoveryPlanItemStatus.RESOLVED).count();
        if (!items.isEmpty() && resolved == items.size()) return RecoveryPlanOutcome.RESOLVED;
        if (resolved > 0) return RecoveryPlanOutcome.PARTIAL;
        if (items.stream().anyMatch(item -> item.status() == RecoveryPlanItemStatus.MANUAL_REVIEW
                || item.status() == RecoveryPlanItemStatus.FAILED)) return RecoveryPlanOutcome.MANUAL_REVIEW;
        return RecoveryPlanOutcome.REJECTED;
    }

    private IncidentRecoveryPlanItem item(IncidentRecoveryPlanRecord plan, String itemId) {
        return plan.items().stream().filter(item -> item.itemId().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("recovery plan item not found: " + itemId));
    }

    private IncidentRecoveryPlanItem copyLease(IncidentRecoveryPlanItem item,
                                               RecoveryPlanItemStatus status,
                                               String owner,
                                               long token,
                                               Instant leaseUntil,
                                               Instant heartbeat,
                                               int takeoverCount) {
        return new IncidentRecoveryPlanItem(
                item.itemId(), item.clientItemKey(), item.identifierType(), item.identifierValue(), item.actionType(),
                item.suggestedReason(), item.evidenceIds(), item.conflictIds(), status, item.proposal(),
                item.approvalId(), item.approvalStatus(), item.actionStatus(), item.caseOutcome(), item.convergence(),
                item.lastError(), owner, token, leaseUntil, heartbeat, takeoverCount, Instant.now());
    }

    private void rollbackQuietly(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { }
    }

    private void requireText(String value, String field) {
        if (!hasText(value)) throw new IllegalArgumentException(field + " is required");
    }

    private Optional<IncidentRecoveryPlanRecord> findByRequestKey(Connection connection,
                                                                   String incidentId,
                                                                   String requestKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT record_json
                FROM agent_incident_recovery_plan
                WHERE incident_id = ? AND request_key = ?
                """)) {
            statement.setString(1, incidentId);
            statement.setString(2, requestKey);
            return readOne(statement);
        }
    }

    private Optional<IncidentRecoveryPlanRecord> readOne(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next()
                    ? Optional.of(fromJson(resultSet.getString("record_json")))
                    : Optional.empty();
        }
    }

    private void bind(PreparedStatement statement, IncidentRecoveryPlanRecord plan) throws SQLException {
        statement.setString(1, plan.planId());
        statement.setString(2, plan.incidentId());
        statement.setString(3, plan.requestKey());
        statement.setString(4, hasText(plan.plannerRunId()) ? plan.plannerRunId() : null);
        statement.setString(5, plan.assessmentDigest());
        statement.setString(6, plan.status().name());
        statement.setString(7, plan.outcome().name());
        statement.setString(8, toJson(plan));
        statement.setLong(9, plan.version());
        statement.setTimestamp(10, Timestamp.from(plan.createdAt()));
        statement.setTimestamp(11, Timestamp.from(plan.updatedAt()));
    }

    private void validate(IncidentRecoveryPlanRecord plan) {
        if (plan == null || !hasText(plan.planId()) || !hasText(plan.incidentId())
                || !hasText(plan.requestKey()) || !hasText(plan.assessmentDigest())
                || plan.status() == null || plan.outcome() == null) {
            throw new IllegalArgumentException("invalid incident recovery plan record");
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
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_incident_recovery_plan (
                            plan_id TEXT PRIMARY KEY,
                            incident_id TEXT NOT NULL REFERENCES agent_incident(incident_id),
                            request_key TEXT NOT NULL,
                            planner_run_id TEXT UNIQUE,
                            assessment_digest CHAR(64) NOT NULL,
                            status TEXT NOT NULL,
                            outcome TEXT NOT NULL,
                            record_json JSONB NOT NULL,
                            version BIGINT NOT NULL DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL,
                            UNIQUE(incident_id, request_key)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_incident_recovery_plan_incident
                        ON agent_incident_recovery_plan(incident_id, created_at DESC)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_incident_recovery_plan_status
                        ON agent_incident_recovery_plan(status, updated_at DESC)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_incident_recovery_plan_event (
                            event_id TEXT PRIMARY KEY,
                            plan_id TEXT NOT NULL REFERENCES agent_incident_recovery_plan(plan_id),
                            incident_id TEXT NOT NULL,
                            event_sequence BIGINT NOT NULL,
                            event_type TEXT NOT NULL,
                            payload JSONB NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL,
                            UNIQUE(plan_id, event_sequence)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_recovery_plan_event_sequence
                        ON agent_incident_recovery_plan_event(plan_id, event_sequence)
                        """);
                statement.execute("""
                        INSERT INTO agent_incident_recovery_plan_event(
                            event_id, plan_id, incident_id, event_sequence, event_type, payload, created_at
                        )
                        SELECT 'rpevt-backfill-' || plan_id || '-' || version,
                               plan_id, incident_id, version, 'RECOVERY_PLAN_SNAPSHOT',
                               record_json, updated_at
                        FROM agent_incident_recovery_plan
                        ON CONFLICT(plan_id, event_sequence) DO NOTHING
                        """);
                schemaReady.set(true);
            } catch (SQLException exception) {
                throw storageFailure("Failed to initialize incident recovery plan schema", exception);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword());
    }

    private String toJson(IncidentRecoveryPlanRecord value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new AgentStorageException("Failed to serialize incident recovery plan", exception);
        }
    }

    private IncidentRecoveryPlanRecord fromJson(String value) {
        try {
            return objectMapper.readValue(value, IncidentRecoveryPlanRecord.class);
        } catch (RuntimeException exception) {
            throw new AgentStorageException("Failed to deserialize incident recovery plan", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        return objectMapper.readValue(value, Map.class);
    }

    private AgentStorageException storageFailure(String message, SQLException exception) {
        return new AgentStorageException(message, exception);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
