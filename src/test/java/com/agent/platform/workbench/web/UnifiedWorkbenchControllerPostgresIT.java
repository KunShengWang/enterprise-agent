package com.agent.platform.workbench.web;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.workbench.application.CommandClassifierResult;
import com.agent.platform.workbench.application.ConversationFocusService;
import com.agent.platform.workbench.application.RouteConfirmationService;
import com.agent.platform.workbench.application.UnifiedWorkIntakeService;
import com.agent.platform.workbench.application.UnifiedWorkLauncher;
import com.agent.platform.workbench.application.UnifiedWorkQueryService;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.WorkCommandClassification;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.persistence.JdbcDispatchStore;
import com.agent.platform.workbench.persistence.JdbcRoutingStore;
import com.agent.platform.workbench.persistence.JdbcWorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class UnifiedWorkbenchControllerPostgresIT {
    private final AgentStorageProperties storage = storage();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String suffix = UUID.randomUUID().toString();
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-m1d-" + suffix, "alice", Set.of("USER", "INCIDENT_OPERATOR"));

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "DELETE FROM agent_route_preview WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1d-%");
            execute(connection, "DELETE FROM agent_dispatch_attempt WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1d-%");
            execute(connection, "DELETE FROM agent_routing_decision WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1d-%");
            execute(connection, "DELETE FROM agent_work_command_decision WHERE tenant_id LIKE ?", "tenant-m1d-%");
            execute(connection, "DELETE FROM agent_work_event WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1d-%");
            execute(connection, "DELETE FROM agent_work_link WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1d-%");
            execute(connection, "DELETE FROM agent_work_relation WHERE source_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?) OR target_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1d-%", "tenant-m1d-%");
            execute(connection, "DELETE FROM agent_conversation_work_state WHERE tenant_id LIKE ?", "tenant-m1d-%");
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id LIKE ?", "tenant-m1d-%");
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id LIKE ?", "tenant-m1d-%");
            connection.commit();
        }
    }

    @Test
    void unifiedInputPersistsBeforeLaunchAndCanBeQueriedThroughOwnedViews() {
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(storage, objectMapper);
        JdbcRoutingStore routing = new JdbcRoutingStore(storage, objectMapper);
        JdbcDispatchStore dispatch = new JdbcDispatchStore(storage, objectMapper);
        UnifiedWorkLauncher launcher = mock(UnifiedWorkLauncher.class);
        UnifiedWorkIntakeService intake = new UnifiedWorkIntakeService(routing, workbench, request ->
                new CommandClassifierResult(
                        new WorkCommandClassification(WorkCommandType.NORMAL_GOAL, 0.91,
                                "new goal", "", ""),
                        ClassifierType.MODEL, "m1d-test", "prompt", "output", "{}",
                        0, 0, 1, "trace-m1d"));
        UnifiedWorkQueryService queries = new UnifiedWorkQueryService(workbench, routing, dispatch);
        UnifiedWorkController controller = new UnifiedWorkController(
                () -> principal, intake, launcher, queries,
                mock(RouteConfirmationService.class), new ConversationFocusService(workbench),
                workbench, routing);
        WebTestClient client = WebTestClient.bindToController(controller).build();
        String conversationId = "conversation-m1d-" + suffix;

        client.post()
                .uri("/api/agent/conversations/{conversationId}/inputs", conversationId)
                .header("Idempotency-Key", "client-m1d-" + suffix)
                .bodyValue(new UnifiedWorkController.UnifiedInputBody(
                        "解释当前项目的 Agent Runtime", java.util.Map.of("uiSource", "test")))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.commandOnly").isEqualTo(false)
                .jsonPath("$.data.workItemId").value(value -> org.junit.jupiter.api.Assertions.assertFalse(String.valueOf(value).isBlank()))
                .returnResult();
        String workItemId = workbench.listWorkItems(principal, conversationId, 10).get(0).workItemId();

        verify(launcher).routeAndDispatch(principal, workItemId,
                workbench.findWorkItem(principal, workItemId).orElseThrow().routingRequestId());
        client.get().uri("/api/agent/conversations/{conversationId}/inputs", conversationId)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(1);
        client.get().uri("/api/agent/conversations/{conversationId}/work-items", conversationId)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(1);
        client.get().uri("/api/agent/conversations/{conversationId}/focus", conversationId)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.focusedWorkItemId").isEqualTo(workItemId);
        client.get().uri("/api/agent/work-items/{workItemId}", workItemId)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.workItem.sourceInputId").exists()
                .jsonPath("$.data.events[0].eventType").isEqualTo("WORK_ITEM_CREATED");
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

    private void execute(Connection connection, String sql, String... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setString(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
}
