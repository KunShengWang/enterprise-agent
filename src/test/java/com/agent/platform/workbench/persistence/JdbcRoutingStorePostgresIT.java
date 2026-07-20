package com.agent.platform.workbench.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.llm.LlmService;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.workbench.application.DefaultWorkCommandClassifier;
import com.agent.platform.workbench.application.LlmUnifiedTaskRouter;
import com.agent.platform.workbench.application.NoopRoutingFailureInjector;
import com.agent.platform.workbench.application.RouteContextResolver;
import com.agent.platform.workbench.application.RoutePolicyValidator;
import com.agent.platform.workbench.application.RouterFailureObservation;
import com.agent.platform.workbench.application.RouterInvocationException;
import com.agent.platform.workbench.application.RouterModelResult;
import com.agent.platform.workbench.application.RoutingCoordinator;
import com.agent.platform.workbench.application.RoutingFailureInjector;
import com.agent.platform.workbench.application.RoutingModelRequest;
import com.agent.platform.workbench.application.RoutingResultPersistenceUnknownException;
import com.agent.platform.workbench.application.UnifiedTaskRouter;
import com.agent.platform.workbench.application.UnifiedWorkInputRequest;
import com.agent.platform.workbench.application.UnifiedWorkIntakeService;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.DecisionStatus;
import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Flux;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class JdbcRoutingStorePostgresIT {

    private final AgentStorageProperties storage = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String prefix = "m1b-" + UUID.randomUUID();
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-" + prefix, "alice", Set.of("USER", "INCIDENT_OPERATOR"));

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "DELETE FROM agent_routing_decision WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1b-%");
            execute(connection, "DELETE FROM agent_work_command_decision WHERE tenant_id LIKE ?", "tenant-m1b-%");
            execute(connection, "DELETE FROM agent_work_event WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1b-%");
            execute(connection, "DELETE FROM agent_work_link WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1b-%");
            execute(connection, "DELETE FROM agent_work_relation WHERE source_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?) OR target_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1b-%", "tenant-m1b-%");
            execute(connection, "DELETE FROM agent_conversation_work_state WHERE tenant_id LIKE ?", "tenant-m1b-%");
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id LIKE ?", "tenant-m1b-%");
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id LIKE ?", "tenant-m1b-%");
            connection.commit();
        }
    }

    @Test
    void createsOneEffectiveDecisionAndRepeatedRoutingDoesNotCallModelAgain() {
        Fixture fixture = fixture(successRouter("GENERAL_AGENT", Map.of(), 61, 13), new NoopRoutingFailureInjector());
        AgentWorkItem work = createWork(fixture, "解释 Java 中的 CAS");

        fixture.coordinator.route(principal, work.workItemId(), work.routingRequestId());
        fixture.coordinator.route(principal, work.workItemId(), work.routingRequestId());

        AgentWorkItem routed = fixture.workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        assertEquals(WorkControlState.READY_TO_DISPATCH, routed.controlState());
        assertFalse(routed.dispatchRequestId().isBlank());
        assertEquals(1, fixture.routerCalls.get());
        assertEquals(1, fixture.routing.listRoutingDecisions(principal, work.workItemId()).stream()
                .filter(decision -> decision.decisionStatus() == DecisionStatus.EFFECTIVE).count());
        assertTrue(fixture.workbench.listLinks(principal, work.workItemId()).isEmpty());
    }

    @Test
    void staleRecoveryRebuildsOnlyThePersistedTrustedPrincipalSnapshot() {
        Fixture fixture = fixture(successRouter("GENERAL_AGENT", Map.of(), 10, 5), new NoopRoutingFailureInjector());
        AgentWorkItem work = createWork(fixture, "解释 Java volatile");

        var candidates = fixture.routing.findStaleRouting(Instant.now().plusSeconds(1), 20);

        var recovered = candidates.stream()
                .filter(candidate -> candidate.workItem().workItemId().equals(work.workItemId()))
                .findFirst().orElseThrow();
        assertEquals(principal.tenantId(), recovered.principal().tenantId());
        assertEquals(principal.principalId(), recovered.principal().principalId());
        assertEquals(principal.roles(), recovered.principal().roles());
    }

    @Test
    void resultUnknownUsesSameWorkAndRoutingRequestThenCreatesOneNewAttempt() throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        RoutingFailureInjector injector = (attempt, result) -> {
            if (first.getAndSet(false)) throw new RoutingResultPersistenceUnknownException("injected crash");
        };
        Fixture fixture = fixture(successRouter("GENERAL_AGENT", Map.of(), 70, 30), injector);
        AgentWorkItem work = createWork(fixture, "解释 Java 内存模型");

        assertThrows(RoutingResultPersistenceUnknownException.class,
                () -> fixture.coordinator.route(principal, work.workItemId(), work.routingRequestId()));
        makeRoutingStale(work.workItemId());
        fixture.coordinator.route(principal, work.workItemId(), work.routingRequestId());

        List<com.agent.platform.workbench.model.RoutingDecisionRecord> decisions =
                fixture.routing.listRoutingDecisions(principal, work.workItemId());
        assertEquals(2, decisions.size());
        assertEquals(1, decisions.stream().filter(d -> d.decisionStatus() == DecisionStatus.RESULT_UNKNOWN).count());
        assertEquals(1, decisions.stream().filter(d -> d.decisionStatus() == DecisionStatus.EFFECTIVE).count());
        assertTrue(decisions.stream().allMatch(d -> d.routingRequestId().equals(work.routingRequestId())));
        assertTrue(fixture.routing.totalRoutingTokens(principal, work.workItemId()) >= 8_192 + 100);
        assertEquals(2, fixture.routerCalls.get());
    }

    @Test
    void expiredRoutingLeaseIsTakenOverOnceAndOldOwnerIsFenced() throws Exception {
        Fixture fixture = fixture(successRouter("GENERAL_AGENT", Map.of(), 10, 5),
                new NoopRoutingFailureInjector());
        AgentWorkItem work = createWork(fixture, "verify routing fencing");
        var ownerA = fixture.routing.claimRouting(principal, work.workItemId(), work.routingRequestId(),
                Instant.now().minusSeconds(1), 2, 8_192, "workbench-v1",
                "routing-owner-a", Instant.now().plusSeconds(30)).orElseThrow();
        assertTrue(fixture.routing.claimRouting(principal, work.workItemId(), work.routingRequestId(),
                Instant.now().minusSeconds(1), 2, 8_192, "workbench-v1",
                "routing-owner-b", Instant.now().plusSeconds(30)).isEmpty());

        makeRoutingStale(work.workItemId());
        var ownerB = fixture.routing.claimRouting(principal, work.workItemId(), work.routingRequestId(),
                Instant.now().minusSeconds(1), 2, 8_192, "workbench-v1",
                "routing-owner-b", Instant.now().plusSeconds(30)).orElseThrow();

        assertEquals(ownerA.fencingToken() + 1, ownerB.fencingToken());
        assertThrows(WorkbenchCasConflictException.class, () -> fixture.routing.failRouting(
                principal, ownerA, "LATE_OWNER", "late routing result",
                RouterFailureObservation.empty(), 0, 2));
    }

    @Test
    void failedAttemptsPreserveTokensAndLeaveRoutingAfterConfiguredMaximum() throws Exception {
        UnifiedTaskRouter failing = request -> {
            throw new RouterInvocationException(
                    "STRUCTURED_OUTPUT_INVALID", "invalid json",
                    new RouterFailureObservation("deepseek-chat", "prompt", "raw", 30, 10, 8), null);
        };
        Fixture fixture = fixture(failing, new NoopRoutingFailureInjector());
        AgentWorkItem work = createWork(fixture, "解释线程池");

        fixture.coordinator.route(principal, work.workItemId(), work.routingRequestId());
        makeRoutingStale(work.workItemId());
        fixture.coordinator.route(principal, work.workItemId(), work.routingRequestId());

        AgentWorkItem failed = fixture.workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        assertEquals(WorkControlState.MANUAL_REVIEW, failed.controlState());
        assertEquals(80, fixture.routing.totalRoutingTokens(principal, work.workItemId()));
        assertEquals(2, fixture.routing.listRoutingDecisions(principal, work.workItemId()).size());
    }

    @Test
    void incidentRouteStopsAtConfirmationAndCreatesNoChildExecution() {
        Fixture fixture = fixture(successRouter(
                "INCIDENT_INVESTIGATION",
                Map.of("batchId", "BATCH-1", "queueName", "floworder.incident.e2e.dlq"), 50, 20),
                new NoopRoutingFailureInjector());
        AgentWorkItem work = createWork(fixture,
                "调查 BATCH-1，队列 floworder.incident.e2e.dlq 的异常订单事故");

        fixture.coordinator.route(principal, work.workItemId(), work.routingRequestId());

        AgentWorkItem routed = fixture.workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        assertEquals(WorkControlState.WAITING_CONFIRMATION, routed.controlState());
        assertTrue(routed.dispatchRequestId().isBlank());
        assertTrue(fixture.workbench.listLinks(principal, work.workItemId()).isEmpty());
    }

    private Fixture fixture(UnifiedTaskRouter router, RoutingFailureInjector injector) {
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(storage, objectMapper);
        JdbcRoutingStore routing = new JdbcRoutingStore(storage, objectMapper);
        IncidentCommandProperties incident = new IncidentCommandProperties();
        incident.setEnabled(true);
        incident.setRecoveryPlannerEnabled(true);
        ExecutionTargetRegistry registry = new ExecutionTargetRegistry(incident);
        WorkbenchRoutingProperties properties = new WorkbenchRoutingProperties();
        properties.setEnabled(true);
        properties.setStaleAfterMillis(1_000);
        properties.setRetryBackoffMillis(0);
        AtomicInteger calls = new AtomicInteger();
        UnifiedTaskRouter counted = request -> {
            calls.incrementAndGet();
            return router.route(request);
        };
        RoutingCoordinator coordinator = new RoutingCoordinator(
                routing, workbench, counted,
                new RoutePolicyValidator(registry, properties, objectMapper),
                new RouteContextResolver(workbench), registry, properties, injector);
        LlmService unused = new LlmService() {
            @Override public String complete(PromptRequest request) { throw new AssertionError("model must not be called"); }
            @Override public Flux<String> stream(PromptRequest request) { return Flux.error(new AssertionError()); }
        };
        UnifiedWorkIntakeService intake = new UnifiedWorkIntakeService(
                routing, workbench, new DefaultWorkCommandClassifier(unused, objectMapper));
        return new Fixture(workbench, routing, intake, coordinator, calls);
    }

    private UnifiedTaskRouter successRouter(String target, Map<String, Object> inputs, long prompt, long completion) {
        return request -> new RouterModelResult(
                new ExecutionDecision(target, .98, "fixture route", inputs, List.of(), "fixture"),
                "deepseek-chat", "prompt-digest", "raw-digest", "{}", prompt, completion, 5);
    }

    private AgentWorkItem createWork(Fixture fixture, String goal) {
        String id = UUID.randomUUID().toString();
        var result = fixture.intake.accept(principal, new UnifiedWorkInputRequest(
                "input-" + id, "client-" + id, "conversation-" + id, goal,
                ClassifierType.DETERMINISTIC_PROTOCOL, WorkCommandType.NORMAL_GOAL, ""));
        return result.workItem();
    }

    private void makeRoutingStale(String workItemId) throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "UPDATE agent_routing_decision SET created_at=? WHERE work_item_id=? AND decision_status='STARTED'",
                    Instant.now().minusSeconds(30), workItemId);
            execute(connection, "UPDATE agent_routing_decision SET lease_until=? WHERE work_item_id=? AND decision_status='STARTED'",
                    Instant.now().minusSeconds(30), workItemId);
            execute(connection, "UPDATE agent_work_item SET routing_last_attempt_at=?, routing_next_retry_at=? WHERE work_item_id=?",
                    Instant.now().minusSeconds(30), Instant.now().minusSeconds(30), workItemId);
            connection.commit();
        }
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
        return DriverManager.getConnection(storage.getDatasource().getUrl(),
                storage.getDatasource().getUsername(), storage.getDatasource().getPassword());
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof Instant instant) statement.setTimestamp(index + 1, java.sql.Timestamp.from(instant));
                else statement.setObject(index + 1, value);
            }
            statement.executeUpdate();
        }
    }

    private record Fixture(
            JdbcWorkbenchStore workbench,
            JdbcRoutingStore routing,
            UnifiedWorkIntakeService intake,
            RoutingCoordinator coordinator,
            AtomicInteger routerCalls
    ) { }
}
