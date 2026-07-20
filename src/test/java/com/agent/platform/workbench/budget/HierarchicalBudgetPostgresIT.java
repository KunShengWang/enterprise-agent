package com.agent.platform.workbench.budget;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.config.WorkbenchBudgetProperties;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentRunLimits;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentRunBudgetSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HierarchicalBudgetPostgresIT {

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final AgentStorageProperties properties = properties();

    @AfterEach
    void clean() throws Exception {
        try (Connection connection = openConnection()) {
            delete(connection, "DELETE FROM agent_budget_reservation WHERE account_id IN "
                    + "(SELECT account_id FROM agent_budget_account WHERE owner_id LIKE ?)", "%" + suffix + "%");
            delete(connection, "DELETE FROM agent_budget_account WHERE owner_id LIKE ?", "%" + suffix + "%");
        }
    }

    @Test
    void reservationAndSettlementMaintainDurableCounters() {
        JdbcHierarchicalBudgetStore store = store();
        BudgetAccount account = store.ensureAccount(spec("settle", limit(4, 1000, 3)));
        BudgetReservation reserved = store.reserve(account.accountId(), "operation-1", "ROUTER",
                limit(1, 200, 0));
        BudgetAccount afterReserve = store.findAccount("WORK_ITEM", "settle-" + suffix).orElseThrow();
        assertEquals(200, afterReserve.reserved().tokens());
        assertEquals(0, afterReserve.consumed().tokens());

        store.settle(reserved.reservationId(), new BudgetLimit(1, 120, 0, 20, 0.2));
        BudgetAccount settled = store.findAccount("WORK_ITEM", "settle-" + suffix).orElseThrow();
        assertEquals(0, settled.reserved().tokens());
        assertEquals(120, settled.consumed().tokens());
        assertEquals(1, settled.consumed().modelCalls());
    }

    @Test
    void sameOperationKeyReturnsOriginalReservationWithoutDoubleCounting() {
        JdbcHierarchicalBudgetStore store = store();
        BudgetAccount account = store.ensureAccount(spec("idempotent", limit(3, 500, 1)));
        BudgetLimit amount = limit(1, 100, 0);
        BudgetReservation first = store.reserve(account.accountId(), "same", "TARGET", amount);
        BudgetReservation duplicate = store.reserve(account.accountId(), "same", "TARGET", amount);
        assertEquals(first.reservationId(), duplicate.reservationId());
        assertEquals(100, store.findAccount("WORK_ITEM", "idempotent-" + suffix)
                .orElseThrow().reserved().tokens());
    }

    @Test
    void exhaustedAccountPersistsDenialAndCannotCreateAnotherReservation() {
        JdbcHierarchicalBudgetStore store = store();
        BudgetAccount account = store.ensureAccount(spec("exhaust", limit(1, 100, 0)));
        store.reserve(account.accountId(), "first", "ROLE", limit(1, 80, 0));

        assertThrows(BudgetExceededException.class, () ->
                store.reserve(account.accountId(), "second", "ROLE", limit(1, 30, 0)));
        BudgetAccount exhausted = store.findAccount("WORK_ITEM", "exhaust-" + suffix).orElseThrow();
        assertEquals("EXHAUSTED", exhausted.status());
        assertEquals("DENIED", store.findReservation(account.accountId(), "second").orElseThrow().status());
    }

    @Test
    void twoStoreInstancesCannotOversellTheSameAccount() throws Exception {
        JdbcHierarchicalBudgetStore first = store();
        JdbcHierarchicalBudgetStore second = store();
        BudgetAccount account = first.ensureAccount(spec("concurrent", limit(2, 100, 0)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> reserveAfter(ready, start, first, account.accountId(), "a"));
            var b = executor.submit(() -> reserveAfter(ready, start, second, account.accountId(), "b"));
            ready.await(); start.countDown();
            int accepted = (a.get() ? 1 : 0) + (b.get() ? 1 : 0);
            assertEquals(1, accepted);
        }
        finally {
            executor.shutdownNow();
        }
        BudgetAccount persisted = first.findAccount("WORK_ITEM", "concurrent-" + suffix).orElseThrow();
        assertEquals(80, persisted.reserved().tokens());
    }

    @Test
    void incidentChildAccountRequiresParentReservationAndAggregatesRoleRuns() {
        JdbcHierarchicalBudgetStore store = store();
        WorkbenchBudgetProperties policy = new WorkbenchBudgetProperties();
        BudgetAccount parent = store.ensureAccount(new BudgetAccountSpec(
                "WORK_ITEM", "parent-" + suffix, "", "tenant-" + suffix, "alice",
                policy.workItemLimit()));
        DefaultIncidentBudgetService incidents = new DefaultIncidentBudgetService(
                store, policy, mock(AgentRunStore.class));

        assertThrows(BudgetExceededException.class, () ->
                incidents.initializeIncident("incident-no-reserve-" + suffix, "parent-" + suffix));

        store.reserve(parent.accountId(), "dispatch-incident", "TARGET_INCIDENT_INVESTIGATION",
                policy.targetLimit(com.agent.platform.workbench.target.ExecutionTargetId.INCIDENT_INVESTIGATION));
        incidents.initializeIncident("incident-" + suffix, "parent-" + suffix);
        AgentExecutionProfile profile = new AgentExecutionProfile("role", "", java.util.Set.of(),
                new AgentRunLimits(2, 2, 1, 2_000, 500, 2, 30_000), false);
        IncidentBudgetReservation first = incidents.reserveIncidentRun(
                "incident-" + suffix, "commander", "COMMANDER", profile);
        IncidentBudgetReservation duplicate = incidents.reserveIncidentRun(
                "incident-" + suffix, "commander", "COMMANDER", profile);

        assertEquals(first.handle().reservationId(), duplicate.handle().reservationId());
        BudgetAccount child = store.findAccount("INCIDENT", "incident-" + suffix).orElseThrow();
        assertEquals(2, child.reserved().modelCalls());
        assertEquals(parent.accountId(), child.parentAccountId());

        AgentRuntimeResult result = mock(AgentRuntimeResult.class);
        when(result.budget()).thenReturn(new AgentRunBudgetSnapshot(
                1, 1, 1, 80, 20, 0.5, Instant.now(), Instant.now().plusSeconds(29),
                false, 29_000, false));
        incidents.settle(first, result);
        incidents.completeIncident("incident-" + suffix);
        BudgetAccount settledParent = store.findAccount("WORK_ITEM", "parent-" + suffix).orElseThrow();
        assertEquals(0, settledParent.reserved().tokens());
        assertEquals(100, settledParent.consumed().tokens());
    }

    private boolean reserveAfter(CountDownLatch ready,
                                 CountDownLatch start,
                                 JdbcHierarchicalBudgetStore store,
                                 String accountId,
                                 String key) throws Exception {
        ready.countDown(); start.await();
        try {
            store.reserve(accountId, key, "ROLE", limit(1, 80, 0));
            return true;
        }
        catch (BudgetExceededException expected) {
            return false;
        }
    }

    private BudgetAccountSpec spec(String name, BudgetLimit maximum) {
        return new BudgetAccountSpec("WORK_ITEM", name + "-" + suffix, "",
                "tenant-" + suffix, "alice", maximum);
    }

    private BudgetLimit limit(int models, long tokens, int tools) {
        return new BudgetLimit(models, tokens, tools, 10_000, 10);
    }

    private JdbcHierarchicalBudgetStore store() { return new JdbcHierarchicalBudgetStore(properties); }
    private AgentStorageProperties properties() {
        AgentStorageProperties value = new AgentStorageProperties();
        value.getDatasource().setUrl(env("AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent"));
        value.getDatasource().setUsername(env("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        value.getDatasource().setPassword(env("AGENT_STORAGE_POSTGRES_PASSWORD", "1234"));
        return value;
    }
    private String env(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value;
    }
    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }
    private void delete(Connection connection, String sql, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value); statement.executeUpdate();
        }
    }
}
