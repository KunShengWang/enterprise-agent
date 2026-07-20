package com.agent.platform.workbench.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import com.agent.platform.workbench.application.SubmitWorkInputCommand;
import com.agent.platform.workbench.application.WorkInputService;
import com.agent.platform.workbench.application.WorkItemCreationResult;
import com.agent.platform.workbench.application.WorkItemService;
import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.ProjectedWorkEventDraft;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkEventDraft;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.model.WorkLinkRelation;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.model.WorkRelationType;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class JdbcWorkbenchStorePostgresIT {

    private final AgentStorageProperties properties = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String testPrefix = "m1a-" + UUID.randomUUID();
    private final AuthenticatedPrincipal principal = principal("tenant-" + testPrefix, "alice");

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            execute(connection, """
                    DELETE FROM agent_dispatch_attempt WHERE work_item_id IN (
                        SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)
                    """, "tenant-m1a-%");
            execute(connection, """
                    DELETE FROM agent_route_preview WHERE work_item_id IN (
                        SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)
                    """, "tenant-m1a-%");
            execute(connection, """
                    DELETE FROM agent_routing_decision WHERE work_item_id IN (
                        SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)
                    """, "tenant-m1a-%");
            execute(connection, """
                    DELETE FROM agent_work_command_execution WHERE work_item_id IN (
                        SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)
                    """, "tenant-m1a-%");
            execute(connection, """
                    DELETE FROM agent_work_event WHERE work_item_id IN (
                        SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)
                    """, "tenant-m1a-%");
            execute(connection, """
                    DELETE FROM agent_work_projection_cursor WHERE work_item_id IN (
                        SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)
                    """, "tenant-m1a-%");
            execute(connection, """
                    DELETE FROM agent_work_link WHERE work_item_id IN (
                        SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)
                    """, "tenant-m1a-%");
            execute(connection, """
                    DELETE FROM agent_work_relation WHERE source_work_item_id IN (
                        SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)
                       OR target_work_item_id IN (
                        SELECT work_item_id FROM agent_work_item WHERE tenant_id LIKE ?)
                    """, "tenant-m1a-%", "tenant-m1a-%");
            execute(connection,
                    "DELETE FROM agent_conversation_work_state WHERE tenant_id LIKE ?", "tenant-m1a-%");
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id LIKE ?", "tenant-m1a-%");
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id LIKE ?", "tenant-m1a-%");
            connection.commit();
        }
    }

    @Test
    void createsInputWorkItemFocusAndFirstEventAtomically() {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult result = submit(store, principal, "client-create", conversation("create"), 0);

        assertFalse(result.duplicate());
        assertEquals(result.input().inputId(), result.workItem().sourceInputId());
        assertEquals(result.workItem().workItemId(), result.focus().focusedWorkItemId());
        assertEquals(1, result.focus().version());
        assertTrue(result.workItem().routingRequestId().startsWith("route-"));
        assertEquals(WorkEventType.WORK_ITEM_CREATED, result.createdEvent().eventType());
        assertEquals(0, result.createdEvent().sequence());
        assertEquals("WORK_ITEM", result.createdEvent().sourceType());
        assertEquals(1, store.findWorkItem(principal, result.workItem().workItemId()).orElseThrow()
                .nextEventSequence());
    }

    @Test
    void projectedSourceReplayTenTimesIsIdempotentAndPreservesSourceCoordinates() {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "client-projection-replay", conversation("projection-replay"), 0);
        String runId = "run-" + testPrefix;
        insertProjectionLink(created.workItem().workItemId(), "dispatch-projection-replay",
                WorkLinkType.RUN, runId);
        Instant sourceCreatedAt = Instant.now().minusSeconds(3);
        ProjectedWorkEventDraft draft = new ProjectedWorkEventDraft(
                "AGENT_RUN", runId, "runtime-event-7", 7,
                WorkEventType.RUN_EVENT_PROJECTED, "TOOL_COMPLETED", "tool completed",
                Map.of("tool", "case-inspect"), created.workItem().workItemId(), "cause-7", sourceCreatedAt);

        IntStream.range(0, 10).forEach(ignored ->
                store.appendProjectedEvent(created.workItem().workItemId(), draft));

        var events = store.loadEvents(principal, created.workItem().workItemId(), -1, 100);
        assertEquals(2, events.size());
        assertEquals(1, events.get(1).sequence());
        assertEquals(7, events.get(1).sourceSequence());
        assertTrue(Math.abs(Duration.between(sourceCreatedAt, events.get(1).sourceCreatedAt()).toNanos()) <= 1_000);
        assertTrue(!events.get(1).projectedAt().isBefore(sourceCreatedAt));
        assertEquals(7, store.projectionCursor(created.workItem().workItemId(), "AGENT_RUN", runId));
    }

    @Test
    void projectedSourceMustAlreadyBeBoundByAuthoritativeWorkLink() {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "client-projection-unlinked", conversation("projection-unlinked"), 0);

        assertThrows(WorkbenchAccessDeniedException.class, () -> store.appendProjectedEvent(
                created.workItem().workItemId(), new ProjectedWorkEventDraft(
                        "AGENT_RUN", "unlinked-run", "unlinked-event", 0,
                        WorkEventType.RUN_EVENT_PROJECTED, "RUN_STARTED", "invalid source",
                        Map.of(), created.workItem().workItemId(), "", Instant.now())));
        assertEquals(1, store.loadEvents(principal, created.workItem().workItemId(), -1, 100).size());
    }

    @Test
    void concurrentCrossSourceProjectionAllocatesOneContiguousProductSequence() throws Exception {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "client-projection-concurrent", conversation("projection-concurrent"), 0);
        Map<String, WorkLinkType> sources = Map.of(
                "run-" + testPrefix, WorkLinkType.RUN,
                "incident-" + testPrefix, WorkLinkType.INCIDENT,
                "plan-" + testPrefix, WorkLinkType.RECOVERY_PLAN);
        int link = 0;
        for (var source : sources.entrySet()) {
            insertProjectionLink(created.workItem().workItemId(), "dispatch-projection-" + link++,
                    source.getValue(), source.getKey());
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var source : sources.entrySet()) {
            String sourceType = switch (source.getValue()) {
                case RUN -> "AGENT_RUN";
                case INCIDENT -> "INCIDENT";
                case RECOVERY_PLAN -> "RECOVERY_PLAN";
                default -> throw new IllegalStateException();
            };
            for (int sequence = 0; sequence < 10; sequence++) {
                int sourceSequence = sequence;
                futures.add(CompletableFuture.runAsync(() -> store.appendProjectedEvent(
                        created.workItem().workItemId(), new ProjectedWorkEventDraft(
                                sourceType, source.getKey(), sourceType + "-event-" + sourceSequence,
                                sourceSequence, projectedType(source.getValue()), sourceType, "projected",
                                Map.of("sourceSequence", sourceSequence), created.workItem().workItemId(), "",
                                Instant.now()))));
            }
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);

        var events = store.loadEvents(principal, created.workItem().workItemId(), -1, 100);
        assertEquals(31, events.size());
        assertEquals(31, events.stream().map(event -> event.sequence()).distinct().count());
        assertEquals(IntStream.range(0, 31).mapToObj(value -> (long) value).toList(),
                events.stream().map(event -> event.sequence()).toList());
        assertEquals(30, events.stream().skip(1)
                .map(event -> event.sourceType() + ":" + event.sourceId() + ":" + event.sourceEventId())
                .distinct().count());
    }

    @Test
    void duplicateClientInputReturnsOriginalInputWorkItemRoutingIdAndEvent() {
        JdbcWorkbenchStore store = store();
        String conversationId = conversation("duplicate");

        WorkItemCreationResult first = submit(store, principal, "client-duplicate", conversationId, 0);
        WorkItemCreationResult duplicate = submit(store, principal, "client-duplicate", conversationId, 999);

        assertFalse(first.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(first.input().inputId(), duplicate.input().inputId());
        assertEquals(first.workItem().workItemId(), duplicate.workItem().workItemId());
        assertEquals(first.workItem().routingRequestId(), duplicate.workItem().routingRequestId());
        assertEquals(first.createdEvent().eventId(), duplicate.createdEvent().eventId());
        assertEquals(1, store.loadEvents(principal, first.workItem().workItemId(), -1, 100).size());
    }

    @Test
    void concurrentDuplicateSubmissionCreatesOnlyOneInputAndWorkItem() throws Exception {
        JdbcWorkbenchStore store = store();
        String conversationId = conversation("concurrent-input");

        var first = CompletableFuture.supplyAsync(
                () -> submit(store, principal, "client-concurrent", conversationId, 0));
        var second = CompletableFuture.supplyAsync(
                () -> submit(store, principal, "client-concurrent", conversationId, 0));
        List<WorkItemCreationResult> results = List.of(
                first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

        assertEquals(1, results.stream().map(result -> result.input().inputId()).distinct().count());
        assertEquals(1, results.stream().map(result -> result.workItem().workItemId()).distinct().count());
        assertEquals(1, count("SELECT count(*) FROM agent_work_input WHERE tenant_id = ?",
                principal.tenantId()));
        assertEquals(1, count("SELECT count(*) FROM agent_work_item WHERE tenant_id = ?",
                principal.tenantId()));
    }

    @Test
    void sameClientInputIdDoesNotConflictAcrossPrincipals() {
        JdbcWorkbenchStore store = store();
        AuthenticatedPrincipal bob = principal("tenant-" + testPrefix, "bob");

        WorkItemCreationResult alice = submit(
                store, principal, "shared-client-id", conversation("alice"), 0);
        WorkItemCreationResult bobResult = submit(
                store, bob, "shared-client-id", conversation("bob"), 0);

        assertNotEquals(alice.input().inputId(), bobResult.input().inputId());
        assertNotEquals(alice.workItem().workItemId(), bobResult.workItem().workItemId());
    }

    @Test
    void sameClientInputIdDoesNotConflictAcrossTenants() {
        JdbcWorkbenchStore store = store();
        AuthenticatedPrincipal otherTenant = principal("tenant-m1a-other-" + testPrefix, "alice");

        WorkItemCreationResult first = submit(
                store, principal, "shared-tenant-client-id", conversation("tenant-a"), 0);
        WorkItemCreationResult second = submit(
                store, otherTenant, "shared-tenant-client-id", conversation("tenant-b"), 0);

        assertNotEquals(first.input().inputId(), second.input().inputId());
        assertNotEquals(first.workItem().workItemId(), second.workItem().workItemId());
    }

    @Test
    void sameClientInputWithDifferentPayloadIsRejected() {
        JdbcWorkbenchStore store = store();
        String conversationId = conversation("idempotency-conflict");
        submit(store, principal, "client-conflict", conversationId, 0);

        assertThrows(WorkbenchIdempotencyConflictException.class, () -> service(store).submit(
                principal,
                SubmitWorkInputCommand.direct(
                        "client-conflict", conversationId, "different payload", 1)));
    }

    @Test
    void childCreationPersistsRelationAndDoesNotInheritAnotherIdentity() {
        JdbcWorkbenchStore store = store();
        String conversationId = conversation("relation");
        WorkItemCreationResult parent = submit(store, principal, "parent", conversationId, 0);
        WorkItemCreationResult child = service(store).submit(principal, new SubmitWorkInputCommand(
                "child", conversationId, "follow up", "follow up",
                GoalOrigin.DIRECT_NORMAL_GOAL, "",
                parent.workItem().workItemId(), WorkRelationType.FOLLOW_UP_OF, 1));

        assertNotNull(child.relation());
        assertEquals(child.workItem().workItemId(), child.relation().sourceWorkItemId());
        assertEquals(parent.workItem().workItemId(), child.relation().targetWorkItemId());
        assertEquals(principal.tenantId(), child.workItem().tenantId());
        assertEquals(principal.principalId(), child.workItem().ownerPrincipalId());
        assertEquals(2, child.focus().version());
        assertEquals(1, store.listRelations(principal, parent.workItem().workItemId()).size());
        assertEquals(1, store.listRelations(principal, child.workItem().workItemId()).size());
    }

    @Test
    void repeatedSourceEventIsIdempotentAndDoesNotAdvanceCursorTwice() {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "event-idempotency", conversation("event-idempotency"), 0);
        WorkEventDraft draft = new WorkEventDraft(
                "stable-source-event", WorkEventType.ROUTING_STARTED,
                "ROUTING", "routing claimed", Map.of("attempt", 1), "test-cause");

        var first = store.appendLocalEvent(principal, created.workItem().workItemId(), draft);
        var duplicate = store.appendLocalEvent(principal, created.workItem().workItemId(), draft);

        assertEquals(first.eventId(), duplicate.eventId());
        assertEquals(2, store.loadEvents(principal, created.workItem().workItemId(), -1, 100).size());
        assertEquals(2, store.findWorkItem(principal, created.workItem().workItemId())
                .orElseThrow().nextEventSequence());
    }

    @Test
    void abandonUpdatesControlStateAndAppendsEventInOneTransaction() {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "abandon", conversation("abandon"), 0);

        var abandoned = store.abandon(
                principal, created.workItem().workItemId(), 0, "user-command-1");

        assertEquals(WorkControlState.ABANDONED, abandoned.controlState());
        assertEquals(1, abandoned.version());
        var events = store.loadEvents(principal, abandoned.workItemId(), -1, 100);
        assertEquals(List.of(WorkEventType.WORK_ITEM_CREATED, WorkEventType.WORK_ITEM_ABANDONED),
                events.stream().map(event -> event.eventType()).toList());
        assertEquals(false, events.get(1).payload().get("underlyingExecutionStopped"));
    }

    @Test
    void abandonWithStaleVersionLeavesStateAndEventStreamUnchanged() {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "abandon-stale", conversation("abandon-stale"), 0);

        assertThrows(WorkbenchCasConflictException.class, () -> store.abandon(
                principal, created.workItem().workItemId(), 9, "stale-command"));

        var persisted = store.findWorkItem(principal, created.workItem().workItemId()).orElseThrow();
        assertEquals(WorkControlState.ROUTING, persisted.controlState());
        assertEquals(0, persisted.version());
        assertEquals(1, store.loadEvents(principal, persisted.workItemId(), -1, 100).size());
    }

    @Test
    void eventQueryUsesExclusiveCursorAndBoundedPage() {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "event-page", conversation("event-page"), 0);
        for (int index = 0; index < 3; index++) {
            store.appendLocalEvent(principal, created.workItem().workItemId(), new WorkEventDraft(
                    "page-event-" + index,
                    WorkEventType.ROUTING_STARTED,
                    "ROUTING",
                    "page event " + index,
                    Map.of("index", index),
                    "page-test"));
        }

        var page = store.loadEvents(principal, created.workItem().workItemId(), 0, 2);

        assertEquals(List.of(1L, 2L), page.stream().map(event -> event.sequence()).toList());
    }

    @Test
    void workLinkFailsClosedUntilAuthoritativeDispatchRequestIsBound() {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "link-fail-closed", conversation("link-fail-closed"), 0);
        WorkLink untrustedLink = new WorkLink(
                created.workItem().workItemId(),
                "client-supplied-dispatch-id",
                WorkLinkType.RUN,
                "arbitrary-run-id",
                WorkLinkRelation.PRIMARY,
                Instant.now());

        assertThrows(WorkbenchAccessDeniedException.class,
                () -> store.createLink(principal, untrustedLink));
        assertTrue(store.listLinks(principal, created.workItem().workItemId()).isEmpty());
    }

    @Test
    void focusCasConflictRollsBackInputWorkItemRelationAndEvent() {
        JdbcWorkbenchStore store = store();
        String conversationId = conversation("focus-cas");
        WorkItemCreationResult parent = submit(store, principal, "focus-parent", conversationId, 0);

        assertThrows(WorkbenchCasConflictException.class, () -> service(store).submit(
                principal,
                new SubmitWorkInputCommand(
                        "focus-stale", conversationId, "stale", "stale",
                        GoalOrigin.DIRECT_NORMAL_GOAL, "", parent.workItem().workItemId(),
                        WorkRelationType.FOLLOW_UP_OF, 0)));

        assertTrue(store.findInputByClientId(principal, "focus-stale").isEmpty());
        assertEquals(1, count("SELECT count(*) FROM agent_work_item WHERE tenant_id = ?",
                principal.tenantId()));
        assertEquals(1, count("SELECT count(*) FROM agent_work_event e JOIN agent_work_item w "
                + "ON w.work_item_id=e.work_item_id WHERE w.tenant_id = ?", principal.tenantId()));
    }

    @Test
    void crossTenantRelationIsRejectedAndChildTransactionRollsBack() {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult parent = submit(store, principal, "tenant-parent", conversation("parent"), 0);
        AuthenticatedPrincipal otherTenant = principal("tenant-m1a-other-" + testPrefix, "alice");

        assertThrows(WorkbenchNotFoundException.class, () -> service(store).submit(
                otherTenant,
                new SubmitWorkInputCommand(
                        "cross-tenant-child", conversation("cross-tenant-child"), "child", "child",
                        GoalOrigin.DIRECT_NORMAL_GOAL, "", parent.workItem().workItemId(),
                        WorkRelationType.RECOVERY_OF, 0)));

        assertTrue(store.findInputByClientId(otherTenant, "cross-tenant-child").isEmpty());
    }

    @Test
    void workEventFailureRollsBackEntireCreationTransaction() {
        JdbcWorkbenchStore failing = new JdbcWorkbenchStore(
                properties,
                objectMapper,
                stage -> {
                    if (stage == M1ACommitStage.BEFORE_EVENT_APPEND) {
                        throw new InjectedFailure();
                    }
                });
        String conversationId = conversation("event-failure");

        assertThrows(InjectedFailure.class,
                () -> submit(failing, principal, "event-failure", conversationId, 0));

        JdbcWorkbenchStore verifier = store();
        assertTrue(verifier.findInputByClientId(principal, "event-failure").isEmpty());
        assertTrue(verifier.findConversationState(principal, conversationId).isEmpty());
        assertEquals(0, count("SELECT count(*) FROM agent_work_item WHERE tenant_id = ?",
                principal.tenantId()));
        assertEquals(0, count("SELECT count(*) FROM agent_work_event e JOIN agent_work_item w "
                + "ON w.work_item_id=e.work_item_id WHERE w.tenant_id = ?", principal.tenantId()));
    }

    @Test
    void persistedWorkItemAndEventsSurviveStoreRestart() {
        JdbcWorkbenchStore firstProcess = store();
        WorkItemCreationResult created = submit(
                firstProcess, principal, "restart", conversation("restart"), 0);

        JdbcWorkbenchStore restartedProcess = store();

        assertEquals(created.workItem().workItemId(), restartedProcess
                .findWorkItem(principal, created.workItem().workItemId()).orElseThrow().workItemId());
        assertEquals(List.of(0L), restartedProcess
                .loadEvents(principal, created.workItem().workItemId(), -1, 100)
                .stream().map(event -> event.sequence()).toList());
    }

    @Test
    void concurrentLocalEventAppendUsesLockedCursorWithoutDuplicateSequence() throws Exception {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "event-concurrency", conversation("event-concurrency"), 0);
        int eventCount = 16;
        List<CompletableFuture<Void>> futures = IntStream.range(0, eventCount)
                .mapToObj(index -> CompletableFuture.runAsync(() -> store.appendLocalEvent(
                        principal,
                        created.workItem().workItemId(),
                        new WorkEventDraft(
                                "concurrent-event-" + index,
                                WorkEventType.ROUTING_STARTED,
                                "TEST",
                                "concurrent append",
                                Map.of("index", index),
                                "test-" + index))))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);

        var events = store.loadEvents(principal, created.workItem().workItemId(), -1, 100);
        assertEquals(eventCount + 1, events.size());
        assertEquals(eventCount + 1, new HashSet<>(events.stream().map(event -> event.sequence()).toList()).size());
        assertEquals(IntStream.rangeClosed(0, eventCount).mapToLong(value -> value).boxed().toList(),
                events.stream().map(event -> event.sequence()).toList());
    }

    @Test
    void databaseUniqueConstraintsRejectDuplicateSequenceAndSourceEvent() throws Exception {
        JdbcWorkbenchStore store = store();
        WorkItemCreationResult created = submit(
                store, principal, "constraints", conversation("constraints"), 0);
        String workItemId = created.workItem().workItemId();

        try (Connection connection = openConnection()) {
            assertEquals("23505", assertThrows(SQLException.class, () -> insertRawEvent(
                    connection, "duplicate-sequence", workItemId, 0, "another-source")).getSQLState());
            assertEquals("23505", assertThrows(SQLException.class, () -> insertRawEvent(
                    connection, "duplicate-source", workItemId, 99,
                    created.createdEvent().sourceEventId())).getSQLState());
        }
    }

    @Test
    void conversationOwnershipIsPrincipalScopedAndCrossConversationFocusFailsClosed() {
        JdbcWorkbenchStore store = store();
        String firstConversation = conversation("owned");
        WorkItemCreationResult first = submit(store, principal, "owned", firstConversation, 0);
        AuthenticatedPrincipal intruder = principal(principal.tenantId(), "mallory");

        WorkItemCreationResult intruderWork = submit(store, intruder, "intrude", firstConversation, 0);
        assertNotEquals(first.workItem().workItemId(), intruderWork.workItem().workItemId());
        assertEquals(intruderWork.workItem().workItemId(),
                store.findConversationState(intruder, firstConversation).orElseThrow().focusedWorkItemId());

        WorkItemCreationResult second = submit(
                store, principal, "other-conversation", conversation("other"), 0);
        assertThrows(WorkbenchAccessDeniedException.class, () -> store.switchFocus(
                principal,
                firstConversation,
                second.workItem().workItemId(),
                first.focus().version()));
        assertEquals(first.workItem().workItemId(), store
                .findConversationState(principal, firstConversation).orElseThrow().focusedWorkItemId());
    }

    private WorkEventType projectedType(WorkLinkType linkType) {
        return switch (linkType) {
            case RUN -> WorkEventType.RUN_EVENT_PROJECTED;
            case INCIDENT -> WorkEventType.INCIDENT_EVENT_PROJECTED;
            case RECOVERY_PLAN -> WorkEventType.RECOVERY_PLAN_EVENT_PROJECTED;
            default -> throw new IllegalArgumentException("unsupported projection link: " + linkType);
        };
    }

    private JdbcWorkbenchStore store() {
        return new JdbcWorkbenchStore(properties, objectMapper);
    }

    private WorkInputService service(JdbcWorkbenchStore store) {
        return new WorkInputService(new WorkItemService(store));
    }

    private WorkItemCreationResult submit(JdbcWorkbenchStore store,
                                          AuthenticatedPrincipal actor,
                                          String clientInputId,
                                          String conversationId,
                                          long expectedVersion) {
        return service(store).submit(actor, SubmitWorkInputCommand.direct(
                clientInputId, conversationId, "goal-" + clientInputId, expectedVersion));
    }

    private String conversation(String suffix) {
        return "conversation-" + testPrefix + "-" + suffix;
    }

    private AuthenticatedPrincipal principal(String tenantId, String principalId) {
        return new AuthenticatedPrincipal(tenantId, principalId, Set.of("USER"));
    }

    private AgentStorageProperties properties() {
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

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword());
    }

    private long count(String sql, String value) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to count M1-A rows", exception);
        }
    }

    private void insertRawEvent(Connection connection,
                                String eventId,
                                String workItemId,
                                long sequence,
                                String sourceEventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_work_event(
                    event_id, work_item_id, sequence, source_type, source_id, source_event_id,
                    source_sequence, event_type, phase, summary, payload,
                    correlation_id, causation_id, source_created_at, projected_at
                ) VALUES (?, ?, ?, 'WORK_ITEM', ?, ?, ?, 'ROUTING_STARTED',
                          'TEST', 'constraint', '{}'::jsonb, ?, 'test', ?, ?)
                """)) {
            statement.setString(1, eventId + "-" + testPrefix);
            statement.setString(2, workItemId);
            statement.setLong(3, sequence);
            statement.setString(4, workItemId);
            statement.setString(5, sourceEventId);
            statement.setLong(6, sequence);
            statement.setString(7, workItemId);
            statement.setTimestamp(8, java.sql.Timestamp.from(Instant.now()));
            statement.setTimestamp(9, java.sql.Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private void insertProjectionLink(String workItemId,
                                      String dispatchRequestId,
                                      WorkLinkType linkType,
                                      String linkedId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_work_link(
                         work_item_id, dispatch_request_id, link_type, linked_id, relation, created_at
                     ) VALUES (?, ?, ?, ?, 'PRIMARY', ?)
                     """)) {
            statement.setString(1, workItemId);
            statement.setString(2, dispatchRequestId + "-" + testPrefix);
            statement.setString(3, linkType.name());
            statement.setString(4, linkedId);
            statement.setTimestamp(5, java.sql.Timestamp.from(Instant.now()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AgentStorageException("Failed to create projection test link", exception);
        }
    }

    private void execute(Connection connection, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static final class InjectedFailure extends RuntimeException {
    }
}
