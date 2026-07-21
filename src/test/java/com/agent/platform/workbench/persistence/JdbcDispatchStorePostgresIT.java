package com.agent.platform.workbench.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.config.WorkbenchDispatchProperties;
import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.llm.LlmService;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.workbench.application.DefaultWorkCommandClassifier;
import com.agent.platform.workbench.application.DispatchPreparationService;
import com.agent.platform.workbench.application.NoopRoutingFailureInjector;
import com.agent.platform.workbench.application.RouteContextResolver;
import com.agent.platform.workbench.application.RoutePolicyValidator;
import com.agent.platform.workbench.application.RouterModelResult;
import com.agent.platform.workbench.application.RoutingCoordinator;
import com.agent.platform.workbench.application.UnifiedTaskRouter;
import com.agent.platform.workbench.application.UnifiedWorkInputRequest;
import com.agent.platform.workbench.application.UnifiedWorkIntakeService;
import com.agent.platform.workbench.dispatch.DispatchCoordinator;
import com.agent.platform.workbench.dispatch.DispatchFailureInjector;
import com.agent.platform.workbench.dispatch.DispatchRequest;
import com.agent.platform.workbench.dispatch.DispatchResult;
import com.agent.platform.workbench.dispatch.DispatchResultPersistenceUnknownException;
import com.agent.platform.workbench.dispatch.ExecutionAdapter;
import com.agent.platform.workbench.dispatch.ExecutionAdapterRegistry;
import com.agent.platform.workbench.dispatch.NoopDispatchFailureInjector;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.DispatchAttemptStatus;
import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.model.RoutePreviewStatus;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetId;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class JdbcDispatchStorePostgresIT {

    private final AgentStorageProperties storage = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String prefix = "m1c-" + UUID.randomUUID();
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-" + prefix, "alice", Set.of("USER", "INCIDENT_OPERATOR"));

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "DELETE FROM agent_dispatch_attempt WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_route_preview WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_routing_decision WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_work_command_decision WHERE tenant_id LIKE ?", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_work_projection_cursor WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_work_event WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_work_link WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_work_relation WHERE source_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?) OR target_work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)", "tenant-m1c-%", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_conversation_work_state WHERE tenant_id LIKE ?", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id LIKE ?", "tenant-m1c-%");
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id LIKE ?", "tenant-m1c-%");
            connection.commit();
        }
    }

    @Test
    void stableDispatchCreatesOneTargetOneLinkAndOneEffectiveAttempt() {
        Fixture fixture = fixture("GENERAL_AGENT", Map.of(), new NoopDispatchFailureInjector());
        AgentWorkItem work = routedWork(fixture, "解释 Java CAS");

        fixture.coordinator.dispatch(principal, work.workItemId());
        fixture.coordinator.dispatch(principal, work.workItemId());

        AgentWorkItem dispatched = fixture.workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        assertEquals(WorkControlState.DISPATCHED, dispatched.controlState());
        assertEquals(1, fixture.adapters.get(ExecutionTargetId.GENERAL_AGENT).dispatchCalls.get());
        assertEquals(1, fixture.workbench.listLinks(principal, work.workItemId()).size());
        assertEquals(1, fixture.dispatchStore.listAttempts(principal, work.workItemId()).stream()
                .filter(attempt -> attempt.status() == DispatchAttemptStatus.EFFECTIVE).count());
    }

    @Test
    void linkingEarlyDiscoveredRunPreservesCancellationIntent() throws Exception {
        Fixture fixture = fixture("GENERAL_AGENT", Map.of(), new NoopDispatchFailureInjector());
        AgentWorkItem work = routedWork(fixture, "explain cancellation during dispatch");
        var claim = fixture.dispatchStore.claimDispatch(principal, work.workItemId(),
                Instant.now().minusSeconds(1), 2, "dispatch-cancel-owner",
                Instant.now().plusSeconds(30)).orElseThrow();
        try (Connection connection = openConnection()) {
            execute(connection, "UPDATE agent_work_item SET control_state='CANCEL_REQUESTED' WHERE work_item_id=?",
                    work.workItemId());
        }

        fixture.dispatchStore.completeDispatch(principal, claim,
                new DispatchResult(work.dispatchRequestId(), WorkLinkType.RUN, "run-cancel-early", true));

        AgentWorkItem linked = fixture.workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        assertEquals(WorkControlState.CANCEL_REQUESTED, linked.controlState());
        assertEquals(WorkExecutionState.STARTING, linked.executionState());
        assertEquals("run-cancel-early", linked.activeRunId());
        assertEquals(1, fixture.workbench.listLinks(principal, work.workItemId()).size());
    }

    @Test
    void targetCreatedBeforeWorkLinkCrashIsReconciledWithoutSecondTarget() throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        DispatchFailureInjector injector = (claim, result) -> {
            if (first.getAndSet(false)) throw new DispatchResultPersistenceUnknownException("injected crash");
        };
        Fixture fixture = fixture("GENERAL_AGENT", Map.of(), injector);
        AgentWorkItem work = routedWork(fixture, "解释 Java happens-before");

        assertThrows(DispatchResultPersistenceUnknownException.class,
                () -> fixture.coordinator.dispatch(principal, work.workItemId()));
        assertTrue(fixture.workbench.listLinks(principal, work.workItemId()).isEmpty());
        makeDispatchStale(work.workItemId());
        fixture.coordinator = coordinator(fixture, new NoopDispatchFailureInjector());
        fixture.coordinator.dispatch(principal, work.workItemId());

        FakeAdapter adapter = fixture.adapters.get(ExecutionTargetId.GENERAL_AGENT);
        assertEquals(1, adapter.dispatchCalls.get());
        assertEquals(1, adapter.reconcileCalls.get());
        assertEquals(1, fixture.workbench.listLinks(principal, work.workItemId()).size());
        var attempts = fixture.dispatchStore.listAttempts(principal, work.workItemId());
        assertEquals(2, attempts.size());
        assertEquals(1, attempts.stream().filter(a -> a.status() == DispatchAttemptStatus.RESULT_UNKNOWN).count());
        assertEquals(1, attempts.stream().filter(a -> a.status() == DispatchAttemptStatus.EFFECTIVE).count());
    }

    @Test
    void expiredDispatchLeaseIsTakenOverOnceAndOldOwnerIsFenced() throws Exception {
        Fixture fixture = fixture("GENERAL_AGENT", Map.of(), new NoopDispatchFailureInjector());
        AgentWorkItem work = routedWork(fixture, "verify dispatch fencing");
        var ownerA = fixture.dispatchStore.claimDispatch(principal, work.workItemId(),
                Instant.now().minusSeconds(1), 2, "dispatch-owner-a",
                Instant.now().plusSeconds(30)).orElseThrow();
        assertTrue(fixture.dispatchStore.claimDispatch(principal, work.workItemId(),
                Instant.now().minusSeconds(1), 2, "dispatch-owner-b",
                Instant.now().plusSeconds(30)).isEmpty());

        makeDispatchStale(work.workItemId());
        var ownerB = fixture.dispatchStore.claimDispatch(principal, work.workItemId(),
                Instant.now().minusSeconds(1), 2, "dispatch-owner-b",
                Instant.now().plusSeconds(30)).orElseThrow();

        assertEquals(ownerA.fencingToken() + 1, ownerB.fencingToken());
        assertThrows(WorkbenchCasConflictException.class, () -> fixture.dispatchStore.failDispatch(
                principal, ownerA, "LATE_OWNER", "late dispatch result", 0, 2));
    }

    @Test
    void incidentPreviewIsImmutableAndNoAdapterRunsBeforeExplicitConfirmation() {
        Map<String, Object> inputs = Map.of(
                "requestIds", List.of("REQ-M1C-1"), "queueName", "floworder.incident.e2e.dlq");
        Fixture fixture = fixture("INCIDENT_INVESTIGATION", inputs, new NoopDispatchFailureInjector());
        AgentWorkItem work = routedWork(fixture,
                "调查 requestId=REQ-M1C-1，队列 floworder.incident.e2e.dlq 的事故");

        AgentWorkItem waiting = fixture.workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
        var preview = fixture.dispatchStore.findPreview(principal, work.workItemId()).orElseThrow();
        assertEquals(WorkControlState.WAITING_CONFIRMATION, waiting.controlState());
        assertEquals(RoutePreviewStatus.ACTIVE, preview.status());
        assertEquals(0, fixture.adapters.get(ExecutionTargetId.INCIDENT_INVESTIGATION).dispatchCalls.get());
        assertTrue(fixture.workbench.listLinks(principal, work.workItemId()).isEmpty());

        assertThrows(WorkbenchIdempotencyConflictException.class, () -> fixture.dispatchStore.confirmPreview(
                principal, work.workItemId(), preview.previewId(), preview.previewVersion(),
                preview.validatedInputDigest(), "tampered-scope"));
        AgentWorkItem ready = fixture.dispatchStore.confirmPreview(
                principal, work.workItemId(), preview.previewId(), preview.previewVersion(),
                preview.validatedInputDigest(), preview.scopeDigest());
        assertEquals(WorkControlState.READY_TO_DISPATCH, ready.controlState());
        assertFalse(ready.dispatchRequestId().isBlank());

        fixture.coordinator.dispatch(principal, work.workItemId());
        assertEquals(1, fixture.adapters.get(ExecutionTargetId.INCIDENT_INVESTIGATION).dispatchCalls.get());
    }

    @Test
    void expiredPreviewCannotReuseOldHumanConfirmation() throws Exception {
        Fixture fixture = fixture("INCIDENT_INVESTIGATION",
                Map.of("requestIds", List.of("REQ-M1C-2"), "queueName", "floworder.incident.e2e.dlq"),
                new NoopDispatchFailureInjector());
        AgentWorkItem work = routedWork(fixture,
                "调查 requestId=REQ-M1C-2，队列 floworder.incident.e2e.dlq 的事故");
        var preview = fixture.dispatchStore.findPreview(principal, work.workItemId()).orElseThrow();
        try (Connection connection = openConnection()) {
            execute(connection, "UPDATE agent_route_preview SET expires_at=? WHERE preview_id=?",
                    Instant.now().minusSeconds(1), preview.previewId());
        }

        assertThrows(WorkbenchCasConflictException.class, () -> fixture.dispatchStore.confirmPreview(
                principal, work.workItemId(), preview.previewId(), preview.previewVersion(),
                preview.validatedInputDigest(), preview.scopeDigest()));
        assertEquals(WorkControlState.WAITING_CONFIRMATION,
                fixture.workbench.findWorkItem(principal, work.workItemId()).orElseThrow().controlState());
        assertEquals(RoutePreviewStatus.EXPIRED,
                fixture.dispatchStore.findPreview(principal, work.workItemId()).orElseThrow().status());
    }

    private Fixture fixture(String targetId, Map<String, Object> inputs, DispatchFailureInjector injector) {
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(storage, objectMapper);
        JdbcRoutingStore routing = new JdbcRoutingStore(storage, objectMapper);
        JdbcDispatchStore dispatch = new JdbcDispatchStore(storage, objectMapper);
        IncidentCommandProperties incident = new IncidentCommandProperties();
        incident.setEnabled(true); incident.setRecoveryPlannerEnabled(true);
        ExecutionTargetRegistry targets = new ExecutionTargetRegistry(incident);
        WorkbenchDispatchProperties dispatchProperties = new WorkbenchDispatchProperties();
        dispatchProperties.setEnabled(true); dispatchProperties.setStaleAfterMillis(1_000);
        WorkbenchRoutingProperties routingProperties = new WorkbenchRoutingProperties();
        routingProperties.setEnabled(true);
        UnifiedTaskRouter routerModel = request -> new RouterModelResult(
                new ExecutionDecision(targetId, .99, "fixture", inputs, List.of(), "fixture"),
                "fixture-model", "prompt", "raw", "{}", 10, 5, 1);
        RoutingCoordinator routingCoordinator = new RoutingCoordinator(
                routing, workbench, routerModel,
                new RoutePolicyValidator(targets, routingProperties, objectMapper),
                new RouteContextResolver(workbench), targets, routingProperties,
                new NoopRoutingFailureInjector(), new DispatchPreparationService(dispatch, dispatchProperties));
        LlmService unused = new LlmService() {
            @Override public String complete(PromptRequest request) { throw new AssertionError(); }
            @Override public Flux<String> stream(PromptRequest request) { return Flux.error(new AssertionError()); }
        };
        UnifiedWorkIntakeService intake = new UnifiedWorkIntakeService(
                routing, workbench, new DefaultWorkCommandClassifier(unused, objectMapper));
        EnumMap<ExecutionTargetId, FakeAdapter> adapters = new EnumMap<>(ExecutionTargetId.class);
        for (ExecutionTargetId id : ExecutionTargetId.values()) adapters.put(id, new FakeAdapter(id));
        ExecutionAdapterRegistry adapterRegistry = new ExecutionAdapterRegistry(List.copyOf(adapters.values()));
        Fixture fixture = new Fixture(workbench, routing, dispatch, intake, routingCoordinator,
                dispatchProperties, adapterRegistry, adapters, null);
        fixture.coordinator = coordinator(fixture, injector);
        return fixture;
    }

    private DispatchCoordinator coordinator(Fixture fixture, DispatchFailureInjector injector) {
        return new DispatchCoordinator(fixture.dispatchStore, fixture.adapterRegistry,
                fixture.dispatchProperties, injector);
    }

    private AgentWorkItem routedWork(Fixture fixture, String goal) {
        String id = UUID.randomUUID().toString();
        AgentWorkItem work = fixture.intake.accept(principal, new UnifiedWorkInputRequest(
                "input-" + id, "client-" + id, "conversation-" + id, goal,
                ClassifierType.DETERMINISTIC_PROTOCOL, WorkCommandType.NORMAL_GOAL, "")).workItem();
        fixture.routingCoordinator.route(principal, work.workItemId(), work.routingRequestId());
        return fixture.workbench.findWorkItem(principal, work.workItemId()).orElseThrow();
    }

    private void makeDispatchStale(String workItemId) throws Exception {
        try (Connection connection = openConnection()) {
            execute(connection, "UPDATE agent_dispatch_attempt SET created_at=? WHERE work_item_id=? AND status='STARTED'",
                    Instant.now().minusSeconds(30), workItemId);
            execute(connection, "UPDATE agent_dispatch_attempt SET lease_until=? WHERE work_item_id=? AND status='STARTED'",
                    Instant.now().minusSeconds(30), workItemId);
        }
    }

    private AgentStorageProperties properties() {
        AgentStorageProperties result = new AgentStorageProperties();
        result.getDatasource().setUrl(environment("AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent"));
        result.getDatasource().setUsername(environment("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        result.getDatasource().setPassword(environment("AGENT_STORAGE_POSTGRES_PASSWORD", "1234"));
        return result;
    }
    private String environment(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value;}
    private Connection openConnection() throws Exception{return DriverManager.getConnection(storage.getDatasource().getUrl(),storage.getDatasource().getUsername(),storage.getDatasource().getPassword());}
    private void execute(Connection connection,String sql,Object...values)throws Exception{try(PreparedStatement statement=connection.prepareStatement(sql)){for(int i=0;i<values.length;i++){Object value=values[i];if(value instanceof Instant instant)statement.setTimestamp(i+1,java.sql.Timestamp.from(instant));else statement.setObject(i+1,value);}statement.executeUpdate();}}

    private static final class FakeAdapter implements ExecutionAdapter {
        private final ExecutionTargetId targetId;
        private final Map<String,String> targets=new ConcurrentHashMap<>();
        private final AtomicInteger dispatchCalls=new AtomicInteger();
        private final AtomicInteger reconcileCalls=new AtomicInteger();
        private FakeAdapter(ExecutionTargetId targetId){this.targetId=targetId;}
        @Override public ExecutionTargetId targetId(){return targetId;}
        @Override public DispatchResult dispatch(DispatchRequest request){dispatchCalls.incrementAndGet();String id=targets.computeIfAbsent(request.dispatchRequestId(),key->targetId.name().toLowerCase()+"-"+UUID.randomUUID());return new DispatchResult(request.dispatchRequestId(),linkType(),id,true);}
        @Override public Optional<DispatchResult> reconcile(DispatchRequest request){reconcileCalls.incrementAndGet();return Optional.ofNullable(targets.get(request.dispatchRequestId())).map(id->new DispatchResult(request.dispatchRequestId(),linkType(),id,false));}
        private WorkLinkType linkType(){return switch(targetId){case GENERAL_AGENT,ORDERCARE_CASE->WorkLinkType.RUN;case INCIDENT_INVESTIGATION->WorkLinkType.INCIDENT;case INCIDENT_RECOVERY_PLAN->WorkLinkType.RECOVERY_PLAN;};}
    }

    private static final class Fixture {
        private final JdbcWorkbenchStore workbench; private final JdbcRoutingStore routing;
        private final JdbcDispatchStore dispatchStore; private final UnifiedWorkIntakeService intake;
        private final RoutingCoordinator routingCoordinator; private final WorkbenchDispatchProperties dispatchProperties;
        private final ExecutionAdapterRegistry adapterRegistry; private final EnumMap<ExecutionTargetId,FakeAdapter> adapters;
        private DispatchCoordinator coordinator;
        private Fixture(JdbcWorkbenchStore workbench,JdbcRoutingStore routing,JdbcDispatchStore dispatchStore,
                        UnifiedWorkIntakeService intake,RoutingCoordinator routingCoordinator,
                        WorkbenchDispatchProperties dispatchProperties,ExecutionAdapterRegistry adapterRegistry,
                        EnumMap<ExecutionTargetId,FakeAdapter> adapters,DispatchCoordinator coordinator){this.workbench=workbench;this.routing=routing;this.dispatchStore=dispatchStore;this.intake=intake;this.routingCoordinator=routingCoordinator;this.dispatchProperties=dispatchProperties;this.adapterRegistry=adapterRegistry;this.adapters=adapters;this.coordinator=coordinator;}
    }
}
