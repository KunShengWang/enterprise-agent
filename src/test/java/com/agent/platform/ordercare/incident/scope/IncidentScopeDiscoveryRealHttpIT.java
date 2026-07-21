package com.agent.platform.ordercare.incident.scope;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.scope.application.IncidentScopeCandidateAssembler;
import com.agent.platform.ordercare.incident.scope.application.IncidentScopeDigests;
import com.agent.platform.ordercare.incident.scope.application.IncidentScopeDiscoveryCommand;
import com.agent.platform.ordercare.incident.scope.application.IncidentScopeDiscoveryCoordinator;
import com.agent.platform.ordercare.incident.scope.application.IncidentScopePolicy;
import com.agent.platform.ordercare.incident.scope.application.IncidentTimeRangeResolver;
import com.agent.platform.ordercare.incident.scope.client.HttpFlowOrderScopeDiscoveryClient;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeAnomalyType;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshotStatus;
import com.agent.platform.ordercare.incident.scope.persistence.JdbcIncidentScopeDiscoveryStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentScopeDiscoveryRealHttpIT {

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String discoveryRequestId = "m4d-real-http-" + suffix;
    private final AgentStorageProperties storage = storage();

    @AfterEach
    void clean() throws Exception {
        if (!enabled()) return;
        try (Connection connection = DriverManager.getConnection(
                storage.getDatasource().getUrl(), storage.getDatasource().getUsername(),
                storage.getDatasource().getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM agent_incident_scope_snapshot WHERE discovery_request_id=?")) {
            statement.setString(1, discoveryRequestId);
            statement.executeUpdate();
        }
    }

    @Test
    void discoversHappyFixtureThroughRealFlowOrderHttpAndPersistsPostgresSnapshot() {
        Assumptions.assumeTrue(enabled(), "M4 real HTTP gate is opt-in");
        ObjectMapper objectMapper = new ObjectMapper();
        OrderCareProperties properties = new OrderCareProperties();
        properties.setFloworderOrderBaseUrl(required("M4_FLOWORDER_ORDER_URL"));
        properties.setFloworderBaseUrl(required("M4_FLOWORDER_RESOURCE_URL"));
        properties.setIncidentScopeInternalToken(required("M4_FLOWORDER_INTERNAL_TOKEN"));
        JdbcIncidentScopeDiscoveryStore store = new JdbcIncidentScopeDiscoveryStore(storage, objectMapper);
        IncidentScopeDigests digests = new IncidentScopeDigests(objectMapper);
        IncidentScopeDiscoveryCoordinator coordinator = new IncidentScopeDiscoveryCoordinator(
                new IncidentTimeRangeResolver(properties), new IncidentScopePolicy(properties), digests,
                new IncidentScopeCandidateAssembler(digests, properties), store,
                new HttpFlowOrderScopeDiscoveryClient(properties, objectMapper), properties);
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "tenant-m4d-" + suffix, "alice", Set.of("INCIDENT_OPERATOR"));

        var ready = coordinator.discover(principal, new IncidentScopeDiscoveryCommand(
                discoveryRequestId, "conversation-" + suffix, "work-" + suffix, "input-" + suffix,
                "昨晚", "Asia/Shanghai",
                List.of(IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED),
                List.of(), List.of(), List.of(), "trace-" + suffix));
        var waiting = store.markWaitingConfirmation(principal, ready.snapshotId(), ready.version());
        var confirmed = store.confirm(principal, waiting.snapshotId(), waiting.version(),
                waiting.candidateFingerprint());

        assertThat(ready.candidateCount()).isEqualTo(3);
        assertThat(ready.candidates()).extracting(value -> value.requestId())
                .containsExactly("IC-HAPPY-REQ-001", "IC-HAPPY-REQ-002", "IC-HAPPY-REQ-003");
        assertThat(ready.candidates()).allMatch(value -> "UNRELEASED".equals(value.releaseState()));
        assertThat(ready.candidates()).flatExtracting(value -> value.queueNames())
                .contains("floworder.incident.e2e.dlq");
        assertThat(confirmed.status()).isEqualTo(IncidentScopeSnapshotStatus.CONFIRMED);
        assertThat(confirmed.candidateFingerprint()).hasSize(64);
    }

    private AgentStorageProperties storage() {
        AgentStorageProperties properties = new AgentStorageProperties();
        properties.getDatasource().setUrl(requiredOrDefault(
                "AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:5432/enterprise_agent"));
        properties.getDatasource().setUsername(requiredOrDefault("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        properties.getDatasource().setPassword(requiredOrDefault("AGENT_STORAGE_POSTGRES_PASSWORD", ""));
        return properties;
    }

    private boolean enabled() {
        return Boolean.parseBoolean(System.getenv("M4_REAL_HTTP_ENABLED"));
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private String requiredOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
