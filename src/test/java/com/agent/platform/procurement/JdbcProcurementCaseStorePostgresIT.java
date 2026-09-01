package com.agent.platform.procurement;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.procurement.application.ProcurementCasePatchMerger;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCasePatch;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.persistence.JdbcProcurementCaseStore;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 需要显式打开 PROCUREMENT_POSTGRES_IT=true，验证真实 PostgreSQL UPDATE ... WHERE version CAS。 */
@EnabledIfEnvironmentVariable(named = "PROCUREMENT_POSTGRES_IT", matches = "true")
class JdbcProcurementCaseStorePostgresIT {
    private static final String CONVERSATION_PREFIX = "procurement-cas-it-";
    private final AgentStorageProperties storageProperties = storageProperties();

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                storageProperties.getDatasource().getUrl(),
                storageProperties.getDatasource().getUsername(),
                storageProperties.getDatasource().getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM procurement_case_state WHERE conversation_id LIKE ?")) {
            statement.setString(1, CONVERSATION_PREFIX + "%");
            statement.executeUpdate();
        }
    }

    @Test
    void onlyOneConcurrentWriterCanUpdateTheSameCaseVersion() throws Exception {
        JdbcProcurementCaseStore store = new JdbcProcurementCaseStore(storageProperties, new ObjectMapper());
        String conversationId = CONVERSATION_PREFIX + UUID.randomUUID();
        ProcurementCase created = new com.agent.platform.procurement.application.ProcurementCaseService(
                store, new com.agent.platform.procurement.application.ProcurementCaseParser()).ensureCase(
                "tenant", conversationId, "buyer");
        ProcurementCasePatchMerger merger = new ProcurementCasePatchMerger();
        ProcurementCaseState quantityState = merger.merge(created.state(), new ProcurementCasePatch(
                null, null, 50, null, null, null, Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of()));
        ProcurementCaseState budgetState = merger.merge(created.state(), new ProcurementCasePatch(
                null, null, null, new java.math.BigDecimal("600000"), null, null,
                Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of()));
        ProcurementCase quantity = next(created, quantityState, "quantity");
        ProcurementCase budget = next(created, budgetState, "budget");

        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> store.saveIfVersion(quantity, 0));
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> store.saveIfVersion(budget, 0));

        assertEquals(1, List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)).stream()
                .filter(Boolean::booleanValue).count());
        assertEquals(1, store.findByTenantUserAndConversationId("tenant", "buyer", conversationId)
                .orElseThrow().version());
    }

    @Test
    void schemaMigrationAlignsLegacyJsonVersionAndRejectsStaleCasWriter() throws Exception {
        JdbcProcurementCaseStore store = new JdbcProcurementCaseStore(storageProperties, new ObjectMapper());
        String conversationId = CONVERSATION_PREFIX + UUID.randomUUID();
        ProcurementCase created = new com.agent.platform.procurement.application.ProcurementCaseService(
                store, new com.agent.platform.procurement.application.ProcurementCaseParser()).ensureCase(
                "tenant", conversationId, "buyer");
        ProcurementCase versionOne = next(created, created.state(), "version-one");
        assertTrue(store.saveIfVersion(versionOne, 0));

        try (Connection connection = DriverManager.getConnection(
                storageProperties.getDatasource().getUrl(),
                storageProperties.getDatasource().getUsername(),
                storageProperties.getDatasource().getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE procurement_case_state SET version = 0 WHERE tenant_id = ? AND user_id = ? AND conversation_id = ?")) {
            statement.setString(1, "tenant");
            statement.setString(2, "buyer");
            statement.setString(3, conversationId);
            assertEquals(1, statement.executeUpdate());
        }

        JdbcProcurementCaseStore migratedStore = new JdbcProcurementCaseStore(storageProperties, new ObjectMapper());
        ProcurementCase migrated = migratedStore.findByTenantUserAndConversationId("tenant", "buyer", conversationId)
                .orElseThrow();
        assertEquals(1, migrated.version());
        ProcurementCase versionTwo = next(migrated, migrated.state(), "version-two");
        assertFalse(migratedStore.saveIfVersion(versionTwo, 0));
        assertTrue(migratedStore.saveIfVersion(versionTwo, 1));
        assertEquals(2, migratedStore.findByTenantUserAndConversationId("tenant", "buyer", conversationId)
                .orElseThrow().version());
    }

    private ProcurementCase next(ProcurementCase current, ProcurementCaseState state, String inputId) {
        return new ProcurementCase(current.caseId(), current.tenantId(), current.conversationId(), current.userId(),
                current.status(), state, current.createdAt(), Instant.now(), current.version() + 1, inputId);
    }

    private AgentStorageProperties storageProperties() {
        AgentStorageProperties properties = new AgentStorageProperties();
        properties.getDatasource().setUrl(environment("AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent"));
        properties.getDatasource().setUsername(environment("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        properties.getDatasource().setPassword(environment("AGENT_STORAGE_POSTGRES_PASSWORD", ""));
        return properties;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
