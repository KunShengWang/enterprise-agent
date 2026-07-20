package com.agent.platform.workbench.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkCommandExecution;
import com.agent.platform.workbench.model.WorkCommandExecutionStatus;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class JdbcWorkCommandExecutionStore implements WorkCommandExecutionStore {

    private final AgentStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcWorkCommandExecutionStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkCommandClaim claim(AuthenticatedPrincipal principal,
                                  String inputId,
                                  String workItemId,
                                  WorkCommandType commandType,
                                  long expectedWorkVersion,
                                  String leaseOwner,
                                  Duration leaseDuration) {
        require(principal, inputId, workItemId, commandType, leaseOwner);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem work = readWork(connection, principal, workItemId, true)
                        .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
                Optional<WorkCommandExecution> existing = readByInput(connection, principal, inputId, true);
                if (existing.isPresent()) {
                    WorkCommandExecution execution = existing.get();
                    requireSameRequest(execution, workItemId, commandType);
                    if (execution.status() != WorkCommandExecutionStatus.EXECUTING) {
                        connection.commit();
                        return new WorkCommandClaim(execution, false);
                    }
                    Instant now = Instant.now();
                    if (execution.leaseUntil() != null && execution.leaseUntil().isAfter(now)
                            && !execution.leaseOwner().equals(leaseOwner)) {
                        connection.commit();
                        return new WorkCommandClaim(execution, false);
                    }
                    WorkCommandExecution reclaimed = reclaim(
                            connection, execution, leaseOwner, now.plus(normalize(leaseDuration)), now);
                    connection.commit();
                    return new WorkCommandClaim(reclaimed, true);
                }
                if (work.version() != expectedWorkVersion) {
                    throw new WorkbenchCasConflictException("work item version mismatch");
                }
                requireEffectiveCommandDecision(connection, principal, inputId, commandType);
                Instant now = Instant.now();
                WorkCommandExecution created = new WorkCommandExecution(
                        "wcmd-" + inputId, inputId, workItemId, principal.tenantId(), principal.principalId(),
                        commandType, expectedWorkVersion, WorkCommandExecutionStatus.EXECUTING,
                        leaseOwner, now.plus(normalize(leaseDuration)), 1, "", false,
                        work.activeRunId(), "", now, now, null);
                insert(connection, created);
                appendEvent(connection, work, created.commandRequestId() + ":requested",
                        "WORK_COMMAND_REQUESTED", commandType.name(), "Work command admitted",
                        Map.of("command", commandType.name(), "claimToken", created.claimToken()), inputId, now);
                connection.commit();
                return new WorkCommandClaim(created, true);
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                if (exception instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw new WorkbenchCasConflictException("another command is active for this work item");
                }
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw failure("Failed to claim work command", exception);
        }
    }

    @Override
    public WorkCommandExecution complete(AuthenticatedPrincipal principal,
                                         String commandRequestId,
                                         String leaseOwner,
                                         long claimToken,
                                         WorkCommandCompletion completion) {
        if (principal == null || !text(commandRequestId) || !text(leaseOwner) || completion == null) {
            throw new IllegalArgumentException("principal, commandRequestId, leaseOwner and completion are required");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                WorkCommandExecution current = readById(connection, principal, commandRequestId, true)
                        .orElseThrow(() -> new WorkbenchNotFoundException("work command not found"));
                if (current.status() != WorkCommandExecutionStatus.EXECUTING) {
                    connection.commit();
                    return current;
                }
                if (!current.leaseOwner().equals(leaseOwner) || current.claimToken() != claimToken) {
                    throw new WorkbenchCasConflictException("work command claim is no longer owned");
                }
                AgentWorkItem work = readWork(connection, principal, current.workItemId(), true)
                        .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
                Instant now = Instant.now();
                boolean changesState = completion.controlState() != null
                        || completion.executionState() != null || completion.outcome() != null;
                if (changesState) {
                    updateWorkState(connection, work, completion, now);
                    work = readWork(connection, principal, work.workItemId(), true).orElseThrow();
                }
                appendEvent(connection, work, current.commandRequestId() + ":completed",
                        completion.eventType().name(), completion.phase(), completion.message(),
                        completion.eventPayload(), current.inputId(), now);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_command_execution SET status=?, result_code=?,
                            underlying_execution_changed=?, underlying_run_id=?, message=?,
                            lease_until=NULL, updated_at=?, completed_at=?
                        WHERE command_request_id=? AND lease_owner=? AND claim_token=? AND status='EXECUTING'
                        """)) {
                    statement.setString(1, completion.status().name());
                    statement.setString(2, completion.resultCode());
                    statement.setBoolean(3, completion.underlyingExecutionChanged());
                    statement.setString(4, blank(completion.underlyingRunId()));
                    statement.setString(5, blank(completion.message()));
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.setTimestamp(7, Timestamp.from(now));
                    statement.setString(8, current.commandRequestId());
                    statement.setString(9, leaseOwner);
                    statement.setLong(10, claimToken);
                    if (statement.executeUpdate() != 1) {
                        throw new WorkbenchCasConflictException("work command completion lost its claim");
                    }
                }
                WorkCommandExecution completed = readById(connection, principal, commandRequestId, false).orElseThrow();
                connection.commit();
                return completed;
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw failure("Failed to complete work command", exception);
        }
    }

    @Override
    public Optional<WorkCommandExecution> findByInput(AuthenticatedPrincipal principal, String inputId) {
        if (principal == null || !text(inputId)) return Optional.empty();
        ensureSchema();
        try (Connection connection = openConnection()) {
            return readByInput(connection, principal, inputId, false);
        }
        catch (SQLException exception) {
            throw failure("Failed to find work command", exception);
        }
    }

    @Override
    public WorkCommandExecution recordUnboundRejection(AuthenticatedPrincipal principal,
                                                        String inputId,
                                                        WorkCommandType commandType,
                                                        String resultCode,
                                                        String message) {
        if (principal == null || !text(inputId) || commandType == null || !text(resultCode)) {
            throw new IllegalArgumentException("principal, inputId, commandType and resultCode are required");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<WorkCommandExecution> existing = readByInput(connection, principal, inputId, true);
                if (existing.isPresent()) {
                    connection.commit();
                    return existing.get();
                }
                requireEffectiveCommandDecision(connection, principal, inputId, commandType);
                Instant now = Instant.now();
                WorkCommandExecution rejected = new WorkCommandExecution(
                        "wcmd-" + inputId, inputId, "", principal.tenantId(), principal.principalId(),
                        commandType, -1, WorkCommandExecutionStatus.REJECTED, "", null, 0,
                        resultCode, false, "", blank(message), now, now, now);
                insert(connection, rejected);
                connection.commit();
                return rejected;
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                if (exception instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    return findByInput(principal, inputId).orElseThrow(() ->
                            new WorkbenchCasConflictException("unbound command result raced and disappeared"));
                }
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw failure("Failed to record unbound work command result", exception);
        }
    }

    @Override
    public AgentWorkItem requireWorkItem(AuthenticatedPrincipal principal, String workItemId) {
        ensureSchema();
        try (Connection connection = openConnection()) {
            return readWork(connection, principal, workItemId, false)
                    .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
        }
        catch (SQLException exception) {
            throw failure("Failed to read work item", exception);
        }
    }

    private void requireEffectiveCommandDecision(Connection connection,
                                                 AuthenticatedPrincipal principal,
                                                 String inputId,
                                                 WorkCommandType commandType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT command_type FROM agent_work_command_decision
                WHERE input_id=? AND tenant_id=? AND owner_principal_id=? AND decision_status='EFFECTIVE'
                """)) {
            statement.setString(1, inputId);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || !commandType.name().equals(resultSet.getString(1))) {
                    throw new WorkbenchCasConflictException("effective command decision is missing or changed");
                }
            }
        }
    }

    private WorkCommandExecution reclaim(Connection connection,
                                         WorkCommandExecution current,
                                         String leaseOwner,
                                         Instant leaseUntil,
                                         Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_work_command_execution SET lease_owner=?, lease_until=?,
                    claim_token=claim_token+1, updated_at=?
                WHERE command_request_id=? AND claim_token=? AND status='EXECUTING'
                RETURNING *
                """)) {
            statement.setString(1, leaseOwner);
            statement.setTimestamp(2, Timestamp.from(leaseUntil));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, current.commandRequestId());
            statement.setLong(5, current.claimToken());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new WorkbenchCasConflictException("work command reclaim lost");
                return map(resultSet);
            }
        }
    }

    private void updateWorkState(Connection connection,
                                 AgentWorkItem work,
                                 WorkCommandCompletion completion,
                                 Instant now) throws SQLException {
        WorkControlState control = completion.controlState() == null ? work.controlState() : completion.controlState();
        WorkExecutionState execution = completion.executionState() == null ? work.executionState() : completion.executionState();
        WorkOutcome outcome = completion.outcome() == null ? work.outcome() : completion.outcome();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_work_item SET control_state=?, execution_state=?, outcome=?,
                    version=version+1, updated_at=?, completed_at=?
                WHERE work_item_id=? AND tenant_id=? AND owner_principal_id=? AND version=?
                """)) {
            statement.setString(1, control.name());
            statement.setString(2, execution.name());
            statement.setString(3, outcome.name());
            statement.setTimestamp(4, Timestamp.from(now));
            if (control == WorkControlState.CLOSED || control == WorkControlState.ABANDONED) {
                statement.setTimestamp(5, Timestamp.from(now));
            } else {
                statement.setTimestamp(5, work.completedAt() == null ? null : Timestamp.from(work.completedAt()));
            }
            statement.setString(6, work.workItemId());
            statement.setString(7, work.tenantId());
            statement.setString(8, work.ownerPrincipalId());
            statement.setLong(9, work.version());
            if (statement.executeUpdate() != 1) {
                throw new WorkbenchCasConflictException("work item changed while completing command");
            }
        }
    }

    private void appendEvent(Connection connection,
                             AgentWorkItem work,
                             String sourceEventId,
                             String eventType,
                             String phase,
                             String summary,
                             Map<String, Object> payload,
                             String causationId,
                             Instant now) throws SQLException {
        long sequence;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT next_event_sequence FROM agent_work_item WHERE work_item_id=? FOR UPDATE")) {
            statement.setString(1, work.workItemId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new WorkbenchNotFoundException("work item not found");
                sequence = resultSet.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_work_event(event_id,work_item_id,sequence,source_type,source_id,
                    source_event_id,source_sequence,event_type,phase,summary,payload,correlation_id,
                    causation_id,source_created_at,projected_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                ON CONFLICT(work_item_id,source_type,source_id,source_event_id) DO NOTHING
                """)) {
            statement.setString(1, "wevt-" + UUID.randomUUID());
            statement.setString(2, work.workItemId());
            statement.setLong(3, sequence);
            statement.setString(4, "WORK_ITEM");
            statement.setString(5, work.workItemId());
            statement.setString(6, sourceEventId);
            statement.setLong(7, sequence);
            statement.setString(8, eventType);
            statement.setString(9, blank(phase));
            statement.setString(10, blank(summary));
            statement.setString(11, json(payload));
            statement.setString(12, work.workItemId());
            statement.setString(13, blank(causationId));
            statement.setTimestamp(14, Timestamp.from(now));
            statement.setTimestamp(15, Timestamp.from(now));
            if (statement.executeUpdate() == 0) return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE agent_work_item SET next_event_sequence=?,updated_at=? WHERE work_item_id=?")) {
            statement.setLong(1, sequence + 1);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, work.workItemId());
            statement.executeUpdate();
        }
    }

    private void insert(Connection connection, WorkCommandExecution value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_work_command_execution(command_request_id,input_id,work_item_id,tenant_id,
                    owner_principal_id,command_type,admitted_work_version,status,lease_owner,lease_until,
                    claim_token,result_code,underlying_execution_changed,underlying_run_id,message,
                    created_at,updated_at,completed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            int index = 1;
            statement.setString(index++, value.commandRequestId());
            statement.setString(index++, value.inputId());
            statement.setString(index++, text(value.workItemId()) ? value.workItemId() : null);
            statement.setString(index++, value.tenantId());
            statement.setString(index++, value.ownerPrincipalId());
            statement.setString(index++, value.commandType().name());
            statement.setLong(index++, value.admittedWorkVersion());
            statement.setString(index++, value.status().name());
            statement.setString(index++, value.leaseOwner());
            statement.setTimestamp(index++, value.leaseUntil() == null ? null : Timestamp.from(value.leaseUntil()));
            statement.setLong(index++, value.claimToken());
            statement.setString(index++, value.resultCode());
            statement.setBoolean(index++, value.underlyingExecutionChanged());
            statement.setString(index++, value.underlyingRunId());
            statement.setString(index++, value.message());
            statement.setTimestamp(index++, Timestamp.from(value.createdAt()));
            statement.setTimestamp(index++, Timestamp.from(value.updatedAt()));
            statement.setTimestamp(index, value.completedAt() == null ? null : Timestamp.from(value.completedAt()));
            statement.executeUpdate();
        }
    }

    private Optional<WorkCommandExecution> readByInput(Connection connection,
                                                        AuthenticatedPrincipal principal,
                                                        String inputId,
                                                        boolean lock) throws SQLException {
        return read(connection, "input_id", inputId, principal, lock);
    }

    private Optional<WorkCommandExecution> readById(Connection connection,
                                                     AuthenticatedPrincipal principal,
                                                     String commandRequestId,
                                                     boolean lock) throws SQLException {
        return read(connection, "command_request_id", commandRequestId, principal, lock);
    }

    private Optional<WorkCommandExecution> read(Connection connection,
                                                 String column,
                                                 String value,
                                                 AuthenticatedPrincipal principal,
                                                 boolean lock) throws SQLException {
        String sql = "SELECT * FROM agent_work_command_execution WHERE " + column
                + "=? AND tenant_id=? AND owner_principal_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<AgentWorkItem> readWork(Connection connection,
                                             AuthenticatedPrincipal principal,
                                             String workItemId,
                                             boolean lock) throws SQLException {
        String sql = "SELECT * FROM agent_work_item WHERE work_item_id=? AND tenant_id=? AND owner_principal_id=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workItemId);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapWork(resultSet)) : Optional.empty();
            }
        }
    }

    private WorkCommandExecution map(ResultSet resultSet) throws SQLException {
        return new WorkCommandExecution(
                resultSet.getString("command_request_id"), resultSet.getString("input_id"),
                resultSet.getString("work_item_id"), resultSet.getString("tenant_id"),
                resultSet.getString("owner_principal_id"), WorkCommandType.valueOf(resultSet.getString("command_type")),
                resultSet.getLong("admitted_work_version"),
                WorkCommandExecutionStatus.valueOf(resultSet.getString("status")),
                blank(resultSet.getString("lease_owner")), instant(resultSet, "lease_until"),
                resultSet.getLong("claim_token"), blank(resultSet.getString("result_code")),
                resultSet.getBoolean("underlying_execution_changed"), blank(resultSet.getString("underlying_run_id")),
                blank(resultSet.getString("message")), instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"), instant(resultSet, "completed_at"));
    }

    private AgentWorkItem mapWork(ResultSet rs) throws SQLException {
        return new AgentWorkItem(rs.getString("work_item_id"), rs.getString("conversation_id"),
                rs.getString("tenant_id"), rs.getString("owner_principal_id"), rs.getString("original_goal"),
                rs.getString("normalized_goal"), WorkControlState.valueOf(rs.getString("control_state")),
                WorkExecutionState.valueOf(rs.getString("execution_state")), WorkOutcome.valueOf(rs.getString("outcome")),
                blank(rs.getString("active_execution_target")), blank(rs.getString("active_run_id")),
                blank(rs.getString("active_incident_id")), blank(rs.getString("active_recovery_plan_id")),
                blank(rs.getString("route_decision_id")), rs.getString("source_input_id"),
                blank(rs.getString("parent_work_item_id")), rs.getString("routing_request_id"),
                rs.getInt("routing_attempt_count"), instant(rs, "routing_last_attempt_at"),
                instant(rs, "routing_next_retry_at"), blank(rs.getString("routing_failure_code")),
                blank(rs.getString("dispatch_request_id")), rs.getLong("next_event_sequence"), rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "completed_at"));
    }

    private void requireSameRequest(WorkCommandExecution existing,
                                    String workItemId,
                                    WorkCommandType commandType) {
        if (!existing.workItemId().equals(workItemId) || existing.commandType() != commandType) {
            throw new WorkbenchIdempotencyConflictException("command input is already bound to another request");
        }
    }

    private void require(AuthenticatedPrincipal principal,
                         String inputId,
                         String workItemId,
                         WorkCommandType commandType,
                         String leaseOwner) {
        if (principal == null || !text(inputId) || !text(workItemId) || commandType == null || !text(leaseOwner)) {
            throw new IllegalArgumentException("principal, inputId, workItemId, commandType and leaseOwner are required");
        }
    }

    private void ensureSchema() {
        if (schemaReady.get()) return;
        synchronized (schemaReady) {
            if (schemaReady.get()) return;
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("""
                CREATE TABLE IF NOT EXISTS agent_work_command_execution(
                            command_request_id TEXT PRIMARY KEY,
                            input_id TEXT NOT NULL UNIQUE REFERENCES agent_work_input(input_id),
                            work_item_id TEXT REFERENCES agent_work_item(work_item_id),
                            tenant_id TEXT NOT NULL,
                            owner_principal_id TEXT NOT NULL,
                            command_type TEXT NOT NULL,
                            admitted_work_version BIGINT NOT NULL,
                            status TEXT NOT NULL,
                            lease_owner TEXT,
                            lease_until TIMESTAMPTZ,
                            claim_token BIGINT NOT NULL DEFAULT 1,
                            result_code TEXT,
                            underlying_execution_changed BOOLEAN NOT NULL DEFAULT FALSE,
                            underlying_run_id TEXT,
                            message TEXT,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL,
                            completed_at TIMESTAMPTZ
                        )
                        """);
                statement.execute("ALTER TABLE agent_work_command_execution ALTER COLUMN work_item_id DROP NOT NULL");
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS uk_work_command_active_per_work
                        ON agent_work_command_execution(work_item_id) WHERE status='EXECUTING'
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_work_command_execution_owner
                        ON agent_work_command_execution(tenant_id,owner_principal_id,work_item_id,created_at)
                        """);
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw failure("Failed to initialize work command schema", exception);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }

    private Duration normalize(Duration duration) {
        return duration == null || duration.isNegative() || duration.isZero() ? Duration.ofMinutes(5) : duration;
    }

    private String json(Map<String, Object> payload) {
        return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private boolean text(String value) { return value != null && !value.isBlank(); }
    private String blank(String value) { return value == null ? "" : value; }
    private void rollback(Connection connection) { try { connection.rollback(); } catch (SQLException ignored) { } }
    private AgentStorageException failure(String message, SQLException exception) {
        return new AgentStorageException(message, exception);
    }
}
