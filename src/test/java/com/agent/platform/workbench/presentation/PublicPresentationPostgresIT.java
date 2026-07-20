package com.agent.platform.workbench.presentation;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.config.WorkbenchStreamProperties;
import com.agent.platform.runtime.AgentCapabilityRegistry;
import com.agent.platform.workbench.application.SubmitWorkInputCommand;
import com.agent.platform.workbench.application.WorkInputService;
import com.agent.platform.workbench.application.WorkItemService;
import com.agent.platform.workbench.model.WorkEventDraft;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.persistence.JdbcWorkbenchStore;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class PublicPresentationPostgresIT {

    private final String suffix = UUID.randomUUID().toString();
    private final AgentStorageProperties storage = storage();
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-p2-" + suffix, "alice", Set.of("USER"));

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            lockWorkItems(connection);
            execute(connection, "DELETE FROM agent_dispatch_attempt WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)");
            execute(connection, "DELETE FROM agent_route_preview WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)");
            execute(connection, "DELETE FROM agent_routing_decision WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)");
            execute(connection, "DELETE FROM agent_work_command_decision WHERE tenant_id LIKE ?");
            execute(connection, "DELETE FROM agent_work_event WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)");
            execute(connection, "DELETE FROM agent_work_projection_cursor WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)");
            execute(connection, "DELETE FROM agent_work_link WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)");
            execute(connection, "DELETE FROM agent_work_relation WHERE source_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?) OR target_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)");
            execute(connection, "DELETE FROM agent_conversation_work_state WHERE tenant_id LIKE ?");
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id LIKE ?");
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id LIKE ?");
            connection.commit();
        }
    }

    @Test
    void persistedHistoryReplayAndStreamShareDtoAndEnforceOwnership() {
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(storage, new ObjectMapper());
        var created = new WorkInputService(new WorkItemService(workbench)).submit(
                principal, SubmitWorkInputCommand.direct(
                        "client-p2-" + suffix, "conversation-p2-" + suffix, "presentation goal", 0));
        String workItemId = created.workItem().workItemId();
        workbench.appendLocalEvent(principal, workItemId, new WorkEventDraft(
                "pause-p2-" + suffix, WorkEventType.WORK_ITEM_PAUSED, "PAUSED",
                "internal pause summary", Map.of("ownerId", "node-secret", "fencingToken", 7), "p2-test"));
        PublicPresentationService service = new PublicPresentationService(
                workbench, mock(RoutingStore.class), mock(AgentCapabilityRegistry.class),
                new PublicExecutionCatalog());

        List<PublicPresentation> history = service.publicTimeline(principal, workItemId, -1, 100);
        PublicPresentation paused = history.stream()
                .filter(item -> item.kind() == PublicPresentationKind.WAITING_FOR_USER).findFirst().orElseThrow();
        assertFalseLeak(paused.toString());
        assertEquals(List.of(), service.publicTimeline(principal, workItemId, paused.sequence(), 100));
        assertEquals(history.size(), history.stream().map(PublicPresentation::presentationId).distinct().count());

        PublicPresentationStreamService stream = new PublicPresentationStreamService(service, new WorkbenchStreamProperties());
        List<PublicPresentation> live = stream.poll(principal, workItemId, new AtomicLong(-1));
        assertEquals(history, live);
        AuthenticatedPrincipal attacker = new AuthenticatedPrincipal("tenant-other", "alice", Set.of("USER"));
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.publicTimeline(attacker, workItemId, -1, 100));
    }

    private void assertFalseLeak(String value) {
        assertTrue(!value.contains("node-secret") && !value.contains("fencingToken"));
    }

    private AgentStorageProperties storage() {
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
        return DriverManager.getConnection(storage.getDatasource().getUrl(),
                storage.getDatasource().getUsername(), storage.getDatasource().getPassword());
    }

    private void lockWorkItems(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ? FOR UPDATE")) {
            statement.setString(1, "tenant-p2-%");
            statement.executeQuery();
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameters = (int) sql.chars().filter(character -> character == '?').count();
            for (int index = 1; index <= parameters; index++) statement.setString(index, "tenant-p2-%");
            statement.executeUpdate();
        }
    }
}
