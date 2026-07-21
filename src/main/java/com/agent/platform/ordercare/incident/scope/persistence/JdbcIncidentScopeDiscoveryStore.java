package com.agent.platform.ordercare.incident.scope.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCandidate;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshot;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshotStatus;
import com.agent.platform.storage.AgentStorageException;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class JdbcIncidentScopeDiscoveryStore implements IncidentScopeDiscoveryStore {

    private final AgentStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcIncidentScopeDiscoveryStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public IncidentScopeSnapshot createOrLoad(IncidentScopeSnapshot requested) {
        requireSnapshot(requested);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO agent_incident_scope_snapshot(
                            snapshot_id,tenant_id,owner_principal_id,conversation_id,work_item_id,
                            source_input_id,discovery_request_id,criteria_json,criteria_digest,
                            candidates_json,source_health_json,candidate_fingerprint,candidate_count,
                            truncated,status,version,lease_owner,lease_until,fencing_token,expires_at,
                            confirmed_at,confirmed_by,failure_code,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb,?,?,?, ?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(discovery_request_id) DO NOTHING
                        """)) {
                    bindInsert(statement, requested);
                    statement.executeUpdate();
                }
                IncidentScopeSnapshot stored = readByDiscoveryRequest(
                        connection, requested.discoveryRequestId(), false).orElseThrow();
                requireSameRequest(stored, requested);
                connection.commit();
                return stored;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure("Failed to create incident scope snapshot", exception);
        }
    }

    @Override
    public Optional<IncidentScopeSnapshot> find(AuthenticatedPrincipal principal, String snapshotId) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            return read(connection, "snapshot_id", snapshotId, principal, false);
        } catch (SQLException exception) {
            throw failure("Failed to load incident scope snapshot", exception);
        }
    }

    @Override
    public Optional<IncidentScopeSnapshot> findByDiscoveryRequestId(
            AuthenticatedPrincipal principal, String discoveryRequestId) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            return read(connection, "discovery_request_id", discoveryRequestId, principal, false);
        } catch (SQLException exception) {
            throw failure("Failed to load incident scope discovery request", exception);
        }
    }

    @Override
    public IncidentScopeClaim claim(AuthenticatedPrincipal principal,
                                    String discoveryRequestId,
                                    String leaseOwner,
                                    Duration leaseDuration) {
        requirePrincipal(principal);
        requireText(discoveryRequestId, "discoveryRequestId");
        requireText(leaseOwner, "leaseOwner");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                IncidentScopeSnapshot current = read(
                        connection, "discovery_request_id", discoveryRequestId, principal, true)
                        .orElseThrow(() -> new IllegalArgumentException("incident scope snapshot not found"));
                if (current.status() == IncidentScopeSnapshotStatus.CANDIDATES_READY
                        || current.status() == IncidentScopeSnapshotStatus.WAITING_CONFIRMATION
                        || current.status() == IncidentScopeSnapshotStatus.CONFIRMED
                        || current.status() == IncidentScopeSnapshotStatus.CANCELLED
                        || current.status() == IncidentScopeSnapshotStatus.EXPIRED) {
                    connection.commit();
                    return new IncidentScopeClaim(current, false);
                }
                Instant now = Instant.now();
                if (current.status() == IncidentScopeSnapshotStatus.DISCOVERING
                        && current.leaseUntil() != null && current.leaseUntil().isAfter(now)
                        && !leaseOwner.equals(current.leaseOwner())) {
                    connection.commit();
                    return new IncidentScopeClaim(current, false);
                }
                Instant leaseUntil = now.plus(normalizeLease(leaseDuration));
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_incident_scope_snapshot
                        SET status='DISCOVERING',lease_owner=?,lease_until=?,
                            fencing_token=fencing_token+1,version=version+1,updated_at=?
                        WHERE snapshot_id=? AND version=?
                        """)) {
                    statement.setString(1, leaseOwner);
                    statement.setTimestamp(2, Timestamp.from(leaseUntil));
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setString(4, current.snapshotId());
                    statement.setLong(5, current.version());
                    if (statement.executeUpdate() != 1) {
                        throw new IncidentScopeCasException("incident scope claim lost");
                    }
                }
                IncidentScopeSnapshot claimed = read(
                        connection, "snapshot_id", current.snapshotId(), principal, false).orElseThrow();
                connection.commit();
                return new IncidentScopeClaim(claimed, true);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure("Failed to claim incident scope discovery", exception);
        }
    }

    @Override
    public IncidentScopeSnapshot complete(AuthenticatedPrincipal principal,
                                          String snapshotId,
                                          String leaseOwner,
                                          long fencingToken,
                                          List<IncidentScopeCandidate> candidates,
                                          Map<String, String> sourceHealth,
                                          String candidateFingerprint,
                                          boolean truncated) {
        requirePrincipal(principal);
        requireText(candidateFingerprint, "candidateFingerprint");
        return mutateFenced(principal, snapshotId, leaseOwner, fencingToken, current -> {
            if (current.status() != IncidentScopeSnapshotStatus.DISCOVERING) {
                throw new IncidentScopeCasException("incident scope is not discovering");
            }
            return new Mutation(IncidentScopeSnapshotStatus.CANDIDATES_READY,
                    candidates == null ? List.of() : List.copyOf(candidates),
                    sourceHealth == null ? Map.of() : Map.copyOf(sourceHealth),
                    candidateFingerprint, candidates == null ? 0 : candidates.size(), truncated, "");
        });
    }

    @Override
    public IncidentScopeSnapshot fail(AuthenticatedPrincipal principal,
                                      String snapshotId,
                                      String leaseOwner,
                                      long fencingToken,
                                      String failureCode) {
        return mutateFenced(principal, snapshotId, leaseOwner, fencingToken,
                current -> new Mutation(IncidentScopeSnapshotStatus.FAILED,
                        current.candidates(), current.sourceHealth(), current.candidateFingerprint(),
                        current.candidateCount(), current.truncated(), blank(failureCode)));
    }

    @Override
    public IncidentScopeSnapshot markWaitingConfirmation(AuthenticatedPrincipal principal,
                                                         String snapshotId,
                                                         long expectedVersion) {
        return transition(principal, snapshotId, expectedVersion,
                IncidentScopeSnapshotStatus.CANDIDATES_READY,
                IncidentScopeSnapshotStatus.WAITING_CONFIRMATION, null, "");
    }

    @Override
    public IncidentScopeSnapshot confirm(AuthenticatedPrincipal principal,
                                         String snapshotId,
                                         long expectedVersion,
                                         String candidateFingerprint) {
        requireText(candidateFingerprint, "candidateFingerprint");
        return transition(principal, snapshotId, expectedVersion,
                IncidentScopeSnapshotStatus.WAITING_CONFIRMATION,
                IncidentScopeSnapshotStatus.CONFIRMED, principal.principalId(), candidateFingerprint);
    }

    private IncidentScopeSnapshot transition(AuthenticatedPrincipal principal,
                                             String snapshotId,
                                             long expectedVersion,
                                             IncidentScopeSnapshotStatus from,
                                             IncidentScopeSnapshotStatus to,
                                             String confirmedBy,
                                             String expectedFingerprint) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                IncidentScopeSnapshot current = read(connection, "snapshot_id", snapshotId, principal, true)
                        .orElseThrow(() -> new IllegalArgumentException("incident scope snapshot not found"));
                if (to == IncidentScopeSnapshotStatus.CONFIRMED
                        && current.status() == IncidentScopeSnapshotStatus.CONFIRMED
                        && current.candidateFingerprint().equals(expectedFingerprint)) {
                    connection.commit();
                    return current;
                }
                if (current.version() != expectedVersion || current.status() != from) {
                    throw new IncidentScopeCasException("incident scope version or status changed");
                }
                if (to == IncidentScopeSnapshotStatus.CONFIRMED
                        && !current.candidateFingerprint().equals(expectedFingerprint)) {
                    throw new IncidentScopeCasException("incident scope fingerprint changed");
                }
                Instant now = Instant.now();
                if (current.expiresAt() == null || !current.expiresAt().isAfter(now)) {
                    updateStatus(connection, current, IncidentScopeSnapshotStatus.EXPIRED, now, null);
                    connection.commit();
                    throw new IncidentScopeCasException("incident scope snapshot expired");
                }
                updateStatus(connection, current, to, now, confirmedBy);
                IncidentScopeSnapshot updated = read(connection, "snapshot_id", snapshotId, principal, false)
                        .orElseThrow();
                connection.commit();
                return updated;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure("Failed to transition incident scope snapshot", exception);
        }
    }

    private IncidentScopeSnapshot mutateFenced(AuthenticatedPrincipal principal,
                                                String snapshotId,
                                                String leaseOwner,
                                                long fencingToken,
                                                java.util.function.Function<IncidentScopeSnapshot, Mutation> mutation) {
        requirePrincipal(principal);
        requireText(leaseOwner, "leaseOwner");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                IncidentScopeSnapshot current = read(connection, "snapshot_id", snapshotId, principal, true)
                        .orElseThrow(() -> new IllegalArgumentException("incident scope snapshot not found"));
                Instant now = Instant.now();
                if (!leaseOwner.equals(current.leaseOwner()) || current.fencingToken() != fencingToken
                        || current.leaseUntil() == null || !current.leaseUntil().isAfter(now)) {
                    throw new IncidentScopeCasException("stale incident scope fencing token");
                }
                Mutation value = mutation.apply(current);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_incident_scope_snapshot SET status=?,candidates_json=?::jsonb,
                            source_health_json=?::jsonb,candidate_fingerprint=?,candidate_count=?,
                            truncated=?,failure_code=?,lease_owner=NULL,lease_until=NULL,
                            version=version+1,updated_at=?
                        WHERE snapshot_id=? AND version=? AND lease_owner=? AND fencing_token=?
                        """)) {
                    statement.setString(1, value.status().name());
                    statement.setString(2, json(value.candidates()));
                    statement.setString(3, json(value.sourceHealth()));
                    statement.setString(4, value.fingerprint());
                    statement.setInt(5, value.candidateCount());
                    statement.setBoolean(6, value.truncated());
                    statement.setString(7, value.failureCode());
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.setString(9, snapshotId);
                    statement.setLong(10, current.version());
                    statement.setString(11, leaseOwner);
                    statement.setLong(12, fencingToken);
                    if (statement.executeUpdate() != 1) {
                        throw new IncidentScopeCasException("incident scope fenced update lost");
                    }
                }
                IncidentScopeSnapshot updated = read(connection, "snapshot_id", snapshotId, principal, false)
                        .orElseThrow();
                connection.commit();
                return updated;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure("Failed to update incident scope discovery", exception);
        }
    }

    private void updateStatus(Connection connection,
                              IncidentScopeSnapshot current,
                              IncidentScopeSnapshotStatus target,
                              Instant now,
                              String confirmedBy) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_incident_scope_snapshot SET status=?,version=version+1,updated_at=?,
                    confirmed_at=?,confirmed_by=?,lease_owner=NULL,lease_until=NULL
                WHERE snapshot_id=? AND version=?
                """)) {
            statement.setString(1, target.name());
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, target == IncidentScopeSnapshotStatus.CONFIRMED ? Timestamp.from(now) : null);
            statement.setString(4, target == IncidentScopeSnapshotStatus.CONFIRMED ? confirmedBy : null);
            statement.setString(5, current.snapshotId());
            statement.setLong(6, current.version());
            if (statement.executeUpdate() != 1) {
                throw new IncidentScopeCasException("incident scope transition lost");
            }
        }
    }

    private Optional<IncidentScopeSnapshot> read(Connection connection,
                                                 String column,
                                                 String value,
                                                 AuthenticatedPrincipal principal,
                                                 boolean lock) throws SQLException {
        if (!text(value)) return Optional.empty();
        String sql = "SELECT * FROM agent_incident_scope_snapshot WHERE " + column
                + "=? AND tenant_id=? AND owner_principal_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value.trim());
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<IncidentScopeSnapshot> readByDiscoveryRequest(
            Connection connection, String discoveryRequestId, boolean lock) throws SQLException {
        String sql = "SELECT * FROM agent_incident_scope_snapshot WHERE discovery_request_id=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, discoveryRequestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private IncidentScopeSnapshot map(ResultSet rs) throws SQLException {
        return new IncidentScopeSnapshot(
                rs.getString("snapshot_id"), rs.getString("tenant_id"), rs.getString("owner_principal_id"),
                rs.getString("conversation_id"), rs.getString("work_item_id"), rs.getString("source_input_id"),
                rs.getString("discovery_request_id"),
                fromJson(rs.getString("criteria_json"), IncidentScopeCriteria.class),
                rs.getString("criteria_digest"),
                fromJson(rs.getString("candidates_json"), new TypeReference<List<IncidentScopeCandidate>>() {}),
                fromJson(rs.getString("source_health_json"), new TypeReference<Map<String, String>>() {}),
                blank(rs.getString("candidate_fingerprint")), rs.getInt("candidate_count"),
                rs.getBoolean("truncated"), IncidentScopeSnapshotStatus.valueOf(rs.getString("status")),
                rs.getLong("version"), blank(rs.getString("lease_owner")), instant(rs, "lease_until"),
                rs.getLong("fencing_token"), instant(rs, "expires_at"), instant(rs, "confirmed_at"),
                blank(rs.getString("confirmed_by")), blank(rs.getString("failure_code")),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private void bindInsert(PreparedStatement statement, IncidentScopeSnapshot value) throws SQLException {
        int index = 1;
        statement.setString(index++, value.snapshotId());
        statement.setString(index++, value.tenantId());
        statement.setString(index++, value.ownerPrincipalId());
        statement.setString(index++, value.conversationId());
        statement.setString(index++, value.workItemId());
        statement.setString(index++, value.sourceInputId());
        statement.setString(index++, value.discoveryRequestId());
        statement.setString(index++, json(value.criteria()));
        statement.setString(index++, value.criteriaDigest());
        statement.setString(index++, json(value.candidates()));
        statement.setString(index++, json(value.sourceHealth()));
        statement.setString(index++, value.candidateFingerprint());
        statement.setInt(index++, value.candidateCount());
        statement.setBoolean(index++, value.truncated());
        statement.setString(index++, value.status().name());
        statement.setLong(index++, value.version());
        statement.setString(index++, nullIfBlank(value.leaseOwner()));
        statement.setTimestamp(index++, timestamp(value.leaseUntil()));
        statement.setLong(index++, value.fencingToken());
        statement.setTimestamp(index++, timestamp(value.expiresAt()));
        statement.setTimestamp(index++, timestamp(value.confirmedAt()));
        statement.setString(index++, nullIfBlank(value.confirmedBy()));
        statement.setString(index++, nullIfBlank(value.failureCode()));
        statement.setTimestamp(index++, timestamp(value.createdAt()));
        statement.setTimestamp(index, timestamp(value.updatedAt()));
    }

    private void requireSameRequest(IncidentScopeSnapshot stored, IncidentScopeSnapshot requested) {
        if (!stored.tenantId().equals(requested.tenantId())
                || !stored.ownerPrincipalId().equals(requested.ownerPrincipalId())
                || !stored.workItemId().equals(requested.workItemId())
                || !stored.criteriaDigest().equals(requested.criteriaDigest())) {
            throw new IncidentScopeCasException("discoveryRequestId is bound to another scope");
        }
    }

    private void requireSnapshot(IncidentScopeSnapshot value) {
        if (value == null) throw new IllegalArgumentException("snapshot is required");
        requireText(value.snapshotId(), "snapshotId");
        requireText(value.discoveryRequestId(), "discoveryRequestId");
        requireText(value.tenantId(), "tenantId");
        requireText(value.ownerPrincipalId(), "ownerPrincipalId");
        requireText(value.workItemId(), "workItemId");
        requireText(value.criteriaDigest(), "criteriaDigest");
    }

    private void requirePrincipal(AuthenticatedPrincipal principal) {
        if (principal == null) throw new IllegalArgumentException("principal is required");
    }

    private void ensureSchema() {
        if (schemaReady.get()) return;
        synchronized (schemaReady) {
            if (schemaReady.get()) return;
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_incident_scope_snapshot(
                            snapshot_id TEXT PRIMARY KEY,
                            tenant_id TEXT NOT NULL,
                            owner_principal_id TEXT NOT NULL,
                            conversation_id TEXT NOT NULL,
                            work_item_id TEXT NOT NULL,
                            source_input_id TEXT NOT NULL,
                            discovery_request_id TEXT NOT NULL UNIQUE,
                            criteria_json JSONB NOT NULL,
                            criteria_digest CHAR(64) NOT NULL,
                            candidates_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                            source_health_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                            candidate_fingerprint CHAR(64),
                            candidate_count INT NOT NULL DEFAULT 0,
                            truncated BOOLEAN NOT NULL DEFAULT FALSE,
                            status TEXT NOT NULL,
                            version BIGINT NOT NULL DEFAULT 0,
                            lease_owner TEXT,
                            lease_until TIMESTAMPTZ,
                            fencing_token BIGINT NOT NULL DEFAULT 0,
                            expires_at TIMESTAMPTZ NOT NULL,
                            confirmed_at TIMESTAMPTZ,
                            confirmed_by TEXT,
                            failure_code TEXT,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_incident_scope_owner_work
                        ON agent_incident_scope_snapshot(tenant_id,owner_principal_id,work_item_id,created_at)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_incident_scope_lease
                        ON agent_incident_scope_snapshot(status,lease_until,updated_at)
                        """);
                schemaReady.set(true);
            } catch (SQLException exception) {
                throw failure("Failed to initialize incident scope snapshot schema", exception);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }

    private <T> T fromJson(String value, Class<T> type) {
        return objectMapper.readValue(value, type);
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        return objectMapper.readValue(value, type);
    }

    private String json(Object value) { return objectMapper.writeValueAsString(value); }
    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private Duration normalizeLease(Duration value) {
        return value == null || value.isZero() || value.isNegative() ? Duration.ofSeconds(30) : value;
    }
    private void requireText(String value, String field) {
        if (!text(value)) throw new IllegalArgumentException(field + " must not be blank");
    }
    private boolean text(String value) { return value != null && !value.isBlank(); }
    private String blank(String value) { return value == null ? "" : value.trim(); }
    private String nullIfBlank(String value) { return text(value) ? value.trim() : null; }
    private void rollback(Connection connection) { try { connection.rollback(); } catch (SQLException ignored) { } }
    private AgentStorageException failure(String message, SQLException exception) {
        return new AgentStorageException(message, exception);
    }

    private record Mutation(IncidentScopeSnapshotStatus status,
                            List<IncidentScopeCandidate> candidates,
                            Map<String, String> sourceHealth,
                            String fingerprint,
                            int candidateCount,
                            boolean truncated,
                            String failureCode) {
    }
}
