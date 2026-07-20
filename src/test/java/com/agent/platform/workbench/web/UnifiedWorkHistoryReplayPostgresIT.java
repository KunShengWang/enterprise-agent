package com.agent.platform.workbench.web;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.config.WorkbenchStreamProperties;
import com.agent.platform.ordercare.incident.application.IncidentTraceProjector;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.runtime.AgentEventDraft;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentRunPhase;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.JdbcAgentRuntimeStore;
import com.agent.platform.runtime.JdbcAgentTimelineStore;
import com.agent.platform.trace.RuntimeTraceProjector;
import com.agent.platform.workbench.application.SubmitWorkInputCommand;
import com.agent.platform.workbench.application.UnifiedWorkExecutionTreeService;
import com.agent.platform.workbench.application.WorkInputService;
import com.agent.platform.workbench.application.WorkItemService;
import com.agent.platform.workbench.persistence.JdbcWorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class UnifiedWorkHistoryReplayPostgresIT {

    private static final int LAST_WORK_SEQUENCE = 520;
    private static final int FIRST_PAGE_LAST_SEQUENCE = 499;
    private static final int MODEL_DELTA_COUNT = 25;

    private final String suffix = UUID.randomUUID().toString();
    private final AgentStorageProperties storage = storage();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-m2d-" + suffix, "history-user", Set.of("USER"));
    private final String sessionId = "session-m2d-" + suffix;
    private final String runId = "run-m2d-" + suffix;

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
            execute(connection, "DELETE FROM agent_run_state WHERE run_id=?", runId);
        }
    }

    @Test
    void restartRebuildsPagedWorkEventsMainAnswerAndExecutionTreeWithoutDuplicateTargets() throws Exception {
        JdbcWorkbenchStore firstWorkbench = new JdbcWorkbenchStore(storage, objectMapper);
        var created = new WorkInputService(new WorkItemService(firstWorkbench)).submit(
                principal, SubmitWorkInputCommand.direct(
                        "client-m2d-" + suffix, "conversation-m2d-" + suffix, "historical goal", 0));
        String workItemId = created.workItem().workItemId();
        insertHistoricalEventsAndRunLink(workItemId);
        persistRunTimeline();

        assertEquals(500, firstWorkbench.loadEvents(principal, workItemId, -1, 500).size());
        assertEquals(List.of(500L, 501L, 502L, 503L, 504L, 505L, 506L, 507L, 508L, 509L,
                        510L, 511L, 512L, 513L, 514L, 515L, 516L, 517L, 518L, 519L, 520L),
                firstWorkbench.loadEvents(principal, workItemId, FIRST_PAGE_LAST_SEQUENCE, 500)
                        .stream().map(event -> event.sequence()).toList());

        JdbcWorkbenchStore restartedWorkbench = new JdbcWorkbenchStore(storage, new ObjectMapper());
        JdbcAgentRuntimeStore restartedRunStore = new JdbcAgentRuntimeStore(storage, new ObjectMapper());
        JdbcAgentTimelineStore restartedTimeline = new JdbcAgentTimelineStore(storage, new ObjectMapper());
        WorkbenchStreamProperties streamProperties = new WorkbenchStreamProperties();
        streamProperties.setPollIntervalMillis(100);
        streamProperties.setBatchSize(1000);
        UnifiedWorkEventStreamService restartedStream = new UnifiedWorkEventStreamService(
                restartedWorkbench, restartedTimeline, restartedRunStore, streamProperties);

        List<UnifiedWorkStreamItem> firstReplay = restartedStream.stream(
                        principal, workItemId,
                        new UnifiedWorkStreamCursor(FIRST_PAGE_LAST_SEQUENCE, -1))
                .map(event -> event.data()).take(21 + MODEL_DELTA_COUNT)
                .collectList().block(Duration.ofSeconds(5));
        assertNotNull(firstReplay);
        assertEquals(21, firstReplay.stream().filter(item -> "WORK_EVENT".equals(item.kind())).count(),
                String.valueOf(firstReplay));
        assertEquals(MODEL_DELTA_COUNT,
                firstReplay.stream().filter(item -> "MODEL_DELTA".equals(item.kind())).count());
        assertEquals(firstReplay.size(), new HashSet<>(firstReplay.stream()
                .map(item -> item.kind() + ":" + item.eventId()).toList()).size());
        String expectedAnswer = expectedAnswer();
        assertEquals(expectedAnswer, firstReplay.stream()
                .filter(item -> "MODEL_DELTA".equals(item.kind()))
                .map(UnifiedWorkStreamItem::content).reduce("", String::concat));

        UnifiedWorkEventStreamService secondRestart = new UnifiedWorkEventStreamService(
                new JdbcWorkbenchStore(storage, new ObjectMapper()),
                new JdbcAgentTimelineStore(storage, new ObjectMapper()),
                new JdbcAgentRuntimeStore(storage, new ObjectMapper()), streamProperties);
        List<UnifiedWorkStreamItem> secondReplay = secondRestart.stream(
                        principal, workItemId,
                        new UnifiedWorkStreamCursor(FIRST_PAGE_LAST_SEQUENCE, -1))
                .map(event -> event.data()).take(21 + MODEL_DELTA_COUNT)
                .collectList().block(Duration.ofSeconds(5));
        assertNotNull(secondReplay);
        assertEquals(firstReplay.stream().map(UnifiedWorkStreamItem::eventId).toList(),
                secondReplay.stream().map(UnifiedWorkStreamItem::eventId).toList());
        assertEquals(expectedAnswer, secondReplay.stream()
                .filter(item -> "MODEL_DELTA".equals(item.kind()))
                .map(UnifiedWorkStreamItem::content).reduce("", String::concat));

        RuntimeTraceProjector runtimeTraces = new RuntimeTraceProjector(restartedRunStore, restartedTimeline);
        UnifiedWorkExecutionTreeService treeService = new UnifiedWorkExecutionTreeService(
                restartedWorkbench, mock(IncidentStore.class), mock(IncidentTraceProjector.class),
                mock(IncidentRecoveryPlanStore.class), runtimeTraces);
        var tree = treeService.project(principal, workItemId);
        assertEquals("SINGLE_AGENT", tree.treeType());
        assertEquals(runId, tree.agents().get(0).runId());
        assertNotNull(tree.agents().get(0).trace());

        assertEquals(1, count("SELECT count(*) FROM agent_work_item WHERE tenant_id=?", principal.tenantId()));
        assertEquals(1, count("SELECT count(*) FROM agent_run_state WHERE run_id=?", runId));
        assertEquals(LAST_WORK_SEQUENCE + 1,
                count("SELECT count(*) FROM agent_work_event WHERE work_item_id=?", workItemId));
    }

    private void persistRunTimeline() {
        JdbcAgentRuntimeStore runStore = new JdbcAgentRuntimeStore(storage, objectMapper);
        JdbcAgentTimelineStore timeline = new JdbcAgentTimelineStore(storage, objectMapper);
        timeline.openSession(sessionId, principal.principalId());
        runStore.create(AgentRunRecord.create(
                runId, "trace-" + runId, sessionId,
                new AgentRequest(sessionId, principal.principalId(), "historical answer", Map.of())));
        timeline.appendEvent(sessionId, principal.principalId(), runId,
                new AgentEventDraft(AgentEventType.RUN_STARTED, "started", Map.of()));
        for (int index = 0; index < MODEL_DELTA_COUNT; index++) {
            timeline.appendEvent(sessionId, principal.principalId(), runId,
                    new AgentEventDraft(AgentEventType.MODEL_DELTA,
                            "chunk-" + String.format("%02d", index) + "|", Map.of()));
        }
        timeline.appendEvent(sessionId, principal.principalId(), runId,
                new AgentEventDraft(AgentEventType.RUN_COMPLETED, "completed", Map.of()));
        runStore.update(runId, current -> current.finished(
                AgentRunState.COMPLETED, AgentRunPhase.FINISHED, expectedAnswer(), "",
                List.of(), List.of(), false, false));
    }

    private String expectedAnswer() {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < MODEL_DELTA_COUNT; index++) {
            result.append("chunk-").append(String.format("%02d", index)).append('|');
        }
        return result.toString();
    }

    private void insertHistoricalEventsAndRunLink(String workItemId) throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_work_event(
                        event_id, work_item_id, sequence, source_type, source_id, source_event_id,
                        source_sequence, event_type, phase, summary, payload, correlation_id,
                        causation_id, source_created_at, projected_at
                    ) VALUES (?, ?, ?, 'WORK_ITEM', ?, ?, ?, 'RUN_EVENT_PROJECTED',
                        'HISTORY', ?, '{}'::jsonb, ?, 'm2d-fixture', ?, ?)
                    """)) {
                Instant now = Instant.now();
                for (int sequence = 1; sequence <= LAST_WORK_SEQUENCE; sequence++) {
                    statement.setString(1, "event-m2d-" + sequence + "-" + suffix);
                    statement.setString(2, workItemId);
                    statement.setLong(3, sequence);
                    statement.setString(4, workItemId);
                    statement.setString(5, "source-m2d-" + sequence + "-" + suffix);
                    statement.setLong(6, sequence);
                    statement.setString(7, "historical event " + sequence);
                    statement.setString(8, workItemId);
                    statement.setTimestamp(9, java.sql.Timestamp.from(now.plusMillis(sequence)));
                    statement.setTimestamp(10, java.sql.Timestamp.from(now.plusMillis(sequence)));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            execute(connection, """
                    INSERT INTO agent_work_link(
                        work_item_id, dispatch_request_id, link_type, linked_id, relation, created_at
                    ) VALUES (?, ?, 'RUN', ?, 'PRIMARY', ?)
                    """, workItemId, "dispatch-m2d-" + suffix, runId,
                    java.sql.Timestamp.from(Instant.now()));
            execute(connection, """
                    UPDATE agent_work_item SET next_event_sequence=?,
                        active_execution_target='GENERAL_AGENT', active_run_id=?,
                        control_state='DISPATCHED', execution_state='COMPLETED'
                    WHERE work_item_id=?
                    """, LAST_WORK_SEQUENCE + 1, runId, workItemId);
            connection.commit();
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

    private long count(String sql, String value) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }
}
