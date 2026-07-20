package com.agent.platform.workbench.budget;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class JdbcHierarchicalBudgetStore implements HierarchicalBudgetStore {

    private final AgentStorageProperties properties;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcHierarchicalBudgetStore(AgentStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public BudgetAccount ensureAccount(BudgetAccountSpec spec) {
        requireSpec(spec);
        ensureSchema();
        String accountId = accountId(spec.ownerType(), spec.ownerId());
        Instant now = Instant.now();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<BudgetAccount> existing = readAccount(connection, accountId, true);
                if (existing.isPresent()) {
                    requireSamePolicy(existing.get(), spec);
                    connection.commit();
                    return existing.get();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO agent_budget_account(account_id,owner_type,owner_id,parent_account_id,
                            tenant_id,owner_principal_id,status,max_model_calls,max_tokens,max_tool_calls,
                            max_duration_millis,max_estimated_cost,reserved_model_calls,reserved_tokens,
                            reserved_tool_calls,reserved_duration_millis,reserved_estimated_cost,
                            consumed_model_calls,consumed_tokens,consumed_tool_calls,consumed_duration_millis,
                            consumed_estimated_cost,version,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,0,0,0,0,0,0,0,0,0,0,0,?,?)
                        """)) {
                    int index = 1;
                    statement.setString(index++, accountId);
                    statement.setString(index++, spec.ownerType());
                    statement.setString(index++, spec.ownerId());
                    statement.setString(index++, blankToNull(spec.parentAccountId()));
                    statement.setString(index++, spec.tenantId());
                    statement.setString(index++, spec.ownerPrincipalId());
                    index = bindLimit(statement, index, spec.maximum());
                    statement.setTimestamp(index++, Timestamp.from(now));
                    statement.setTimestamp(index, Timestamp.from(now));
                    statement.executeUpdate();
                }
                BudgetAccount created = readAccount(connection, accountId, false).orElseThrow();
                connection.commit();
                return created;
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                if (exception instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    BudgetAccount raced = findAccount(spec.ownerType(), spec.ownerId()).orElseThrow();
                    requireSamePolicy(raced, spec);
                    return raced;
                }
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw failure("Failed to ensure budget account", exception);
        }
    }

    @Override
    public BudgetReservation reserve(String accountId,
                                     String operationKey,
                                     String category,
                                     BudgetLimit amount) {
        if (!text(accountId) || !text(operationKey) || !text(category) || amount == null || amount.zero()) {
            throw new IllegalArgumentException("accountId, operationKey, category and non-zero amount are required");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                BudgetAccount account = readAccount(connection, accountId, true)
                        .orElseThrow(() -> new IllegalArgumentException("budget account not found: " + accountId));
                Optional<BudgetReservation> existing = readReservation(connection, accountId, operationKey, true);
                if (existing.isPresent()) {
                    BudgetReservation value = existing.get();
                    if (!value.category().equals(category) || !value.reserved().equals(amount)) {
                        throw new IllegalStateException("budget operation key is bound to another reservation");
                    }
                    connection.commit();
                    if ("DENIED".equals(value.status())) throw exhausted(account, amount);
                    return value;
                }
                BudgetLimit projected = account.consumed().plus(account.reserved()).plus(amount);
                if (!"ACTIVE".equals(account.status()) || !projected.fitsWithin(account.maximum())) {
                    BudgetReservation denied = insertReservation(connection, account, operationKey,
                            category, amount, "DENIED", Instant.now());
                    markExhausted(connection, account.accountId(), account.version());
                    connection.commit();
                    throw exhausted(account, amount);
                }
                BudgetReservation reserved = insertReservation(connection, account, operationKey,
                        category, amount, "RESERVED", Instant.now());
                updateAccountReservation(connection, account, amount, true);
                connection.commit();
                return reserved;
            }
            catch (BudgetExceededException exception) {
                if (!connection.getAutoCommit()) {
                    try { if (!connection.isClosed()) connection.commit(); } catch (SQLException ignored) { }
                }
                throw exception;
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw failure("Failed to reserve budget", exception);
        }
    }

    @Override
    public BudgetReservation settle(String reservationId, BudgetLimit actual) {
        return finish(reservationId, actual == null ? new BudgetLimit(0, 0, 0, 0, 0) : actual, false);
    }

    @Override
    public BudgetReservation release(String reservationId) {
        return finish(reservationId, new BudgetLimit(0, 0, 0, 0, 0), true);
    }

    private BudgetReservation finish(String reservationId, BudgetLimit actual, boolean release) {
        if (!text(reservationId)) throw new IllegalArgumentException("reservationId is required");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                BudgetReservation reservation = readReservationById(connection, reservationId, true)
                        .orElseThrow(() -> new IllegalArgumentException("budget reservation not found"));
                if (!"RESERVED".equals(reservation.status())) {
                    connection.commit();
                    return reservation;
                }
                BudgetAccount account = readAccount(connection, reservation.accountId(), true).orElseThrow();
                Instant now = Instant.now();
                String status = release ? "RELEASED" : "SETTLED";
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_budget_reservation SET status=?,consumed_model_calls=?,consumed_tokens=?,
                            consumed_tool_calls=?,consumed_duration_millis=?,consumed_estimated_cost=?,settled_at=?
                        WHERE reservation_id=? AND status='RESERVED'
                        """)) {
                    statement.setString(1, status);
                    bindLimit(statement, 2, actual);
                    statement.setTimestamp(7, Timestamp.from(now));
                    statement.setString(8, reservation.reservationId());
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("reservation settlement raced");
                }
                updateAccountSettlement(connection, account, reservation.reserved(), actual, now);
                BudgetReservation finished = readReservationById(connection, reservationId, false).orElseThrow();
                connection.commit();
                return finished;
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw failure("Failed to settle budget", exception);
        }
    }

    @Override
    public Optional<BudgetAccount> findAccount(String ownerType, String ownerId) {
        if (!text(ownerType) || !text(ownerId)) return Optional.empty();
        ensureSchema();
        try (Connection connection = openConnection()) {
            return readAccount(connection, accountId(ownerType, ownerId), false);
        }
        catch (SQLException exception) {
            throw failure("Failed to find budget account", exception);
        }
    }

    @Override
    public Optional<BudgetReservation> findReservation(String accountId, String operationKey) {
        if (!text(accountId) || !text(operationKey)) return Optional.empty();
        ensureSchema();
        try (Connection connection = openConnection()) {
            return readReservation(connection, accountId, operationKey, false);
        }
        catch (SQLException exception) {
            throw failure("Failed to find budget reservation", exception);
        }
    }

    @Override
    public Optional<BudgetReservation> findReservedByCategory(String accountId, String category) {
        if (!text(accountId) || !text(category)) return Optional.empty();
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM agent_budget_reservation
                     WHERE account_id=? AND category=? AND status='RESERVED'
                     ORDER BY created_at LIMIT 1
                     """)) {
            statement.setString(1, accountId); statement.setString(2, category);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapReservation(resultSet)) : Optional.empty();
            }
        }
        catch (SQLException exception) {
            throw failure("Failed to find active budget reservation", exception);
        }
    }

    private BudgetReservation insertReservation(Connection connection,
                                                BudgetAccount account,
                                                String operationKey,
                                                String category,
                                                BudgetLimit amount,
                                                String status,
                                                Instant now) throws SQLException {
        String id = "bres-" + UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_budget_reservation(reservation_id,account_id,operation_key,category,status,
                    reserved_model_calls,reserved_tokens,reserved_tool_calls,reserved_duration_millis,
                    reserved_estimated_cost,consumed_model_calls,consumed_tokens,consumed_tool_calls,
                    consumed_duration_millis,consumed_estimated_cost,created_at,settled_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,0,0,0,0,0,?,?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, account.accountId());
            statement.setString(3, operationKey);
            statement.setString(4, category);
            statement.setString(5, status);
            bindLimit(statement, 6, amount);
            statement.setTimestamp(11, Timestamp.from(now));
            statement.setTimestamp(12, "DENIED".equals(status) ? Timestamp.from(now) : null);
            statement.executeUpdate();
        }
        return readReservationById(connection, id, false).orElseThrow();
    }

    private void updateAccountReservation(Connection connection,
                                          BudgetAccount account,
                                          BudgetLimit amount,
                                          boolean add) throws SQLException {
        int sign = add ? 1 : -1;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_budget_account SET reserved_model_calls=reserved_model_calls+?,
                    reserved_tokens=reserved_tokens+?,reserved_tool_calls=reserved_tool_calls+?,
                    reserved_duration_millis=reserved_duration_millis+?,
                    reserved_estimated_cost=reserved_estimated_cost+?,version=version+1,updated_at=?
                WHERE account_id=? AND version=?
                """)) {
            statement.setInt(1, sign * amount.modelCalls());
            statement.setLong(2, sign * amount.tokens());
            statement.setInt(3, sign * amount.toolCalls());
            statement.setLong(4, sign * amount.durationMillis());
            statement.setDouble(5, sign * amount.estimatedCost());
            statement.setTimestamp(6, Timestamp.from(Instant.now()));
            statement.setString(7, account.accountId());
            statement.setLong(8, account.version());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("budget account CAS conflict");
        }
    }

    private void updateAccountSettlement(Connection connection,
                                         BudgetAccount account,
                                         BudgetLimit reserved,
                                         BudgetLimit actual,
                                         Instant now) throws SQLException {
        BudgetLimit projected = account.consumed().plus(actual);
        String status = projected.fitsWithin(account.maximum()) ? account.status() : "EXHAUSTED";
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_budget_account SET status=?,
                    reserved_model_calls=reserved_model_calls-?,reserved_tokens=reserved_tokens-?,
                    reserved_tool_calls=reserved_tool_calls-?,reserved_duration_millis=reserved_duration_millis-?,
                    reserved_estimated_cost=reserved_estimated_cost-?,consumed_model_calls=consumed_model_calls+?,
                    consumed_tokens=consumed_tokens+?,consumed_tool_calls=consumed_tool_calls+?,
                    consumed_duration_millis=consumed_duration_millis+?,
                    consumed_estimated_cost=consumed_estimated_cost+?,version=version+1,updated_at=?
                WHERE account_id=? AND version=?
                """)) {
            statement.setString(1, status);
            statement.setInt(2, reserved.modelCalls()); statement.setLong(3, reserved.tokens());
            statement.setInt(4, reserved.toolCalls()); statement.setLong(5, reserved.durationMillis());
            statement.setDouble(6, reserved.estimatedCost());
            statement.setInt(7, actual.modelCalls()); statement.setLong(8, actual.tokens());
            statement.setInt(9, actual.toolCalls()); statement.setLong(10, actual.durationMillis());
            statement.setDouble(11, actual.estimatedCost());
            statement.setTimestamp(12, Timestamp.from(now)); statement.setString(13, account.accountId());
            statement.setLong(14, account.version());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("budget account settlement CAS conflict");
        }
    }

    private void markExhausted(Connection connection, String accountId, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_budget_account SET status='EXHAUSTED',version=version+1,updated_at=?
                WHERE account_id=? AND version=?
                """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, accountId);
            statement.setLong(3, version);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("budget exhaustion CAS conflict");
        }
    }

    private Optional<BudgetAccount> readAccount(Connection connection, String id, boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_budget_account WHERE account_id=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapAccount(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<BudgetReservation> readReservation(Connection connection,
                                                         String accountId,
                                                         String operationKey,
                                                         boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_budget_reservation WHERE account_id=? AND operation_key=?
                """ + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, accountId); statement.setString(2, operationKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapReservation(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<BudgetReservation> readReservationById(Connection connection,
                                                             String id,
                                                             boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_budget_reservation WHERE reservation_id=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapReservation(resultSet)) : Optional.empty();
            }
        }
    }

    private BudgetAccount mapAccount(ResultSet rs) throws SQLException {
        return new BudgetAccount(rs.getString("account_id"), rs.getString("owner_type"),
                rs.getString("owner_id"), blank(rs.getString("parent_account_id")),
                rs.getString("tenant_id"), rs.getString("owner_principal_id"), rs.getString("status"),
                readLimit(rs, "max_"), readLimit(rs, "reserved_"), readLimit(rs, "consumed_"),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private BudgetReservation mapReservation(ResultSet rs) throws SQLException {
        Timestamp settled = rs.getTimestamp("settled_at");
        return new BudgetReservation(rs.getString("reservation_id"), rs.getString("account_id"),
                rs.getString("operation_key"), rs.getString("category"), rs.getString("status"),
                readLimit(rs, "reserved_"), readLimit(rs, "consumed_"),
                rs.getTimestamp("created_at").toInstant(), settled == null ? null : settled.toInstant());
    }

    private BudgetLimit readLimit(ResultSet rs, String prefix) throws SQLException {
        return new BudgetLimit(rs.getInt(prefix + "model_calls"), rs.getLong(prefix + "tokens"),
                rs.getInt(prefix + "tool_calls"), rs.getLong(prefix + "duration_millis"),
                rs.getDouble(prefix + "estimated_cost"));
    }

    private int bindLimit(PreparedStatement statement, int index, BudgetLimit limit) throws SQLException {
        statement.setInt(index++, limit.modelCalls()); statement.setLong(index++, limit.tokens());
        statement.setInt(index++, limit.toolCalls()); statement.setLong(index++, limit.durationMillis());
        statement.setDouble(index++, limit.estimatedCost());
        return index;
    }

    private void requireSpec(BudgetAccountSpec spec) {
        if (spec == null || !text(spec.ownerType()) || !text(spec.ownerId()) || !text(spec.tenantId())
                || !text(spec.ownerPrincipalId()) || spec.maximum() == null || spec.maximum().zero()) {
            throw new IllegalArgumentException("complete non-zero budget account spec is required");
        }
    }

    private void requireSamePolicy(BudgetAccount account, BudgetAccountSpec spec) {
        if (!account.ownerType().equals(spec.ownerType()) || !account.ownerId().equals(spec.ownerId())
                || !account.tenantId().equals(spec.tenantId())
                || !account.ownerPrincipalId().equals(spec.ownerPrincipalId())
                || !account.parentAccountId().equals(blank(spec.parentAccountId()))
                || !account.maximum().equals(spec.maximum())) {
            throw new IllegalStateException("budget account is already bound to another immutable policy");
        }
    }

    private BudgetExceededException exhausted(BudgetAccount account, BudgetLimit requested) {
        return new BudgetExceededException("BUDGET_EXHAUSTED",
                "budget exhausted for " + account.ownerType() + ":" + account.ownerId()
                        + " while reserving " + requested);
    }

    private String accountId(String ownerType, String ownerId) { return "budget:" + ownerType + ":" + ownerId; }

    private void ensureSchema() {
        if (schemaReady.get()) return;
        synchronized (schemaReady) {
            if (schemaReady.get()) return;
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_budget_account(
                            account_id TEXT PRIMARY KEY,owner_type TEXT NOT NULL,owner_id TEXT NOT NULL,
                            parent_account_id TEXT,tenant_id TEXT NOT NULL,owner_principal_id TEXT NOT NULL,
                            status TEXT NOT NULL,max_model_calls INT NOT NULL,max_tokens BIGINT NOT NULL,
                            max_tool_calls INT NOT NULL,max_duration_millis BIGINT NOT NULL,
                            max_estimated_cost DOUBLE PRECISION NOT NULL,reserved_model_calls INT NOT NULL DEFAULT 0,
                            reserved_tokens BIGINT NOT NULL DEFAULT 0,reserved_tool_calls INT NOT NULL DEFAULT 0,
                            reserved_duration_millis BIGINT NOT NULL DEFAULT 0,
                            reserved_estimated_cost DOUBLE PRECISION NOT NULL DEFAULT 0,
                            consumed_model_calls INT NOT NULL DEFAULT 0,consumed_tokens BIGINT NOT NULL DEFAULT 0,
                            consumed_tool_calls INT NOT NULL DEFAULT 0,consumed_duration_millis BIGINT NOT NULL DEFAULT 0,
                            consumed_estimated_cost DOUBLE PRECISION NOT NULL DEFAULT 0,version BIGINT NOT NULL DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
                            UNIQUE(owner_type,owner_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_budget_reservation(
                            reservation_id TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES agent_budget_account(account_id),
                            operation_key TEXT NOT NULL,category TEXT NOT NULL,status TEXT NOT NULL,
                            reserved_model_calls INT NOT NULL,reserved_tokens BIGINT NOT NULL,
                            reserved_tool_calls INT NOT NULL,reserved_duration_millis BIGINT NOT NULL,
                            reserved_estimated_cost DOUBLE PRECISION NOT NULL,consumed_model_calls INT NOT NULL DEFAULT 0,
                            consumed_tokens BIGINT NOT NULL DEFAULT 0,consumed_tool_calls INT NOT NULL DEFAULT 0,
                            consumed_duration_millis BIGINT NOT NULL DEFAULT 0,
                            consumed_estimated_cost DOUBLE PRECISION NOT NULL DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL,settled_at TIMESTAMPTZ,
                            UNIQUE(account_id,operation_key)
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_budget_owner ON agent_budget_account(tenant_id,owner_principal_id,owner_type,owner_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_budget_reservation_status ON agent_budget_reservation(account_id,status,created_at)");
                schemaReady.set(true);
            }
            catch (SQLException exception) { throw failure("Failed to initialize budget schema", exception); }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }
    private boolean text(String value) { return value != null && !value.isBlank(); }
    private String blank(String value) { return value == null ? "" : value; }
    private String blankToNull(String value) { return text(value) ? value.trim() : null; }
    private void rollback(Connection connection) { try { connection.rollback(); } catch (SQLException ignored) { } }
    private AgentStorageException failure(String message, SQLException exception) {
        return new AgentStorageException(message, exception);
    }
}
