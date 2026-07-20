package com.agent.platform.workbench.application;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.WorkCommandClassification;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.persistence.JdbcRoutingStore;
import com.agent.platform.workbench.persistence.JdbcWorkCommandExecutionStore;
import com.agent.platform.workbench.persistence.JdbcWorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionCommandCapabilityRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkCommandHandlerPostgresIT {

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-m3a-" + suffix, "alice", Set.of("USER"));
    private final AgentStorageProperties properties = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcWorkbenchStore workbench;
    private JdbcRoutingStore routing;
    private JdbcWorkCommandExecutionStore commandStore;
    private AgentRunWorkCommandAdapter runCommands;
    private WorkCommandHandler handler;

    @BeforeEach
    void setUp() {
        workbench = new JdbcWorkbenchStore(properties, objectMapper);
        routing = new JdbcRoutingStore(properties, objectMapper);
        commandStore = new JdbcWorkCommandExecutionStore(properties, objectMapper);
        runCommands = mock(AgentRunWorkCommandAdapter.class);
        handler = new WorkCommandHandler(workbench, commandStore,
                new ExecutionCommandCapabilityRegistry(), runCommands);
    }

    @AfterEach
    void clean() throws SQLException {
        try (Connection connection = openConnection()) {
            execute(connection, "DELETE FROM agent_work_command_execution WHERE tenant_id=?", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_command_decision WHERE tenant_id=?", principal.tenantId());
            execute(connection, "DELETE FROM agent_routing_decision WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id=?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_event WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id=?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_link WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id=?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_relation WHERE source_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id=?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_conversation_work_state WHERE tenant_id=?", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id=?", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id=?", principal.tenantId());
        }
    }

    @Test
    void incidentPauseIsRejectedWithoutChangingUnderlyingOrWorkExecutionState() throws Exception {
        AgentWorkItem incident = createWork("incident");
        setTarget(incident.workItemId(), "INCIDENT_INVESTIGATION", "", "incident-1");
        AgentWorkItem before = workbench.findWorkItem(principal, incident.workItemId()).orElseThrow();
        UnifiedWorkIntakeResult command = command(before.conversationId(), WorkCommandType.PAUSE_ACTIVE_WORK, "pause-incident");

        WorkCommandResult result = handler.handle(principal, new WorkCommandRequest(
                command.input(), command.commandDecision(), "", before.version()));

        assertFalse(result.success());
        assertEquals("UNSUPPORTED_FOR_TARGET", result.code());
        assertFalse(result.underlyingExecutionChanged());
        AgentWorkItem after = workbench.findWorkItem(principal, incident.workItemId()).orElseThrow();
        assertEquals(before.version(), after.version());
        assertEquals(before.controlState(), after.controlState());
        assertEquals(before.executionState(), after.executionState());
        verify(runCommands, never()).execute(any(), any(), any());
        assertEquals(1, workbench.loadEvents(principal, incident.workItemId(), -1, 100).stream()
                .filter(event -> event.eventType() == WorkEventType.WORK_COMMAND_REJECTED).count());
    }

    @Test
    void repeatedAbandonReturnsTheOriginalResultAndAdvancesVersionOnlyOnce() throws Exception {
        AgentWorkItem work = createWork("abandon");
        setTarget(work.workItemId(), "INCIDENT_INVESTIGATION", "", "incident-2");
        AgentWorkItem admitted = workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        UnifiedWorkIntakeResult command = command(admitted.conversationId(), WorkCommandType.ABANDON_ACTIVE_WORK, "abandon-once");
        WorkCommandRequest request = new WorkCommandRequest(
                command.input(), command.commandDecision(), admitted.workItemId(), admitted.version());

        WorkCommandResult first = handler.handle(principal, request);
        WorkCommandResult duplicate = handler.handle(principal, request);

        assertEquals(true, first.success());
        assertEquals(first.commandRequestId(), duplicate.commandRequestId());
        assertEquals(WorkControlState.ABANDONED, duplicate.workItem().controlState());
        assertEquals(admitted.version() + 1, duplicate.workItem().version());
        assertEquals(1, count("SELECT COUNT(*) FROM agent_work_command_execution WHERE input_id=?", command.input().inputId()));
        assertEquals(1, workbench.loadEvents(principal, admitted.workItemId(), -1, 100).stream()
                .filter(event -> event.eventType() == WorkEventType.WORK_ITEM_ABANDONED).count());
    }

    @Test
    void staleWorkVersionFailsBeforeRuntimeAndDoesNotCreateCommandExecution() throws Exception {
        AgentWorkItem work = createWork("stale");
        setTarget(work.workItemId(), "GENERAL_AGENT", "run-stale", "");
        AgentWorkItem admitted = workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        UnifiedWorkIntakeResult command = command(admitted.conversationId(), WorkCommandType.PAUSE_ACTIVE_WORK, "pause-stale");

        WorkCommandResult result = handler.handle(principal, new WorkCommandRequest(
                command.input(), command.commandDecision(), admitted.workItemId(), admitted.version() + 5));

        assertEquals("COMMAND_CAS_CONFLICT", result.code());
        assertEquals(0, count("SELECT COUNT(*) FROM agent_work_command_execution WHERE input_id=?", command.input().inputId()));
        verify(runCommands, never()).execute(any(), any(), any());
    }

    @Test
    void routingWorkWithoutExecutionTargetCanStillBeAbandoned() throws Exception {
        AgentWorkItem routingWork = createWork("abandon-routing");
        UnifiedWorkIntakeResult command = command(routingWork.conversationId(),
                WorkCommandType.ABANDON_ACTIVE_WORK, "abandon-routing");

        WorkCommandResult result = handler.handle(principal, new WorkCommandRequest(
                command.input(), command.commandDecision(), routingWork.workItemId(), routingWork.version()));

        assertEquals(true, result.success());
        assertEquals(WorkControlState.ABANDONED, result.workItem().controlState());
        assertEquals(false, result.underlyingExecutionChanged());
        verify(runCommands, never()).execute(any(), any(), any());
    }

    @Test
    void missingFocusResultIsPersistedAndReplayedWithoutCreatingAWorkItem() throws Exception {
        String conversation = "conversation-m3a-no-focus-" + suffix;
        UnifiedWorkIntakeResult command = command(conversation,
                WorkCommandType.RESUME_ACTIVE_WORK, "resume-no-focus");

        WorkCommandResult first = handler.handle(principal, new WorkCommandRequest(
                command.input(), command.commandDecision(), "", null));
        WorkCommandResult duplicate = handler.handle(principal, new WorkCommandRequest(
                command.input(), command.commandDecision(), "", null));

        assertEquals("FOCUS_NOT_FOUND", first.code());
        assertEquals(first.commandRequestId(), duplicate.commandRequestId());
        assertEquals("", first.workItemId());
        assertEquals(0, workbench.listWorkItems(principal, conversation, 10).size());
        assertEquals(1, count("SELECT COUNT(*) FROM agent_work_command_execution WHERE input_id=?",
                command.input().inputId()));
        verify(runCommands, never()).execute(any(), any(), any());
    }

    @Test
    void naturalLanguageCommandUsesFocusedWorkEvenWhenAnotherWorkIsRunning() throws Exception {
        String conversation = "conversation-m3a-focus-" + suffix;
        AgentWorkItem first = createWork("first", conversation);
        AgentWorkItem second = createWork("second", conversation);
        assertNotEquals(first.workItemId(), second.workItemId());
        setTarget(first.workItemId(), "INCIDENT_INVESTIGATION", "", "incident-first");
        setTarget(second.workItemId(), "INCIDENT_INVESTIGATION", "", "incident-second");
        UnifiedWorkIntakeResult command = command(conversation, WorkCommandType.ABANDON_ACTIVE_WORK, "abandon-focus");

        WorkCommandResult result = handler.handle(principal, new WorkCommandRequest(
                command.input(), command.commandDecision(), "", null));
        var focus = workbench.findConversationState(principal, conversation).orElseThrow();
        workbench.switchFocus(principal, conversation, first.workItemId(), focus.version());
        WorkCommandResult duplicateAfterFocusSwitch = handler.handle(principal, new WorkCommandRequest(
                command.input(), command.commandDecision(), "", null));

        assertEquals(second.workItemId(), result.workItemId());
        assertEquals(second.workItemId(), duplicateAfterFocusSwitch.workItemId());
        assertEquals(result.commandRequestId(), duplicateAfterFocusSwitch.commandRequestId());
        assertEquals(WorkControlState.ABANDONED,
                workbench.findWorkItem(principal, second.workItemId()).orElseThrow().controlState());
        assertNotEquals(WorkControlState.ABANDONED,
                workbench.findWorkItem(principal, first.workItemId()).orElseThrow().controlState());
    }

    @Test
    void twoStoreInstancesCannotOwnTheSameCommandAtTheSameTime() throws Exception {
        AgentWorkItem work = createWork("multi-instance");
        setTarget(work.workItemId(), "INCIDENT_INVESTIGATION", "", "incident-multi");
        AgentWorkItem admitted = workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        UnifiedWorkIntakeResult command = command(admitted.conversationId(),
                WorkCommandType.PAUSE_ACTIVE_WORK, "multi-instance-pause");
        JdbcWorkCommandExecutionStore secondInstance = new JdbcWorkCommandExecutionStore(properties, objectMapper);

        var firstClaim = commandStore.claim(principal, command.input().inputId(), admitted.workItemId(),
                WorkCommandType.PAUSE_ACTIVE_WORK, admitted.version(), "instance-a", Duration.ofMinutes(1));
        var competingClaim = secondInstance.claim(principal, command.input().inputId(), admitted.workItemId(),
                WorkCommandType.PAUSE_ACTIVE_WORK, admitted.version(), "instance-b", Duration.ofMinutes(1));

        assertEquals(true, firstClaim.acquired());
        assertEquals(false, competingClaim.acquired());
        assertEquals(firstClaim.execution().commandRequestId(), competingClaim.execution().commandRequestId());
        assertEquals("instance-a", competingClaim.execution().leaseOwner());
    }

    @Test
    void successfulResumeKeepsRunIdAndProjectsAuthoritativeTerminalState() throws Exception {
        AgentWorkItem work = createWork("resume-same-run");
        setTarget(work.workItemId(), "GENERAL_AGENT", "run-same-1", "");
        AgentWorkItem admitted = workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        UnifiedWorkIntakeResult command = command(admitted.conversationId(),
                WorkCommandType.RESUME_ACTIVE_WORK, "resume-same-run");
        AgentRunRecord before = run("run-same-1", AgentRunState.PAUSED, 3, 0);
        AgentRunRecord after = run("run-same-1", AgentRunState.COMPLETED, 7, 1);
        when(runCommands.execute(eq(principal), any(AgentWorkItem.class),
                eq(WorkCommandType.RESUME_ACTIVE_WORK))).thenReturn(
                new AgentRunCommandResult(true, true, "OK", "resumed", before, after));

        WorkCommandResult result = handler.handle(principal, new WorkCommandRequest(
                command.input(), command.commandDecision(), admitted.workItemId(), admitted.version()));

        assertEquals(true, result.success());
        assertEquals("run-same-1", result.underlyingRunId());
        assertEquals("run-same-1", result.workItem().activeRunId());
        assertEquals(WorkControlState.CLOSED, result.workItem().controlState());
        assertEquals(WorkExecutionState.COMPLETED, result.workItem().executionState());
        assertEquals(WorkOutcome.ANSWERED, result.workItem().outcome());
    }

    private AgentWorkItem createWork(String name) {
        return createWork(name, "conversation-m3a-" + name + "-" + suffix);
    }

    private AgentWorkItem createWork(String name, String conversation) {
        long version = workbench.findConversationState(principal, conversation).map(state -> state.version()).orElse(0L);
        return new WorkInputService(new WorkItemService(workbench)).submit(principal,
                SubmitWorkInputCommand.direct("goal-" + name + "-" + suffix, conversation,
                        "goal " + name, version)).workItem();
    }

    private UnifiedWorkIntakeResult command(String conversation,
                                            WorkCommandType type,
                                            String name) {
        UnifiedWorkIntakeService intake = new UnifiedWorkIntakeService(routing, workbench, request ->
                new CommandClassifierResult(new WorkCommandClassification(type, 1,
                        "test command", request.focusedWorkItemId(), ""),
                        request.classifierType(), "", "", "", "", 0, 0, 0, "trace-" + name));
        return intake.accept(principal, new UnifiedWorkInputRequest(
                "input-" + name + "-" + suffix, "client-" + name + "-" + suffix,
                conversation, "[CONTROL] " + type.name(), ClassifierType.DETERMINISTIC_BUTTON, type, ""));
    }

    private void setTarget(String workItemId, String target, String runId, String incidentId) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_work_item SET control_state='DISPATCHED',execution_state='RUNNING',
                    active_execution_target=?,active_run_id=?,active_incident_id=? WHERE work_item_id=?
                """)) {
            statement.setString(1, target);
            statement.setString(2, runId.isBlank() ? null : runId);
            statement.setString(3, incidentId.isBlank() ? null : incidentId);
            statement.setString(4, workItemId);
            statement.executeUpdate();
        }
    }

    private AgentRunRecord run(String runId, AgentRunState state, long version, int resumeCount) {
        AgentRunRecord record = mock(AgentRunRecord.class);
        when(record.runId()).thenReturn(runId);
        when(record.state()).thenReturn(state);
        when(record.version()).thenReturn(version);
        when(record.resumeCount()).thenReturn(resumeCount);
        when(record.failureReason()).thenReturn("");
        return record;
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

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }

    private void execute(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private long count(String sql, String value) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }
}
