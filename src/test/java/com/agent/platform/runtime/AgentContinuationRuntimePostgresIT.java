package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.llm.ConfiguredLlmCostCalculator;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.memory.MemoryService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "INCIDENT_POSTGRES_IT", matches = "true")
class AgentContinuationRuntimePostgresIT {

    private static final String SESSION_PREFIX = "m1c-it-";
    private final AgentStorageProperties storage = storageProperties();

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                storage.getDatasource().getUrl(),
                storage.getDatasource().getUsername(),
                storage.getDatasource().getPassword())) {
            connection.setAutoCommit(false);
            delete(connection, "DELETE FROM agent_runtime_event WHERE session_id LIKE ?");
            delete(connection, "DELETE FROM agent_message WHERE session_id LIKE ?");
            delete(connection, "DELETE FROM agent_session_lease WHERE session_id LIKE ?");
            delete(connection, "DELETE FROM agent_run_state WHERE conversation_id LIKE ?");
            delete(connection, "DELETE FROM agent_session WHERE session_id LIKE ?");
            connection.commit();
        }
    }

    @Test
    void restartsAndContinuesTheSameRunWithCumulativeBudgetAndTimeline() {
        JdbcAgentRuntimeStore runStore = new JdbcAgentRuntimeStore(storage, new ObjectMapper());
        JdbcAgentTimelineStore timelineStore = new JdbcAgentTimelineStore(storage, new ObjectMapper());
        AtomicInteger modelCalls = new AtomicInteger();
        String sessionId = SESSION_PREFIX + UUID.randomUUID();
        AgentExecutionProfile profile = profile();

        DefaultAgentRuntime firstProcess = runtime(runStore, timelineStore, modelCalls, "first investigation");
        AgentRuntimeResult waiting = firstProcess.runUntilInputCheckpoint(
                new AgentRequest(sessionId, "reviewer", "investigate incident", Map.of(), "incident-command"),
                profile,
                AgentEventListener.NOOP
        );

        assertEquals(AgentRunState.WAITING_INPUT, waiting.state());
        assertEquals(AgentStopReason.WAITING_INPUT, waiting.stopReason());
        assertTrue(waiting.budget().executionPaused());
        assertEquals(1, waiting.budget().modelCalls());

        // 构造第二个 Runtime 实例，模拟原执行对象和进程内上下文已经消失。
        DefaultAgentRuntime restartedProcess = runtime(runStore, timelineStore, modelCalls, "clarified investigation");
        AgentRuntimeResult completed = restartedProcess.continueWithInput(
                waiting.runId(),
                followUp("task-mq", "conflict-7"),
                AgentEventListener.NOOP
        );

        assertEquals(waiting.runId(), completed.runId());
        assertEquals(AgentRunState.COMPLETED, completed.state());
        assertEquals(2, completed.budget().modelCalls());
        assertEquals(2, completed.budget().turns());
        AgentRunRecord persisted = runStore.find(waiting.runId()).orElseThrow();
        assertEquals(1, persisted.followUpCount());
        assertEquals(1, persisted.maxFollowUps());
        assertEquals(1, persisted.resumeCount());
        assertFalse(persisted.budgetSnapshot().executionPaused());

        List<AgentMessage> messages = timelineStore.loadMessages(sessionId, 100);
        assertEquals(List.of(
                        AgentMessageType.USER,
                        AgentMessageType.ASSISTANT_TEXT,
                        AgentMessageType.USER,
                        AgentMessageType.ASSISTANT_TEXT),
                messages.stream().map(AgentMessage::type).toList());
        assertTrue(messages.stream().allMatch(message -> waiting.runId().equals(message.runId())));
        assertEquals(List.of(1L, 2L, 3L, 4L), messages.stream().map(AgentMessage::sequence).toList());
        assertTrue(timelineStore.loadEvents(waiting.runId(), 100).stream()
                .anyMatch(event -> event.type() == AgentEventType.RUN_WAITING_INPUT));
        assertTrue(timelineStore.loadEvents(waiting.runId(), 100).stream()
                .anyMatch(event -> event.type() == AgentEventType.RUN_INPUT_RECEIVED));
    }

    @Test
    void waitingInputClaimUsesVersionCasAndAllowsOnlyOneWinner() throws Exception {
        JdbcAgentRuntimeStore runStore = new JdbcAgentRuntimeStore(storage, new ObjectMapper());
        JdbcAgentTimelineStore timelineStore = new JdbcAgentTimelineStore(storage, new ObjectMapper());
        String sessionId = SESSION_PREFIX + UUID.randomUUID();
        AgentRuntimeResult waiting = runtime(runStore, timelineStore, new AtomicInteger(), "evidence")
                .runUntilInputCheckpoint(
                        new AgentRequest(sessionId, "reviewer", "investigate", Map.of(), "incident-command"),
                        profile(), AgentEventListener.NOOP);
        AgentRunRecord checkpoint = runStore.find(waiting.runId()).orElseThrow();

        var first = CompletableFuture.supplyAsync(() -> claim(
                runStore, checkpoint, "question-a", "event-a"));
        var second = CompletableFuture.supplyAsync(() -> claim(
                runStore, checkpoint, "question-b", "event-b"));

        long winners = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)).stream()
                .filter(Optional::isPresent)
                .count();
        assertEquals(1, winners);
        assertEquals(1, runStore.find(waiting.runId()).orElseThrow().followUpCount());
        assertEquals(3, timelineStore.loadMessages(sessionId, 100).size());
        assertEquals(1, timelineStore.loadEvents(waiting.runId(), 100).stream()
                .filter(event -> event.type() == AgentEventType.RUN_INPUT_RECEIVED)
                .count());
    }

    @Test
    void waitingRunCanCompleteWithoutFollowUpButTerminalRunCannotReopen() {
        JdbcAgentRuntimeStore runStore = new JdbcAgentRuntimeStore(storage, new ObjectMapper());
        JdbcAgentTimelineStore timelineStore = new JdbcAgentTimelineStore(storage, new ObjectMapper());
        String sessionId = SESSION_PREFIX + UUID.randomUUID();
        DefaultAgentRuntime runtime = runtime(runStore, timelineStore, new AtomicInteger(), "no conflict");
        AgentRuntimeResult waiting = runtime.runUntilInputCheckpoint(
                new AgentRequest(sessionId, "reviewer", "investigate", Map.of(), "incident-command"),
                profile(), AgentEventListener.NOOP);

        AgentRuntimeResult completed = runtime.completeWaitingInput(waiting.runId());
        AgentRuntimeResult rejectedReopen = runtime.continueWithInput(
                waiting.runId(), followUp("task-order", "conflict-none"), AgentEventListener.NOOP);

        assertEquals(AgentRunState.COMPLETED, completed.state());
        assertEquals(AgentRunState.COMPLETED, rejectedReopen.state());
        assertEquals(0, runStore.find(waiting.runId()).orElseThrow().followUpCount());
        assertEquals(2, timelineStore.loadMessages(sessionId, 100).size());
    }

    private Optional<AgentContinuationTransition> claim(JdbcAgentRuntimeStore store,
                                                        AgentRunRecord checkpoint,
                                                        String question,
                                                        String eventId) {
        AgentRequest request = new AgentRequest(
                checkpoint.conversationId(), checkpoint.userId(), question, Map.of(), "incident-command");
        return store.claimWaitingInput(
                checkpoint.runId(), checkpoint.version(), request,
                new AgentMessageDraft(
                        AgentMessageType.USER, question, "", "", Map.of(),
                        Map.of("schemaVersion", "follow-up-task-v1", "eventId", eventId), 2),
                new AgentEventDraft(
                        AgentEventType.RUN_INPUT_RECEIVED, "input claimed", Map.of("eventId", eventId))
        );
    }

    private DefaultAgentRuntime runtime(JdbcAgentRuntimeStore runStore,
                                        JdbcAgentTimelineStore timelineStore,
                                        AtomicInteger modelCalls,
                                        String answer) {
        AgentProperties properties = new AgentProperties();
        AgentContextManager contextManager = mock(AgentContextManager.class);
        when(contextManager.project(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new AgentContextView(List.of(), 0, 0, false));
        GuardrailService guardrail = mock(GuardrailService.class);
        when(guardrail.checkInput(anyString()))
                .thenReturn(GuardrailDecision.allow(GuardrailStage.INPUT, "ok"));
        when(guardrail.checkOutput(anyString()))
                .thenReturn(GuardrailDecision.allow(GuardrailStage.OUTPUT, "ok"));
        when(guardrail.previewOutput(anyString()))
                .thenReturn(GuardrailDecision.allow(GuardrailStage.OUTPUT, "ok"));
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        when(registry.listCapabilities()).thenReturn(List.of());
        AgentModelGateway gateway = request -> {
            modelCalls.incrementAndGet();
            return new AgentModelTurn(
                    answer, List.of(), answer,
                    new LlmUsage(10, 5, 15, 0, 0, "m1c-test", "test"), "stop");
        };
        return new DefaultAgentRuntime(
                properties,
                timelineStore,
                runStore,
                runStore,
                contextManager,
                gateway,
                registry,
                mock(AgentToolRuntime.class),
                guardrail,
                mock(ApprovalService.class),
                new ConservativeTokenEstimator(),
                new JdbcAgentRunControlStore(storage),
                mock(MemoryService.class),
                new ConfiguredLlmCostCalculator(properties),
                new ToolResultProjector(properties)
        );
    }

    private AgentExecutionProfile profile() {
        return new AgentExecutionProfile(
                "incident-specialist",
                "Collect read-only evidence.",
                Set.of(),
                new AgentRunLimits(4, 4, 2, 10_000, 4_000, 10, 120_000),
                false
        );
    }

    private AgentFollowUpInput followUp(String taskId, String conflictId) {
        return new AgentFollowUpInput(
                "follow-up-task-v1", "EVIDENCE_CLARIFICATION", taskId, conflictId,
                List.of("evidence-1"), "recheck the conflicting evidence", 0, 100,
                Map.of("requestedAt", Instant.now().toString())
        );
    }

    private AgentStorageProperties storageProperties() {
        AgentStorageProperties properties = new AgentStorageProperties();
        properties.getDatasource().setUrl(environment(
                "AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent"));
        properties.getDatasource().setUsername(environment("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        properties.getDatasource().setPassword(environment("AGENT_STORAGE_POSTGRES_PASSWORD", ""));
        return properties;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private void delete(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SESSION_PREFIX + "%");
            statement.executeUpdate();
        }
    }
}
