package com.agent.platform.web;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.runtime.*;
import com.agent.platform.workbench.security.*;
import com.agent.platform.workbench.persistence.WorkbenchAccessDeniedException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.spy;

/** Opt-in, isolated schema only. Never runs existing IT cleanup routines or changes business rows. */
@EnabledIfEnvironmentVariable(named="CONVERSATION_POSTGRES_IT", matches="true")
class ConversationOwnershipPostgresIT {
    private final AgentStorageProperties properties = new AgentStorageProperties();
    private final String schema = "phase2_isolation_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;
    private JdbcConversationOwnershipStore owners;
    private JdbcAgentTimelineStore timeline;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv().getOrDefault("AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent");
        String user = System.getenv().getOrDefault("AGENT_STORAGE_POSTGRES_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("AGENT_STORAGE_POSTGRES_PASSWORD", "1234");
        admin = DriverManager.getConnection(url, user, password);
        try (var statement = admin.createStatement()) { statement.execute("CREATE SCHEMA " + schema); }
        properties.getDatasource().setUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        properties.getDatasource().setUsername(user); properties.getDatasource().setPassword(password);
        owners = new JdbcConversationOwnershipStore(properties);
        timeline = spy(new JdbcAgentTimelineStore(properties, new ObjectMapper()));
    }
    @AfterEach void cleanup() throws Exception {
        if (admin == null) return;
        assertTrue(schema.matches("phase2_isolation_[a-f0-9]{32}"));
        try (var connection = admin; var statement = connection.createStatement()) { statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }
    @ParameterizedTest @CsvSource({"t1,bob", "t2,alice"})
    void httpThroughRealOwnershipAndTimeline(String tenant, String user) {
        ConversationIsolationHttpTests.withPersistence(owners, timeline)
                .foreignIdentityCannotContinueReadOrRemapExistingWorkbenchConversation(tenant, user);
    }
    @Test void directAndWorkbenchHttpIsolationAndContinuation() {
        ConversationIsolationHttpTests.withPersistence(owners, timeline)
                .directConversationCannotBeReusedThroughWorkbenchOrByAnotherIdentity();
    }
    @Test void legacyUniqueOwnerCanContinueButAmbiguousOwnerCannot() throws Exception {
        var alice = new AuthenticatedPrincipal("t1", "alice", Set.of("USER"));
        timeline.appendMessages("legacy", "alice", "old-run", List.of(AgentMessageDraft.user("existing",1)));
        assertThrows(WorkbenchAccessDeniedException.class, () -> owners.check(alice,"legacy",true));
        try (var c = DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),properties.getDatasource().getPassword());
             var statement = c.createStatement()) {
            statement.execute("CREATE TABLE agent_conversation_work_state(conversation_id TEXT, tenant_id TEXT, owner_principal_id TEXT)");
            statement.execute("INSERT INTO agent_conversation_work_state VALUES ('legacy','t1','alice')");
            assertTrue(owners.check(alice,"legacy",true));
            assertEquals("existing", timeline.loadMessages("legacy",10).get(0).content());
            statement.execute("INSERT INTO agent_conversation_work_state VALUES ('legacy','t2','alice')");
            assertThrows(WorkbenchAccessDeniedException.class, () -> owners.check(alice,"legacy",false));
        }
    }
    @Test void competingDatabaseConnectionsHaveExactlyOneOwner() throws Exception {
        var a = new AuthenticatedPrincipal("t1","alice",Set.of("USER"));
        var b = new AuthenticatedPrincipal("t2","bob",Set.of("USER"));
        owners.check(a,"initialize-schema",true);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (var identity : List.of(a,b)) futures.add(pool.submit(() -> {
                gate.await();
                try { return owners.check(identity,"race",true); }
                catch (WorkbenchAccessDeniedException expected) { return false; }
            }));
            gate.countDown();
            int winners=0;
            for (var result : futures) if (result.get(10,TimeUnit.SECONDS)) winners++;
            assertEquals(1,winners);
        } finally { pool.shutdownNow(); }
    }
}
