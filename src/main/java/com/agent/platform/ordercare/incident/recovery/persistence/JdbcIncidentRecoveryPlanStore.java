package com.agent.platform.ordercare.incident.recovery.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException;
import com.agent.platform.ordercare.incident.persistence.IncidentIdempotencyConflictException;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
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
            Optional<IncidentRecoveryPlanRecord> existing = findByRequestKey(connection, plan.incidentId(), plan.requestKey());
            if (existing.isPresent()) {
                IncidentRecoveryPlanRecord current = existing.get();
                if (!current.planId().equals(plan.planId())
                        || !current.assessmentDigest().equals(plan.assessmentDigest())) {
                    throw new IncidentIdempotencyConflictException(
                            "recovery plan requestKey already bound to another request");
                }
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
                return plan;
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
    public IncidentRecoveryPlanRecord update(IncidentRecoveryPlanRecord next, long expectedVersion) {
        validate(next);
        if (next.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("next recovery plan version must equal expectedVersion + 1");
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
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
            return next;
        } catch (SQLException exception) {
            throw storageFailure("Failed to update incident recovery plan: " + next.planId(), exception);
        }
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

    private AgentStorageException storageFailure(String message, SQLException exception) {
        return new AgentStorageException(message, exception);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
