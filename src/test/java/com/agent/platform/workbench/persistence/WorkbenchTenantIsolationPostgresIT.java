package com.agent.platform.workbench.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.workbench.application.SubmitWorkInputCommand;
import com.agent.platform.workbench.application.WorkInputService;
import com.agent.platform.workbench.application.WorkItemCreationResult;
import com.agent.platform.workbench.application.WorkItemService;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.RoutePreviewStatus;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class WorkbenchTenantIsolationPostgresIT {

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    private final AgentStorageProperties properties = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String prefix = "s0-" + UUID.randomUUID();
    private final String conversationId = "shared-conversation-" + prefix;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "DELETE FROM agent_dispatch_attempt WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-s0-%");
            execute(connection, "DELETE FROM agent_route_preview WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-s0-%");
            execute(connection, "DELETE FROM agent_routing_decision WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-s0-%");
            execute(connection, "DELETE FROM agent_work_command_decision WHERE tenant_id LIKE ?", "tenant-s0-%");
            execute(connection, "DELETE FROM agent_work_event WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-s0-%");
            execute(connection, "DELETE FROM agent_work_link WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-s0-%");
            execute(connection, "DELETE FROM agent_work_relation WHERE source_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?) OR target_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-s0-%", "tenant-s0-%");
            execute(connection, "DELETE FROM agent_conversation_work_state WHERE tenant_id LIKE ?", "tenant-s0-%");
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id LIKE ?", "tenant-s0-%");
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id LIKE ?", "tenant-s0-%");
            connection.commit();
        }
    }

    @Test
    void sameConversationIdIsIsolatedByTenantAndPrincipalAtSqlBoundary() {
        JdbcWorkbenchStore store = workbench();
        AuthenticatedPrincipal tenantA = principal("tenant-s0-a-" + prefix, "alice");
        AuthenticatedPrincipal tenantB = principal("tenant-s0-b-" + prefix, "bob");
        AuthenticatedPrincipal sharedAlice = principal("tenant-s0-shared-" + prefix, "alice");
        AuthenticatedPrincipal sharedBob = principal("tenant-s0-shared-" + prefix, "bob");
        AuthenticatedPrincipal unknown = principal("tenant-s0-unknown-" + prefix, "mallory");

        WorkItemCreationResult workA = submit(store, tenantA, "a");
        WorkItemCreationResult workB = submit(store, tenantB, "b");
        WorkItemCreationResult alice = submit(store, sharedAlice, "shared-a");
        WorkItemCreationResult bob = submit(store, sharedBob, "shared-b");

        assertEquals(workA.workItem().workItemId(), store.findConversationState(tenantA, conversationId)
                .orElseThrow().focusedWorkItemId());
        assertEquals(workB.workItem().workItemId(), store.findConversationState(tenantB, conversationId)
                .orElseThrow().focusedWorkItemId());
        assertEquals(alice.workItem().workItemId(), store.findConversationState(sharedAlice, conversationId)
                .orElseThrow().focusedWorkItemId());
        assertEquals(bob.workItem().workItemId(), store.findConversationState(sharedBob, conversationId)
                .orElseThrow().focusedWorkItemId());
        assertFalse(store.findConversationState(unknown, conversationId).isPresent());

        for (int index = 0; index < 5; index++) {
            assertEquals(workA.workItem().workItemId(), store.findConversationState(tenantA, conversationId)
                    .orElseThrow().focusedWorkItemId());
            assertFalse(store.findConversationState(unknown, conversationId).isPresent());
        }
        assertEquals(4, count("SELECT count(*) FROM agent_conversation_work_state WHERE conversation_id=?", conversationId));
    }

    @Test
    void foreignIdentityCannotReadWorkGraphInputEventsLinksRoutingOrPreview() throws Exception {
        JdbcWorkbenchStore store = workbench();
        JdbcRoutingStore routing = routing();
        JdbcDispatchStore dispatch = dispatch();
        AuthenticatedPrincipal owner = principal("tenant-s0-owner-" + prefix, "alice");
        AuthenticatedPrincipal foreignTenant = principal("tenant-s0-foreign-" + prefix, "mallory");
        AuthenticatedPrincipal foreignPrincipal = principal(owner.tenantId(), "bob");
        WorkItemCreationResult created = submit(store, owner, "protected");
        PreviewFixture preview = seedIncidentPreview(created.workItem());

        for (AuthenticatedPrincipal attacker : Set.of(foreignTenant, foreignPrincipal)) {
            assertTrue(store.findWorkItem(attacker, created.workItem().workItemId()).isEmpty());
            assertTrue(store.findInput(attacker, created.input().inputId()).isEmpty());
            assertThrows(WorkbenchNotFoundException.class,
                    () -> store.loadEvents(attacker, created.workItem().workItemId(), -1, 100));
            assertThrows(WorkbenchNotFoundException.class,
                    () -> store.listRelations(attacker, created.workItem().workItemId()));
            assertThrows(WorkbenchNotFoundException.class,
                    () -> store.listLinks(attacker, created.workItem().workItemId()));
            assertTrue(routing.listCommandDecisions(attacker, created.input().inputId()).isEmpty());
            assertTrue(routing.listRoutingDecisions(attacker, created.workItem().workItemId()).isEmpty());
            assertThrows(WorkbenchNotFoundException.class,
                    () -> dispatch.findPreview(attacker, created.workItem().workItemId()));
            assertThrows(WorkbenchNotFoundException.class, () -> dispatch.confirmPreview(
                    attacker, created.workItem().workItemId(), preview.previewId(), 1, DIGEST_A, DIGEST_B));
        }

        assertEquals(RoutePreviewStatus.ACTIVE,
                dispatch.findPreview(owner, created.workItem().workItemId()).orElseThrow().status());
        assertTrue(Arrays.stream(WorkbenchStore.class.getMethods())
                .noneMatch(method -> method.getName().equals("listWorkItemsByConversation")
                        || method.getName().equals("listInputsByConversation")));
    }

    @Test
    void rejectedCrossIdentityFocusAndPreviewMutationsLeaveAllAuthoritativeStateUnchanged() throws Exception {
        JdbcWorkbenchStore store = workbench();
        JdbcDispatchStore dispatch = dispatch();
        AuthenticatedPrincipal owner = principal("tenant-s0-victim-" + prefix, "alice");
        AuthenticatedPrincipal otherTenant = principal("tenant-s0-attacker-" + prefix, "mallory");
        AuthenticatedPrincipal otherPrincipal = principal(owner.tenantId(), "bob");
        WorkItemCreationResult victim = submit(store, owner, "victim");
        WorkItemCreationResult tenantAttacker = submit(store, otherTenant, "tenant-attacker");
        WorkItemCreationResult principalAttacker = submit(store, otherPrincipal, "principal-attacker");
        PreviewFixture preview = seedIncidentPreview(victim.workItem());

        Snapshot before = snapshot(victim.workItem().workItemId(), tenantAttacker.workItem().workItemId(),
                principalAttacker.workItem().workItemId());

        assertThrows(WorkbenchNotFoundException.class, () -> store.switchFocus(
                otherTenant, conversationId, victim.workItem().workItemId(), tenantAttacker.focus().version()));
        assertThrows(WorkbenchNotFoundException.class, () -> store.switchFocus(
                otherPrincipal, conversationId, victim.workItem().workItemId(), principalAttacker.focus().version()));
        assertThrows(WorkbenchNotFoundException.class, () -> dispatch.confirmPreview(
                otherTenant, victim.workItem().workItemId(), preview.previewId(), 1, DIGEST_A, DIGEST_B));

        Snapshot after = snapshot(victim.workItem().workItemId(), tenantAttacker.workItem().workItemId(),
                principalAttacker.workItem().workItemId());
        assertEquals(before, after);
        assertEquals(tenantAttacker.workItem().workItemId(), store.findConversationState(otherTenant, conversationId)
                .orElseThrow().focusedWorkItemId());
        assertEquals(principalAttacker.workItem().workItemId(), store.findConversationState(otherPrincipal, conversationId)
                .orElseThrow().focusedWorkItemId());
    }

    private WorkItemCreationResult submit(JdbcWorkbenchStore store,
                                          AuthenticatedPrincipal principal,
                                          String suffix) {
        return new WorkInputService(new WorkItemService(store)).submit(principal,
                SubmitWorkInputCommand.direct("client-" + prefix + "-" + suffix, conversationId,
                        "goal-" + suffix, 0));
    }

    private PreviewFixture seedIncidentPreview(AgentWorkItem work) throws Exception {
        String decisionId = "rdec-" + UUID.randomUUID();
        String previewId = "preview-" + UUID.randomUUID();
        Instant now = Instant.now();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            execute(connection, """
                    INSERT INTO agent_routing_decision(
                        decision_id,work_item_id,routing_request_id,attempt_no,decision_status,
                        target_catalog_version,prompt_tokens,completion_tokens,latency_ms,trace_id,created_at)
                    VALUES(?,?,?,1,'EFFECTIVE','security-gate',0,0,0,?,?)
                    """, decisionId, work.workItemId(), work.routingRequestId(), "trace-" + decisionId, now);
            execute(connection, """
                    UPDATE agent_work_item SET control_state='WAITING_CONFIRMATION',
                        active_execution_target='INCIDENT_INVESTIGATION',route_decision_id=?,version=version+1
                    WHERE work_item_id=? AND tenant_id=? AND owner_principal_id=?
                    """, decisionId, work.workItemId(), work.tenantId(), work.ownerPrincipalId());
            execute(connection, """
                    INSERT INTO agent_route_preview(
                        preview_id,work_item_id,route_decision_id,target_id,preview_version,
                        validated_input_digest,scope_digest,payload_json,status,expires_at,created_at)
                    VALUES(?,?,?,'INCIDENT_INVESTIGATION',1,?,?,'{}'::jsonb,'ACTIVE',?,?)
                    """, previewId, work.workItemId(), decisionId, DIGEST_A, DIGEST_B,
                    now.plusSeconds(600), now);
            connection.commit();
        }
        return new PreviewFixture(previewId);
    }

    private Snapshot snapshot(String... workItemIds) throws Exception {
        String placeholders = String.join(",", java.util.Collections.nCopies(workItemIds.length, "?"));
        try (Connection connection = openConnection()) {
            return new Snapshot(
                    count(connection, "SELECT COALESCE(sum(version),0) FROM agent_work_item WHERE work_item_id IN (" + placeholders + ")", (Object[]) workItemIds),
                    count(connection, "SELECT count(*) FROM agent_work_event WHERE work_item_id IN (" + placeholders + ")", (Object[]) workItemIds),
                    count(connection, "SELECT count(*) FROM agent_work_link WHERE work_item_id IN (" + placeholders + ")", (Object[]) workItemIds),
                    count(connection, "SELECT count(*) FROM agent_work_item WHERE work_item_id IN (" + placeholders + ") AND (active_run_id IS NOT NULL OR active_incident_id IS NOT NULL OR active_recovery_plan_id IS NOT NULL)", (Object[]) workItemIds),
                    count(connection, "SELECT count(*) FROM agent_route_preview WHERE work_item_id IN (" + placeholders + ") AND status='ACTIVE'", (Object[]) workItemIds),
                    count(connection, "SELECT COALESCE(sum(version),0) FROM agent_conversation_work_state WHERE conversation_id=?", conversationId));
        }
    }

    private JdbcWorkbenchStore workbench() {
        return new JdbcWorkbenchStore(properties, objectMapper);
    }

    private JdbcRoutingStore routing() {
        return new JdbcRoutingStore(properties, objectMapper);
    }

    private JdbcDispatchStore dispatch() {
        return new JdbcDispatchStore(properties, objectMapper);
    }

    private AuthenticatedPrincipal principal(String tenantId, String principalId) {
        return new AuthenticatedPrincipal(tenantId, principalId, Set.of("USER", "INCIDENT_OPERATOR"));
    }

    private AgentStorageProperties properties() {
        AgentStorageProperties result = new AgentStorageProperties();
        result.getDatasource().setUrl(environment("AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent"));
        result.getDatasource().setUsername(environment("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        result.getDatasource().setPassword(environment("AGENT_STORAGE_POSTGRES_PASSWORD", "1234"));
        return result;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }

    private long count(String sql, Object... values) {
        try (Connection connection = openConnection()) {
            return count(connection, sql, values);
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long count(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private void bind(PreparedStatement statement, Object... values) throws Exception {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            if (value instanceof Instant instant) {
                statement.setTimestamp(index + 1, java.sql.Timestamp.from(instant));
            }
            else {
                statement.setObject(index + 1, value);
            }
        }
    }

    private record PreviewFixture(String previewId) {
    }

    private record Snapshot(long workVersions,
                            long eventCount,
                            long workLinkCount,
                            long linkedTargetCount,
                            long activePreviewCount,
                            long focusVersions) {
    }
}
