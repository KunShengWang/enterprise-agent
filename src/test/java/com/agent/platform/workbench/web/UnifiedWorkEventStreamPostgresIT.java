package com.agent.platform.workbench.web;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.config.WorkbenchStreamProperties;
import com.agent.platform.runtime.AgentEventDraft;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.runtime.JdbcAgentTimelineStore;
import com.agent.platform.runtime.JdbcAgentRuntimeStore;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRunPhase;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.workbench.application.SubmitWorkInputCommand;
import com.agent.platform.workbench.application.WorkInputService;
import com.agent.platform.workbench.application.WorkItemService;
import com.agent.platform.workbench.model.WorkEventDraft;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.persistence.JdbcWorkbenchStore;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class UnifiedWorkEventStreamPostgresIT {

    private final String suffix = UUID.randomUUID().toString();
    private final AgentStorageProperties storage = storage();
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-m2b-" + suffix, "stream-user", Set.of("USER"));
    private final String sessionId = "session-m2b-" + suffix;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            lockWorkItems(connection);
            execute(connection, "DELETE FROM agent_dispatch_attempt WHERE work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)");
            execute(connection, "DELETE FROM agent_route_preview WHERE work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)");
            execute(connection, "DELETE FROM agent_routing_decision WHERE work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)");
            execute(connection, "DELETE FROM agent_work_command_decision WHERE tenant_id = ?");
            execute(connection, "DELETE FROM agent_work_event WHERE work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)");
            execute(connection, "DELETE FROM agent_work_projection_cursor WHERE work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)");
            execute(connection, "DELETE FROM agent_work_link WHERE work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)");
            execute(connection, "DELETE FROM agent_work_relation WHERE source_work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?) OR target_work_item_id IN "
                    + "(SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)");
            execute(connection, "DELETE FROM agent_conversation_work_state WHERE tenant_id = ?");
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id = ?");
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id = ?");
            executeValue(connection, "DELETE FROM agent_run_state WHERE dispatch_request_id = ?",
                    "dispatch-live-" + suffix);
            executeValue(connection, "DELETE FROM agent_session WHERE session_id = ?", sessionId);
            connection.commit();
        }
    }

    @Test
    void reconnectReplaysEventsWrittenWhileDisconnectedWithoutCreatingDuplicates() {
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(storage, new ObjectMapper());
        var created = new WorkInputService(new WorkItemService(workbench)).submit(
                principal, SubmitWorkInputCommand.direct(
                        "client-m2b-" + suffix, "conversation-m2b-" + suffix, "stream goal", 0));
        String workItemId = created.workItem().workItemId();
        UnifiedWorkEventStreamService firstConnection = stream(workbench);
        AtomicLong workCursor = new AtomicLong(-1);
        AtomicLong runCursor = new AtomicLong(-1);

        var initial = firstConnection.poll(principal, workItemId, workCursor, runCursor, 1);
        assertEquals(List.of(0L), initial.stream().map(UnifiedWorkStreamItem::workSequence).toList());

        workbench.appendLocalEvent(principal, workItemId, event("offline-1", 1));
        workbench.appendLocalEvent(principal, workItemId, event("offline-2", 2));

        UnifiedWorkEventStreamService reconnectedProcess = stream(
                new JdbcWorkbenchStore(storage, new ObjectMapper()));
        var replay = reconnectedProcess.poll(principal, workItemId, workCursor, runCursor, 1);
        assertEquals(List.of(1L, 2L), replay.stream().map(UnifiedWorkStreamItem::workSequence).toList());
        assertEquals(List.of("offline event 1", "offline event 2"),
                replay.stream().map(UnifiedWorkStreamItem::content).toList());
        assertEquals(List.of(), reconnectedProcess.poll(principal, workItemId, workCursor, runCursor, 2));
        assertEquals(3, workbench.loadEvents(principal, workItemId, -1, 100).size());
    }

    @Test
    void crossTenantPrincipalCannotEnterSubscriptionReadPath() {
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(storage, new ObjectMapper());
        var created = new WorkInputService(new WorkItemService(workbench)).submit(
                principal, SubmitWorkInputCommand.direct(
                        "client-isolation-" + suffix, "conversation-isolation-" + suffix, "private goal", 0));
        AuthenticatedPrincipal attacker = new AuthenticatedPrincipal(
                "tenant-attacker-" + suffix, "stream-user", Set.of("USER"));

        assertThrows(WorkbenchNotFoundException.class, () -> stream(workbench).poll(
                attacker, created.workItem().workItemId(),
                new AtomicLong(-1), new AtomicLong(-1), 1));
    }

    @Test
    void persistedChildRunDeltaNeverEntersPrimaryAnswerChannel() throws Exception {
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(storage, new ObjectMapper());
        var created = new WorkInputService(new WorkItemService(workbench)).submit(
                principal, SubmitWorkInputCommand.direct(
                        "client-runs-" + suffix, "conversation-runs-" + suffix, "run stream goal", 0));
        String workItemId = created.workItem().workItemId();
        String primaryRunId = "run-primary-m2b-" + suffix;
        String childRunId = "run-child-m2b-" + suffix;
        insertLink(workItemId, primaryRunId, "PRIMARY", "dispatch-primary-" + suffix);
        insertLink(workItemId, childRunId, "CHILD", "dispatch-child-" + suffix);

        JdbcAgentTimelineStore timeline = new JdbcAgentTimelineStore(storage, new ObjectMapper());
        timeline.openSession(sessionId, principal.principalId());
        timeline.appendEvent(sessionId, principal.principalId(), primaryRunId,
                new AgentEventDraft(AgentEventType.MODEL_DELTA, "primary answer", Map.of()));
        timeline.appendEvent(sessionId, principal.principalId(), childRunId,
                new AgentEventDraft(AgentEventType.MODEL_DELTA, "child secret", Map.of()));
        UnifiedWorkEventStreamService service = new UnifiedWorkEventStreamService(
                workbench, timeline, new com.agent.platform.runtime.JdbcAgentRuntimeStore(storage, new ObjectMapper()),
                new WorkbenchStreamProperties());

        var items = service.poll(principal, workItemId,
                new AtomicLong(0), new AtomicLong(-1), 1);

        assertEquals(List.of("primary answer"), items.stream()
                .filter(item -> "MODEL_DELTA".equals(item.kind()))
                .map(UnifiedWorkStreamItem::content).toList());
    }

    @Test
    void discoversPersistedRunningRunBeforePrimaryLinkAndReconnectsWithoutDuplicateDelta() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(storage, mapper);
        var created = new WorkInputService(new WorkItemService(workbench)).submit(
                principal, SubmitWorkInputCommand.direct(
                        "client-live-" + suffix, "conversation-live-" + suffix, "live stream goal", 0));
        String workItemId = created.workItem().workItemId();
        String dispatchRequestId = "dispatch-live-" + suffix;
        String runId = "run-live-" + suffix;
        updateWorkForDispatch(workItemId, dispatchRequestId);

        JdbcAgentRuntimeStore runs = new JdbcAgentRuntimeStore(storage, mapper);
        AgentRequest request = new AgentRequest(created.workItem().conversationId(), principal.principalId(),
                "live stream goal", Map.of("workItemId", workItemId,
                AgentRunStore.DISPATCH_REQUEST_METADATA_KEY, dispatchRequestId));
        runs.create(AgentRunRecord.create(runId, "trace-live-" + suffix,
                created.workItem().conversationId(), request));
        JdbcAgentTimelineStore timeline = new JdbcAgentTimelineStore(storage, mapper);
        timeline.openSession(sessionId, principal.principalId());
        timeline.appendEvent(sessionId, principal.principalId(), runId,
                new AgentEventDraft(AgentEventType.MODEL_DELTA, "real ", Map.of()));
        timeline.appendEvent(sessionId, principal.principalId(), runId,
                new AgentEventDraft(AgentEventType.MODEL_DELTA, "time", Map.of()));
        UnifiedWorkEventStreamService service = new UnifiedWorkEventStreamService(
                workbench, timeline, runs, new WorkbenchStreamProperties());
        AtomicLong runCursor = new AtomicLong(-1);

        var live = service.poll(principal, workItemId, new AtomicLong(0), runCursor, 1);
        String liveBuffer = live.stream().filter(item -> "MODEL_DELTA".equals(item.kind()))
                .map(UnifiedWorkStreamItem::content).reduce("", String::concat);
        assertEquals(AgentRunState.RUNNING, runs.find(runId).orElseThrow().state());
        assertEquals("real time", liveBuffer);
        assertEquals(List.of(), service.poll(principal, workItemId, new AtomicLong(0), runCursor, 2));

        AgentRunRecord completed = runs.update(runId, current -> current.finished(
                AgentRunState.COMPLETED, AgentRunPhase.FINISHED, liveBuffer, "",
                List.of(), List.of(), false, false));
        assertEquals(completed.answer(), liveBuffer);
    }

    private UnifiedWorkEventStreamService stream(JdbcWorkbenchStore workbench) {
        return new UnifiedWorkEventStreamService(
                workbench, mock(AgentTimelineStore.class), mock(com.agent.platform.runtime.AgentRunStore.class),
                new WorkbenchStreamProperties());
    }

    private WorkEventDraft event(String sourceEventId, int index) {
        return new WorkEventDraft(sourceEventId, WorkEventType.ROUTING_STARTED, "STREAM_TEST",
                "offline event " + index, Map.of("index", index), "m2b-test");
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

    private void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 1; index <= countParameters(sql); index++) {
                statement.setString(index, principal.tenantId());
            }
            statement.executeUpdate();
        }
    }

    private int countParameters(String sql) {
        return (int) sql.chars().filter(character -> character == '?').count();
    }

    private void lockWorkItems(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT work_item_id FROM agent_work_item WHERE tenant_id=? FOR UPDATE")) {
            statement.setString(1, principal.tenantId());
            statement.executeQuery();
        }
    }

    private void executeValue(Connection connection, String sql, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private void insertLink(String workItemId, String runId, String relation, String dispatchRequestId)
            throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_work_link(
                         work_item_id, dispatch_request_id, link_type, linked_id, relation, created_at
                     ) VALUES (?, ?, 'RUN', ?, ?, ?)
                     """)) {
            statement.setString(1, workItemId);
            statement.setString(2, dispatchRequestId);
            statement.setString(3, runId);
            statement.setString(4, relation);
            statement.setTimestamp(5, java.sql.Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private void updateWorkForDispatch(String workItemId, String dispatchRequestId) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_work_item
                     SET control_state='DISPATCHING', execution_state='STARTING', dispatch_request_id=?
                     WHERE work_item_id=?
                     """)) {
            statement.setString(1, dispatchRequestId);
            statement.setString(2, workItemId);
            statement.executeUpdate();
        }
    }
}
