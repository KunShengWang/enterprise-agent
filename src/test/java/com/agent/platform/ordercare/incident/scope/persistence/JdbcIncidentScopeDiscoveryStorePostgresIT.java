package com.agent.platform.ordercare.incident.scope.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeAnomalyType;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCandidate;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeRelationQuality;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshot;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshotStatus;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcIncidentScopeDiscoveryStorePostgresIT {

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final AgentStorageProperties properties = properties();
    private final JdbcIncidentScopeDiscoveryStore store =
            new JdbcIncidentScopeDiscoveryStore(properties, new ObjectMapper());
    private final AuthenticatedPrincipal principal =
            new AuthenticatedPrincipal("tenant-scope-" + suffix, "alice", Set.of("INCIDENT_OPERATOR"));

    @AfterEach
    void clean() throws Exception {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM agent_incident_scope_snapshot WHERE discovery_request_id LIKE ?")) {
            statement.setString(1, "discovery-" + suffix + "%");
            statement.executeUpdate();
        }
    }

    @Test
    void idempotentCreateLeaseTakeoverFencingAndConfirmation() throws Exception {
        IncidentScopeSnapshot requested = snapshot("main", Instant.now().plusSeconds(600));
        IncidentScopeSnapshot created = store.createOrLoad(requested);
        IncidentScopeSnapshot duplicate = store.createOrLoad(snapshot("main", Instant.now().plusSeconds(600)));

        assertThat(duplicate.snapshotId()).isEqualTo(created.snapshotId());
        IncidentScopeClaim first = store.claim(principal, requested.discoveryRequestId(),
                "instance-a", Duration.ofMinutes(2));
        assertThat(first.acquired()).isTrue();
        IncidentScopeClaim competing = new JdbcIncidentScopeDiscoveryStore(properties, new ObjectMapper())
                .claim(principal, requested.discoveryRequestId(), "instance-b", Duration.ofMinutes(2));
        assertThat(competing.acquired()).isFalse();

        expireLease(requested.discoveryRequestId());
        IncidentScopeClaim takeover = new JdbcIncidentScopeDiscoveryStore(properties, new ObjectMapper())
                .claim(principal, requested.discoveryRequestId(), "instance-b", Duration.ofMinutes(2));
        assertThat(takeover.acquired()).isTrue();
        assertThat(takeover.snapshot().fencingToken()).isGreaterThan(first.snapshot().fencingToken());
        assertThatThrownBy(() -> store.complete(principal, created.snapshotId(), "instance-a",
                first.snapshot().fencingToken(), List.of(candidate()), Map.of(), fingerprint('a'), false))
                .isInstanceOf(IncidentScopeCasException.class);

        IncidentScopeSnapshot ready = store.complete(principal, created.snapshotId(), "instance-b",
                takeover.snapshot().fencingToken(), List.of(candidate()),
                Map.of("inventory", "AVAILABLE"), fingerprint('b'), false);
        IncidentScopeSnapshot waiting = store.markWaitingConfirmation(
                principal, ready.snapshotId(), ready.version());
        assertThatThrownBy(() -> store.confirm(principal, waiting.snapshotId(), waiting.version(), fingerprint('x')))
                .isInstanceOf(IncidentScopeCasException.class);
        IncidentScopeSnapshot confirmed = store.confirm(
                principal, waiting.snapshotId(), waiting.version(), fingerprint('b'));
        IncidentScopeSnapshot repeated = store.confirm(
                principal, waiting.snapshotId(), waiting.version(), fingerprint('b'));

        assertThat(confirmed.status()).isEqualTo(IncidentScopeSnapshotStatus.CONFIRMED);
        assertThat(repeated.version()).isEqualTo(confirmed.version());
        AuthenticatedPrincipal otherTenant = new AuthenticatedPrincipal("other-tenant", "alice", Set.of());
        assertThat(store.find(otherTenant, confirmed.snapshotId())).isEmpty();
    }

    @Test
    void expiredSnapshotCannotBeConfirmedAndPersistsExpiredState() throws Exception {
        IncidentScopeSnapshot created = store.createOrLoad(snapshot("expired", Instant.now().plusSeconds(600)));
        IncidentScopeClaim claim = store.claim(principal, created.discoveryRequestId(),
                "instance-a", Duration.ofMinutes(1));
        IncidentScopeSnapshot ready = store.complete(principal, created.snapshotId(), "instance-a",
                claim.snapshot().fencingToken(), List.of(candidate()), Map.of(), fingerprint('c'), false);
        IncidentScopeSnapshot waiting = store.markWaitingConfirmation(principal, ready.snapshotId(), ready.version());
        expireSnapshot(waiting.snapshotId());

        assertThatThrownBy(() -> store.confirm(
                principal, waiting.snapshotId(), waiting.version(), fingerprint('c')))
                .isInstanceOf(IncidentScopeCasException.class)
                .hasMessageContaining("expired");
        assertThat(store.find(principal, waiting.snapshotId()).orElseThrow().status())
                .isEqualTo(IncidentScopeSnapshotStatus.EXPIRED);
    }

    private IncidentScopeSnapshot snapshot(String name, Instant expiresAt) {
        Instant now = Instant.now();
        IncidentScopeCriteria criteria = new IncidentScopeCriteria(
                "昨晚", now.minusSeconds(3600), now, "Asia/Shanghai", false,
                List.of(IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED),
                List.of(), List.of(), List.of());
        return new IncidentScopeSnapshot(
                "scope-" + suffix + "-" + name, principal.tenantId(), principal.principalId(),
                "conversation-" + suffix, "work-" + suffix, "input-" + suffix,
                "discovery-" + suffix + "-" + name, criteria, fingerprint('d'),
                List.of(), Map.of(), "", 0, false, IncidentScopeSnapshotStatus.NEW,
                0, "", null, 0, expiresAt, null, "", "", now, now);
    }

    private IncidentScopeCandidate candidate() {
        return new IncidentScopeCandidate(
                "REQ-1", "ORDER-1", "DEDUCT-1", List.of("3"),
                List.of("floworder.order.state.dlq"), 40, 20, 20, "UNRELEASED",
                List.of(IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED),
                List.of("ORDER_CANDIDATE_MATCHED", "INVENTORY_UNRELEASED"),
                IncidentScopeRelationQuality.STRONG, "COMPLETE", List.of(), List.of());
    }

    private void expireLease(String discoveryRequestId) throws Exception {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE agent_incident_scope_snapshot SET lease_until=? WHERE discovery_request_id=?")) {
            statement.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
            statement.setString(2, discoveryRequestId);
            statement.executeUpdate();
        }
    }

    private void expireSnapshot(String snapshotId) throws Exception {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE agent_incident_scope_snapshot SET expires_at=? WHERE snapshot_id=?")) {
            statement.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
            statement.setString(2, snapshotId);
            statement.executeUpdate();
        }
    }

    private String fingerprint(char value) { return String.valueOf(value).repeat(64); }

    private Connection open() throws Exception {
        return DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }

    private AgentStorageProperties properties() {
        AgentStorageProperties value = new AgentStorageProperties();
        value.getDatasource().setUrl(environment("AGENT_STORAGE_POSTGRES_URL",
                "jdbc:postgresql://localhost:5432/enterprise_agent"));
        value.getDatasource().setUsername(environment("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        value.getDatasource().setPassword(environment("AGENT_STORAGE_POSTGRES_PASSWORD", "1234"));
        return value;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
