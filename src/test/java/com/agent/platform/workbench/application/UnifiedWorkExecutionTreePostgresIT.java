package com.agent.platform.workbench.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.runtime.AgentEventDraft;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentRunPhase;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.JdbcAgentRuntimeStore;
import com.agent.platform.runtime.JdbcAgentTimelineStore;
import com.agent.platform.trace.RuntimeTraceProjector;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.persistence.JdbcWorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class UnifiedWorkExecutionTreePostgresIT {

    private final String suffix = UUID.randomUUID().toString();
    private final AgentStorageProperties storage = storage();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-m2c-" + suffix, "tree-user", Set.of("USER", "INCIDENT_OPERATOR"));
    private final String incidentId = "incident-m2c-" + suffix;
    private final String taskId = "task-m2c-" + suffix;
    private final String sessionId = "session-m2c-" + suffix;
    private final String commanderRunId = "run-commander-m2c-" + suffix;
    private final String firstRunId = "run-specialist-1-m2c-" + suffix;
    private final String secondRunId = "run-specialist-2-m2c-" + suffix;
    private final String reviewerRunId = "run-reviewer-m2c-" + suffix;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            execute(connection, "DELETE FROM agent_work_event WHERE work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id=?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_projection_cursor WHERE work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id=?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_link WHERE work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id=?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_conversation_work_state WHERE tenant_id=?", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id=?", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id=?", principal.tenantId());
            execute(connection, "DELETE FROM agent_session WHERE session_id=?", sessionId);
            for (String runId : List.of(commanderRunId, firstRunId, secondRunId, reviewerRunId)) {
                execute(connection, "DELETE FROM agent_run_state WHERE run_id=?", runId);
            }
        }
    }

    @Test
    void readsRetiredLinkAndPreservedRunTraceWithoutAnyDomainStore() throws Exception {
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(storage, objectMapper);
        var created = new WorkInputService(new WorkItemService(workbench)).submit(
                principal, SubmitWorkInputCommand.direct(
                        "client-m2c-" + suffix, "conversation-m2c-" + suffix, "investigate incident", 0));
        String workItemId = created.workItem().workItemId();
        insertWorkLink(workItemId);

        JdbcAgentRuntimeStore runStore = new JdbcAgentRuntimeStore(storage, objectMapper);
        JdbcAgentTimelineStore timeline = new JdbcAgentTimelineStore(storage, objectMapper);
        timeline.openSession(sessionId, principal.principalId());
        persistedRun(runStore, timeline, commanderRunId, 1);
        persistedRun(runStore, timeline, firstRunId, 1);
        persistedRun(runStore, timeline, secondRunId, 2);
        persistedRun(runStore, timeline, reviewerRunId, 1);
        RuntimeTraceProjector runtimeTraces = new RuntimeTraceProjector(runStore, timeline);
        UnifiedWorkExecutionTreeService service = new UnifiedWorkExecutionTreeService(
                workbench, runtimeTraces, List.of());

        var tree = service.project(principal, workItemId);

        assertEquals("RETIRED", tree.treeType());
        assertEquals(true, tree.assessment().get("readOnly"));
        assertEquals("RUNNING", tree.assessment().get("historicalExecutionState"));
        assertTrue(tree.agents().isEmpty());
        assertEquals(com.agent.platform.workbench.model.WorkExecutionState.RUNNING,
                workbench.findWorkItem(principal, workItemId).orElseThrow().executionState());
        assertEquals(2L, ((Number) runtimeTraces.project(secondRunId).orElseThrow().metrics().get("modelCalls")).longValue());
        var foreign = new AuthenticatedPrincipal("other-tenant", "tree-user", Set.of("USER"));
        org.junit.jupiter.api.Assertions.assertThrows(com.agent.platform.workbench.persistence.WorkbenchNotFoundException.class,
                () -> service.project(foreign, workItemId));
    }

    private void persistedRun(JdbcAgentRuntimeStore runStore,
                              JdbcAgentTimelineStore timeline,
                              String runId,
                              int modelCalls) {
        AgentRunRecord record = AgentRunRecord.create(
                runId, "trace-" + runId, sessionId,
                new AgentRequest(sessionId, principal.principalId(), "task for " + runId, Map.of()));
        runStore.create(record);
        timeline.appendEvent(sessionId, principal.principalId(), runId,
                new AgentEventDraft(AgentEventType.RUN_STARTED, "started", Map.of()));
        for (int index = 0; index < modelCalls; index++) {
            timeline.appendEvent(sessionId, principal.principalId(), runId,
                    new AgentEventDraft(AgentEventType.MODEL_STARTED, "model started", Map.of()));
            timeline.appendEvent(sessionId, principal.principalId(), runId,
                    new AgentEventDraft(AgentEventType.MODEL_COMPLETED, "model completed",
                            Map.of("promptTokens", 10, "completionTokens", 5)));
        }
        timeline.appendEvent(sessionId, principal.principalId(), runId,
                new AgentEventDraft(AgentEventType.RUN_COMPLETED, "completed", Map.of()));
        runStore.update(runId, current -> current.finished(
                AgentRunState.COMPLETED, AgentRunPhase.FINISHED, "done", "",
                List.of(), List.of(), false, false));
    }

    private void insertWorkLink(String workItemId) throws Exception {
        try (Connection connection = openConnection()) {
            execute(connection, """
                    INSERT INTO agent_work_link(
                        work_item_id, dispatch_request_id, link_type, linked_id, relation, created_at
                    ) VALUES (?, ?, ?, ?, 'PRIMARY', ?)
                    """, workItemId, "dispatch-m2c-" + suffix, WorkLinkType.INCIDENT.name(),
                    incidentId, java.sql.Timestamp.from(Instant.now()));
            execute(connection, """
                    UPDATE agent_work_item SET active_execution_target='INCIDENT_INVESTIGATION',
                        active_incident_id=?, control_state='DISPATCHED', execution_state='RUNNING'
                    WHERE work_item_id=?
                    """, incidentId, workItemId);
        }
    }

    private AgentStorageProperties storage() {
        AgentStorageProperties result = new AgentStorageProperties();
        result.getDatasource().setUrl(environment(
                "AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent"));
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

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
}
