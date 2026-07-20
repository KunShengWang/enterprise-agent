package com.agent.platform.workbench.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import com.agent.platform.workbench.application.CreateWorkItemCommand;
import com.agent.platform.workbench.application.CreatePersistedInputWorkItemCommand;
import com.agent.platform.workbench.application.WorkItemCreationResult;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.InputClassificationStatus;
import com.agent.platform.workbench.model.NormalGoalEnvelope;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkEventDraft;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkInputKind;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.model.WorkLinkRelation;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkRelation;
import com.agent.platform.workbench.model.WorkRelationType;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M1-A aggregate store. Operations that must be atomic deliberately share one JDBC connection.
 */
@Repository
public class JdbcWorkbenchStore implements WorkbenchStore {

    private static final String SOURCE_TYPE_WORK_ITEM = "WORK_ITEM";
    private static final int MAX_QUERY_LIMIT = 10_000;

    private final AgentStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final M1ACommitFailureInjector failureInjector;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    @Autowired
    public JdbcWorkbenchStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, M1ACommitFailureInjector.NOOP);
    }

    JdbcWorkbenchStore(AgentStorageProperties properties,
                       ObjectMapper objectMapper,
                       M1ACommitFailureInjector failureInjector) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.failureInjector = failureInjector == null ? M1ACommitFailureInjector.NOOP : failureInjector;
    }

    @Override
    public WorkItemCreationResult createWorkItem(AuthenticatedPrincipal principal,
                                                 CreateWorkItemCommand command) {
        requirePrincipal(principal);
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ConversationWorkState lockedFocus = ensureAndLockConversation(
                        connection, principal, command.conversationId());
                String requestDigest = requestDigest(command);
                insertInputIfAbsent(connection, principal, command, requestDigest);
                AgentConversationTurn input = readInputByClientId(
                        connection, principal, command.clientInputId(), true).orElseThrow();
                if (!input.requestDigest().equals(requestDigest)
                        || !input.conversationId().equals(command.conversationId())) {
                    throw new WorkbenchIdempotencyConflictException(
                            "clientInputId was already used with a different payload: " + command.clientInputId());
                }
                failureInjector.after(M1ACommitStage.INPUT_PERSISTED);

                Optional<AgentWorkItem> existing = readWorkItemBySourceInput(
                        connection, principal, input.inputId(), false);
                if (existing.isPresent()) {
                    WorkItemCreationResult result = duplicateResult(connection, input, existing.get(), principal);
                    connection.commit();
                    return result;
                }

                if (lockedFocus.version() != command.expectedFocusVersion()) {
                    throw new WorkbenchCasConflictException(
                            "conversation focus version mismatch: expected=" + command.expectedFocusVersion()
                                    + ", actual=" + lockedFocus.version());
                }

                AgentWorkItem parent = validateParent(connection, principal, command);
                Instant now = Instant.now();
                String workItemId = "work-" + UUID.randomUUID();
                String routingRequestId = "route-" + UUID.randomUUID();
                AgentWorkItem created = new AgentWorkItem(
                        workItemId,
                        command.conversationId(),
                        principal.tenantId(),
                        principal.principalId(),
                        command.content(),
                        command.goal().goalText(),
                        WorkControlState.ROUTING,
                        WorkExecutionState.NOT_STARTED,
                        WorkOutcome.UNDETERMINED,
                        "", "", "", "", "",
                        input.inputId(),
                        parent == null ? "" : parent.workItemId(),
                        routingRequestId,
                        0, null, null, "", "",
                        0, 0, now, now, null
                );
                insertWorkItem(connection, created);
                failureInjector.after(M1ACommitStage.WORK_ITEM_PERSISTED);

                WorkRelation relation = null;
                if (parent != null) {
                    relation = new WorkRelation(
                            created.workItemId(), parent.workItemId(), command.goal().relationType(),
                            input.inputId(), now
                    );
                    insertRelation(connection, relation);
                }
                failureInjector.after(M1ACommitStage.RELATION_PERSISTED);

                ConversationWorkState focus = updateFocus(
                        connection, principal, command.conversationId(), created.workItemId(),
                        command.expectedFocusVersion(), now);
                failureInjector.after(M1ACommitStage.FOCUS_UPDATED);
                failureInjector.after(M1ACommitStage.BEFORE_EVENT_APPEND);

                WorkEvent createdEvent = appendLocalEvent(
                        connection,
                        principal,
                        created,
                        new WorkEventDraft(
                                "work-item-created:" + input.inputId(),
                                WorkEventType.WORK_ITEM_CREATED,
                                "CREATED",
                                "WorkItem created and focused",
                                Map.of(
                                        "inputId", input.inputId(),
                                        "routingRequestId", routingRequestId,
                                        "goalOrigin", input.goalOrigin().name()
                                ),
                                input.inputId()
                        )
                );
                failureInjector.after(M1ACommitStage.EVENT_APPENDED);
                failureInjector.after(M1ACommitStage.BEFORE_COMMIT);
                connection.commit();

                AgentWorkItem persisted = readWorkItemById(created.workItemId(), principal).orElseThrow();
                return new WorkItemCreationResult(input, persisted, relation, focus, createdEvent, false);
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to create work item", exception);
        }
    }

    @Override
    public WorkItemCreationResult createWorkItemFromPersistedInput(
            AuthenticatedPrincipal principal,
            CreatePersistedInputWorkItemCommand persistedCommand) {
        requirePrincipal(principal);
        if (persistedCommand == null) throw new IllegalArgumentException("command must not be null");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentConversationTurn input = readInputById(
                        connection, principal, persistedCommand.inputId(), true)
                        .orElseThrow(() -> new WorkbenchNotFoundException(
                                "work input not found: " + persistedCommand.inputId()));
                boolean directGoal = input.inputKind() == WorkInputKind.NORMAL_GOAL
                        && persistedCommand.goalOrigin() == GoalOrigin.DIRECT_NORMAL_GOAL;
                boolean derivedGoal = input.inputKind() == WorkInputKind.WORK_COMMAND
                        && input.commandType() == WorkCommandType.START_NEW_WORK
                        && persistedCommand.goalOrigin() == GoalOrigin.DERIVED_FROM_START_NEW_WORK
                        && input.commandDecisionId().equals(persistedCommand.commandDecisionId());
                if ((!directGoal && !derivedGoal)
                        || input.classificationStatus() != InputClassificationStatus.CLASSIFIED) {
                    throw new WorkbenchCasConflictException("input is not an effective NORMAL_GOAL");
                }
                ConversationWorkState lockedFocus = ensureAndLockConversation(
                        connection, principal, input.conversationId());
                NormalGoalEnvelope envelope = new NormalGoalEnvelope(
                        input.inputId(), persistedCommand.goalText(), persistedCommand.goalOrigin(),
                        persistedCommand.commandDecisionId(), persistedCommand.parentWorkItemId(),
                        persistedCommand.relationType());
                CreateWorkItemCommand command = new CreateWorkItemCommand(
                        input.clientInputId(), input.conversationId(), input.content(), envelope,
                        persistedCommand.expectedFocusVersion());

                Optional<AgentWorkItem> existing = readWorkItemBySourceInput(
                        connection, principal, input.inputId(), false);
                if (existing.isPresent()) {
                    WorkItemCreationResult result = duplicateResult(connection, input, existing.get(), principal);
                    connection.commit();
                    return result;
                }
                if (lockedFocus.version() != command.expectedFocusVersion()) {
                    throw new WorkbenchCasConflictException(
                            "conversation focus version mismatch: expected=" + command.expectedFocusVersion()
                                    + ", actual=" + lockedFocus.version());
                }
                AgentWorkItem parent = validateParent(connection, principal, command);
                Instant now = Instant.now();
                String workItemId = "work-" + UUID.randomUUID();
                String routingRequestId = "route-" + UUID.randomUUID();
                AgentWorkItem created = new AgentWorkItem(
                        workItemId, input.conversationId(), principal.tenantId(), principal.principalId(),
                        input.content(), envelope.goalText(), WorkControlState.ROUTING,
                        WorkExecutionState.NOT_STARTED, WorkOutcome.UNDETERMINED,
                        "", "", "", "", "", input.inputId(),
                        parent == null ? "" : parent.workItemId(), routingRequestId,
                        0, null, null, "", "", 0, 0, now, now, null);
                insertWorkItem(connection, created);
                WorkRelation relation = null;
                if (parent != null) {
                    relation = new WorkRelation(
                            created.workItemId(), parent.workItemId(), envelope.relationType(), input.inputId(), now);
                    insertRelation(connection, relation);
                }
                ConversationWorkState focus = updateFocus(
                        connection, principal, input.conversationId(), created.workItemId(),
                        command.expectedFocusVersion(), now);
                WorkEvent createdEvent = appendLocalEvent(connection, principal, created, new WorkEventDraft(
                        "work-item-created:" + input.inputId(), WorkEventType.WORK_ITEM_CREATED,
                        "CREATED", "WorkItem created and focused",
                        Map.of("inputId", input.inputId(), "routingRequestId", routingRequestId,
                                "goalOrigin", envelope.goalOrigin().name()), input.inputId()));
                connection.commit();
                AgentWorkItem persisted = readWorkItemById(created.workItemId(), principal).orElseThrow();
                return new WorkItemCreationResult(input, persisted, relation, focus, createdEvent, false);
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to create work item from persisted input", exception);
        }
    }

    @Override
    public Optional<AgentConversationTurn> findInput(AuthenticatedPrincipal principal, String inputId) {
        requirePrincipal(principal);
        if (!hasText(inputId)) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM agent_work_input
                     WHERE input_id = ? AND tenant_id = ? AND owner_principal_id = ?
                     """)) {
            statement.setString(1, inputId.trim());
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapInput(resultSet)) : Optional.empty();
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to find work input", exception);
        }
    }

    @Override
    public Optional<AgentConversationTurn> findInputByClientId(AuthenticatedPrincipal principal,
                                                                String clientInputId) {
        requirePrincipal(principal);
        if (!hasText(clientInputId)) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            return readInputByClientId(connection, principal, clientInputId.trim(), false);
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to find work input by client id", exception);
        }
    }

    @Override
    public List<AgentConversationTurn> listInputs(AuthenticatedPrincipal principal,
                                                   String conversationId,
                                                   int limit) {
        requirePrincipal(principal);
        if (!hasText(conversationId)) return List.of();
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM agent_work_input
                     WHERE conversation_id=? AND tenant_id=? AND owner_principal_id=?
                     ORDER BY created_at ASC LIMIT ?
                     """)) {
            statement.setString(1, conversationId.trim());
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            statement.setInt(4, Math.max(1, Math.min(limit, MAX_QUERY_LIMIT)));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentConversationTurn> result = new ArrayList<>();
                while (resultSet.next()) result.add(mapInput(resultSet));
                return List.copyOf(result);
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to list conversation inputs", exception);
        }
    }

    @Override
    public Optional<AgentWorkItem> findWorkItem(AuthenticatedPrincipal principal, String workItemId) {
        requirePrincipal(principal);
        if (!hasText(workItemId)) {
            return Optional.empty();
        }
        ensureSchema();
        return readWorkItemById(workItemId.trim(), principal);
    }

    @Override
    public List<AgentWorkItem> listWorkItems(AuthenticatedPrincipal principal,
                                             String conversationId,
                                             int limit) {
        requirePrincipal(principal);
        if (!hasText(conversationId)) return List.of();
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM agent_work_item
                     WHERE conversation_id=? AND tenant_id=? AND owner_principal_id=?
                     ORDER BY created_at DESC LIMIT ?
                     """)) {
            statement.setString(1, conversationId.trim());
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            statement.setInt(4, Math.max(1, Math.min(limit, MAX_QUERY_LIMIT)));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentWorkItem> result = new ArrayList<>();
                while (resultSet.next()) result.add(mapWorkItem(resultSet));
                return List.copyOf(result);
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to list conversation work items", exception);
        }
    }

    @Override
    public Optional<ConversationWorkState> findConversationState(AuthenticatedPrincipal principal,
                                                                  String conversationId) {
        requirePrincipal(principal);
        if (!hasText(conversationId)) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            return readConversation(connection, principal, conversationId.trim(), false);
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to find conversation work state", exception);
        }
    }

    @Override
    public ConversationWorkState switchFocus(AuthenticatedPrincipal principal,
                                             String conversationId,
                                             String workItemId,
                                             long expectedVersion) {
        requirePrincipal(principal);
        if (!hasText(conversationId) || !hasText(workItemId) || expectedVersion < 0) {
            throw new IllegalArgumentException("conversationId, workItemId and expectedVersion are required");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ConversationWorkState current = readConversation(
                        connection, principal, conversationId.trim(), true)
                        .orElseThrow(() -> new WorkbenchNotFoundException(
                                "conversation work state not found: " + conversationId));
                AgentWorkItem target = readWorkItemById(connection, principal, workItemId.trim(), true)
                        .orElseThrow(() -> new WorkbenchNotFoundException("work item not found: " + workItemId));
                if (!target.conversationId().equals(current.conversationId())) {
                    throw new WorkbenchAccessDeniedException("focus target belongs to another conversation");
                }
                ConversationWorkState updated = updateFocus(
                        connection, principal, conversationId.trim(), workItemId.trim(), expectedVersion, Instant.now());
                connection.commit();
                return updated;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to switch conversation focus", exception);
        }
    }

    @Override
    public WorkEvent appendLocalEvent(AuthenticatedPrincipal principal,
                                      String workItemId,
                                      WorkEventDraft event) {
        requirePrincipal(principal);
        if (!hasText(workItemId) || event == null) {
            throw new IllegalArgumentException("workItemId and event are required");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem locked = readWorkItemById(connection, principal, workItemId.trim(), true)
                        .orElseThrow(() -> new WorkbenchNotFoundException("work item not found: " + workItemId));
                Optional<WorkEvent> duplicate = readEventBySource(
                        connection, locked.workItemId(), event.sourceEventId());
                if (duplicate.isPresent()) {
                    connection.commit();
                    return duplicate.get();
                }
                WorkEvent persisted = appendLocalEvent(connection, principal, locked, event);
                connection.commit();
                return persisted;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to append local work event", exception);
        }
    }

    @Override
    public AgentWorkItem abandon(AuthenticatedPrincipal principal,
                                 String workItemId,
                                 long expectedVersion,
                                 String causationId) {
        requirePrincipal(principal);
        if (!hasText(workItemId) || expectedVersion < 0) {
            throw new IllegalArgumentException("workItemId and expectedVersion are required");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem locked = readWorkItemById(connection, principal, workItemId.trim(), true)
                        .orElseThrow(() -> new WorkbenchNotFoundException("work item not found: " + workItemId));
                if (locked.version() != expectedVersion) {
                    throw new WorkbenchCasConflictException("work item version mismatch");
                }
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_item
                        SET control_state = 'ABANDONED', version = version + 1, updated_at = ?
                        WHERE work_item_id = ? AND tenant_id = ? AND owner_principal_id = ? AND version = ?
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(now));
                    statement.setString(2, locked.workItemId());
                    statement.setString(3, principal.tenantId());
                    statement.setString(4, principal.principalId());
                    statement.setLong(5, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new WorkbenchCasConflictException("work item version changed while abandoning");
                    }
                }
                appendLocalEvent(connection, principal, locked, new WorkEventDraft(
                        "work-item-abandoned:" + normalize(causationId, UUID.randomUUID().toString()),
                        WorkEventType.WORK_ITEM_ABANDONED,
                        "ABANDONED",
                        "User stopped focusing this WorkItem; underlying execution is unchanged",
                        Map.of("underlyingExecutionStopped", false),
                        normalize(causationId, "")
                ));
                connection.commit();
                return readWorkItemById(locked.workItemId(), principal).orElseThrow();
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to abandon work item", exception);
        }
    }

    @Override
    public List<WorkEvent> loadEvents(AuthenticatedPrincipal principal,
                                      String workItemId,
                                      long afterSequence,
                                      int limit) {
        AgentWorkItem workItem = requireOwnedWorkItem(principal, workItemId);
        int safeLimit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT e.* FROM agent_work_event e
                     JOIN agent_work_item w ON w.work_item_id = e.work_item_id
                     WHERE e.work_item_id = ? AND w.tenant_id = ? AND w.owner_principal_id = ?
                       AND e.sequence > ?
                     ORDER BY e.sequence ASC
                     LIMIT ?
                     """)) {
            statement.setString(1, workItem.workItemId());
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            statement.setLong(4, afterSequence);
            statement.setInt(5, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<WorkEvent> events = new ArrayList<>();
                while (resultSet.next()) {
                    events.add(mapEvent(resultSet));
                }
                return List.copyOf(events);
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to load work events", exception);
        }
    }

    @Override
    public List<WorkRelation> listRelations(AuthenticatedPrincipal principal, String workItemId) {
        AgentWorkItem workItem = requireOwnedWorkItem(principal, workItemId);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT r.* FROM agent_work_relation r
                     JOIN agent_work_item w ON w.work_item_id = ?
                     WHERE w.tenant_id = ? AND w.owner_principal_id = ?
                       AND (r.source_work_item_id = w.work_item_id OR r.target_work_item_id = w.work_item_id)
                     ORDER BY r.created_at ASC
                     """)) {
            statement.setString(1, workItem.workItemId());
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<WorkRelation> relations = new ArrayList<>();
                while (resultSet.next()) {
                    WorkRelation relation = mapRelation(resultSet);
                    verifyRelatedWorkItems(connection, principal, relation);
                    relations.add(relation);
                }
                return List.copyOf(relations);
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to list work relations", exception);
        }
    }

    @Override
    public WorkLink createLink(AuthenticatedPrincipal principal, WorkLink link) {
        requirePrincipal(principal);
        if (link == null || !hasText(link.workItemId()) || link.linkType() == null
                || !hasText(link.dispatchRequestId()) || !hasText(link.linkedId())
                || link.relation() == null) {
            throw new IllegalArgumentException("valid link is required");
        }
        AgentWorkItem workItem = requireOwnedWorkItem(principal, link.workItemId());
        if (!hasText(workItem.dispatchRequestId())
                || !workItem.dispatchRequestId().equals(link.dispatchRequestId().trim())) {
            throw new WorkbenchAccessDeniedException(
                    "WorkLink must use the authoritative dispatchRequestId bound to its WorkItem");
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_work_link(
                         work_item_id, dispatch_request_id, link_type, linked_id, relation, created_at
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT(work_item_id, link_type, linked_id) DO NOTHING
                     """)) {
            Instant now = link.createdAt() == null ? Instant.now() : link.createdAt();
            statement.setString(1, link.workItemId());
            statement.setString(2, blankToNull(link.dispatchRequestId()));
            statement.setString(3, link.linkType().name());
            statement.setString(4, link.linkedId().trim());
            statement.setString(5, link.relation().name());
            statement.setTimestamp(6, Timestamp.from(now));
            statement.executeUpdate();
            return findLink(connection, principal, link.workItemId(), link.linkType(), link.linkedId())
                    .orElseThrow(() -> new AgentStorageException("Failed to read persisted work link", null));
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to create work link", exception);
        }
    }

    @Override
    public List<WorkLink> listLinks(AuthenticatedPrincipal principal, String workItemId) {
        AgentWorkItem workItem = requireOwnedWorkItem(principal, workItemId);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT l.* FROM agent_work_link l
                     JOIN agent_work_item w ON w.work_item_id = l.work_item_id
                     WHERE l.work_item_id = ? AND w.tenant_id = ? AND w.owner_principal_id = ?
                     ORDER BY l.created_at ASC
                     """)) {
            statement.setString(1, workItem.workItemId());
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<WorkLink> links = new ArrayList<>();
                while (resultSet.next()) {
                    links.add(mapLink(resultSet));
                }
                return List.copyOf(links);
            }
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to list work links", exception);
        }
    }

    private WorkItemCreationResult duplicateResult(Connection connection,
                                                   AgentConversationTurn input,
                                                   AgentWorkItem workItem,
                                                   AuthenticatedPrincipal principal) throws SQLException {
        WorkRelation relation = findSourceRelation(connection, workItem.workItemId()).orElse(null);
        ConversationWorkState focus = readConversation(
                connection, principal, workItem.conversationId(), false)
                .orElseThrow(() -> new AgentStorageException("Conversation focus missing for duplicate input", null));
        WorkEvent createdEvent = readEventByType(
                connection, workItem.workItemId(), WorkEventType.WORK_ITEM_CREATED)
                .orElseThrow(() -> new AgentStorageException("WORK_ITEM_CREATED missing for duplicate input", null));
        return new WorkItemCreationResult(input, workItem, relation, focus, createdEvent, true);
    }

    private ConversationWorkState ensureAndLockConversation(Connection connection,
                                                            AuthenticatedPrincipal principal,
                                                            String conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_conversation_work_state(
                    conversation_id, tenant_id, owner_principal_id,
                    focused_work_item_id, version, updated_at
                ) VALUES (?, ?, ?, NULL, 0, ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, conversationId);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        ConversationWorkState state = readConversation(connection, principal, conversationId, true)
                .orElseThrow(() -> new AgentStorageException("Conversation state disappeared", null));
        return state;
    }

    private void insertInputIfAbsent(Connection connection,
                                     AuthenticatedPrincipal principal,
                                     CreateWorkItemCommand command,
                                     String requestDigest) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_work_input(
                    input_id, client_input_id, conversation_id, tenant_id, owner_principal_id,
                    content, content_digest, request_digest, goal_origin, command_decision_id,
                    parent_work_item_id, relation_type, created_at, principal_roles
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT(tenant_id, owner_principal_id, client_input_id) DO NOTHING
                """)) {
            statement.setString(1, command.goal().sourceInputId());
            statement.setString(2, command.clientInputId());
            statement.setString(3, command.conversationId());
            statement.setString(4, principal.tenantId());
            statement.setString(5, principal.principalId());
            statement.setString(6, command.content());
            statement.setString(7, sha256(command.content()));
            statement.setString(8, requestDigest);
            statement.setString(9, command.goal().goalOrigin().name());
            statement.setString(10, blankToNull(command.goal().commandDecisionId()));
            statement.setString(11, blankToNull(command.goal().parentWorkItemId()));
            statement.setString(12, command.goal().relationType() == null
                    ? null : command.goal().relationType().name());
            statement.setTimestamp(13, Timestamp.from(Instant.now()));
            statement.setString(14, toJson(principal.roles()));
            statement.executeUpdate();
        }
    }

    private Optional<AgentConversationTurn> readInputByClientId(Connection connection,
                                                                 AuthenticatedPrincipal principal,
                                                                 String clientInputId,
                                                                 boolean forUpdate) throws SQLException {
        String sql = """
                SELECT * FROM agent_work_input
                WHERE tenant_id = ? AND owner_principal_id = ? AND client_input_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal.tenantId());
            statement.setString(2, principal.principalId());
            statement.setString(3, clientInputId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapInput(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<AgentConversationTurn> readInputById(Connection connection,
                                                           AuthenticatedPrincipal principal,
                                                           String inputId,
                                                           boolean forUpdate) throws SQLException {
        String sql = "SELECT * FROM agent_work_input WHERE input_id = ? AND tenant_id = ? AND owner_principal_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, inputId);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapInput(resultSet)) : Optional.empty();
            }
        }
    }

    private AgentWorkItem validateParent(Connection connection,
                                         AuthenticatedPrincipal principal,
                                         CreateWorkItemCommand command) throws SQLException {
        if (!hasText(command.goal().parentWorkItemId())) {
            return null;
        }
        AgentWorkItem parent = readWorkItemById(
                connection, principal, command.goal().parentWorkItemId(), true)
                .orElseThrow(() -> new WorkbenchNotFoundException(
                        "parent work item not found: " + command.goal().parentWorkItemId()));
        return parent;
    }

    private void insertWorkItem(Connection connection, AgentWorkItem item) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_work_item(
                    work_item_id, conversation_id, tenant_id, owner_principal_id,
                    original_goal, normalized_goal, control_state, execution_state, outcome,
                    active_execution_target, active_run_id, active_incident_id,
                    active_recovery_plan_id, route_decision_id, source_input_id,
                    parent_work_item_id, routing_request_id, routing_attempt_count,
                    routing_last_attempt_at, routing_next_retry_at, routing_failure_code,
                    dispatch_request_id, next_event_sequence, version,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int index = 1;
            statement.setString(index++, item.workItemId());
            statement.setString(index++, item.conversationId());
            statement.setString(index++, item.tenantId());
            statement.setString(index++, item.ownerPrincipalId());
            statement.setString(index++, item.originalGoal());
            statement.setString(index++, item.normalizedGoal());
            statement.setString(index++, item.controlState().name());
            statement.setString(index++, item.executionState().name());
            statement.setString(index++, item.outcome().name());
            statement.setString(index++, blankToNull(item.activeExecutionTarget()));
            statement.setString(index++, blankToNull(item.activeRunId()));
            statement.setString(index++, blankToNull(item.activeIncidentId()));
            statement.setString(index++, blankToNull(item.activeRecoveryPlanId()));
            statement.setString(index++, blankToNull(item.routeDecisionId()));
            statement.setString(index++, item.sourceInputId());
            statement.setString(index++, blankToNull(item.parentWorkItemId()));
            statement.setString(index++, item.routingRequestId());
            statement.setInt(index++, item.routingAttemptCount());
            setTimestamp(statement, index++, item.routingLastAttemptAt());
            setTimestamp(statement, index++, item.routingNextRetryAt());
            statement.setString(index++, blankToNull(item.routingFailureCode()));
            statement.setString(index++, blankToNull(item.dispatchRequestId()));
            statement.setLong(index++, item.nextEventSequence());
            statement.setLong(index++, item.version());
            setTimestamp(statement, index++, item.createdAt());
            setTimestamp(statement, index++, item.updatedAt());
            setTimestamp(statement, index, item.completedAt());
            statement.executeUpdate();
        }
    }

    private void insertRelation(Connection connection, WorkRelation relation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_work_relation(
                    source_work_item_id, target_work_item_id, relation_type,
                    created_by_input_id, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, relation.sourceWorkItemId());
            statement.setString(2, relation.targetWorkItemId());
            statement.setString(3, relation.relationType().name());
            statement.setString(4, relation.createdByInputId());
            statement.setTimestamp(5, Timestamp.from(relation.createdAt()));
            statement.executeUpdate();
        }
    }

    private ConversationWorkState updateFocus(Connection connection,
                                               AuthenticatedPrincipal principal,
                                               String conversationId,
                                               String workItemId,
                                               long expectedVersion,
                                               Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_conversation_work_state
                SET focused_work_item_id = ?, version = version + 1, updated_at = ?
                WHERE conversation_id = ? AND tenant_id = ? AND owner_principal_id = ? AND version = ?
                """)) {
            statement.setString(1, workItemId);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, conversationId);
            statement.setString(4, principal.tenantId());
            statement.setString(5, principal.principalId());
            statement.setLong(6, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new WorkbenchCasConflictException("conversation focus CAS failed");
            }
        }
        return readConversation(connection, principal, conversationId, false).orElseThrow();
    }

    private WorkEvent appendLocalEvent(Connection connection,
                                       AuthenticatedPrincipal principal,
                                       AgentWorkItem knownWorkItem,
                                       WorkEventDraft draft) throws SQLException {
        AgentWorkItem locked = readWorkItemById(
                connection, principal, knownWorkItem.workItemId(), true)
                .orElseThrow(() -> new WorkbenchNotFoundException(
                        "work item not found while appending event: " + knownWorkItem.workItemId()));
        Optional<WorkEvent> duplicate = readEventBySource(
                connection, locked.workItemId(), draft.sourceEventId());
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        Instant now = Instant.now();
        long sequence = locked.nextEventSequence();
        WorkEvent event = new WorkEvent(
                "wevt-" + UUID.randomUUID(),
                locked.workItemId(),
                sequence,
                SOURCE_TYPE_WORK_ITEM,
                locked.workItemId(),
                draft.sourceEventId(),
                sequence,
                draft.eventType(),
                draft.phase(),
                draft.summary(),
                draft.payload(),
                locked.workItemId(),
                draft.causationId(),
                now,
                now
        );
        insertEvent(connection, event);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_work_item SET next_event_sequence = ?, updated_at = ?
                WHERE work_item_id = ?
                """)) {
            statement.setLong(1, sequence + 1);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, locked.workItemId());
            statement.executeUpdate();
        }
        return event;
    }

    private void insertEvent(Connection connection, WorkEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_work_event(
                    event_id, work_item_id, sequence, source_type, source_id, source_event_id,
                    source_sequence, event_type, phase, summary, payload,
                    correlation_id, causation_id, source_created_at, projected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.eventId());
            statement.setString(2, event.workItemId());
            statement.setLong(3, event.sequence());
            statement.setString(4, event.sourceType());
            statement.setString(5, event.sourceId());
            statement.setString(6, event.sourceEventId());
            if (event.sourceSequence() == null) {
                statement.setObject(7, null);
            }
            else {
                statement.setLong(7, event.sourceSequence());
            }
            statement.setString(8, event.eventType().name());
            statement.setString(9, blankToNull(event.phase()));
            statement.setString(10, blankToNull(event.summary()));
            statement.setString(11, toJson(event.payload()));
            statement.setString(12, event.correlationId());
            statement.setString(13, blankToNull(event.causationId()));
            statement.setTimestamp(14, Timestamp.from(event.sourceCreatedAt()));
            statement.setTimestamp(15, Timestamp.from(event.projectedAt()));
            statement.executeUpdate();
        }
    }

    private Optional<ConversationWorkState> readConversation(Connection connection,
                                                              AuthenticatedPrincipal principal,
                                                              String conversationId,
                                                              boolean forUpdate) throws SQLException {
        String sql = """
                SELECT * FROM agent_conversation_work_state
                WHERE conversation_id = ? AND tenant_id = ? AND owner_principal_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapConversation(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<AgentWorkItem> readWorkItemById(String workItemId,
                                                      AuthenticatedPrincipal principal) {
        try (Connection connection = openConnection()) {
            return readWorkItemById(connection, principal, workItemId, false);
        }
        catch (SQLException exception) {
            throw storageFailure("Failed to read work item", exception);
        }
    }

    private Optional<AgentWorkItem> readWorkItemById(Connection connection,
                                                      AuthenticatedPrincipal principal,
                                                      String workItemId,
                                                      boolean forUpdate) throws SQLException {
        String sql = "SELECT * FROM agent_work_item"
                + " WHERE work_item_id = ? AND tenant_id = ? AND owner_principal_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workItemId);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapWorkItem(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<AgentWorkItem> readWorkItemBySourceInput(Connection connection,
                                                               AuthenticatedPrincipal principal,
                                                               String inputId,
                                                               boolean forUpdate) throws SQLException {
        String sql = "SELECT * FROM agent_work_item"
                + " WHERE source_input_id = ? AND tenant_id = ? AND owner_principal_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, inputId);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapWorkItem(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<WorkRelation> findSourceRelation(Connection connection,
                                                       String sourceWorkItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_work_relation WHERE source_work_item_id = ?
                ORDER BY created_at ASC LIMIT 1
                """)) {
            statement.setString(1, sourceWorkItemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRelation(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<WorkEvent> readEventBySource(Connection connection,
                                                   String workItemId,
                                                   String sourceEventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_work_event
                WHERE work_item_id = ? AND source_type = 'WORK_ITEM'
                  AND source_id = ? AND source_event_id = ?
                """)) {
            statement.setString(1, workItemId);
            statement.setString(2, workItemId);
            statement.setString(3, sourceEventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapEvent(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<WorkEvent> readEventByType(Connection connection,
                                                 String workItemId,
                                                 WorkEventType eventType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_work_event
                WHERE work_item_id = ? AND event_type = ?
                ORDER BY sequence ASC LIMIT 1
                """)) {
            statement.setString(1, workItemId);
            statement.setString(2, eventType.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapEvent(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<WorkLink> findLink(Connection connection,
                                         AuthenticatedPrincipal principal,
                                         String workItemId,
                                         WorkLinkType type,
                                         String linkedId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT l.* FROM agent_work_link l
                JOIN agent_work_item w ON w.work_item_id = l.work_item_id
                WHERE l.work_item_id = ? AND w.tenant_id = ? AND w.owner_principal_id = ?
                  AND l.link_type = ? AND l.linked_id = ?
                """)) {
            statement.setString(1, workItemId);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            statement.setString(4, type.name());
            statement.setString(5, linkedId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapLink(resultSet)) : Optional.empty();
            }
        }
    }

    private AgentWorkItem requireOwnedWorkItem(AuthenticatedPrincipal principal, String workItemId) {
        requirePrincipal(principal);
        if (!hasText(workItemId)) {
            throw new IllegalArgumentException("workItemId must not be blank");
        }
        ensureSchema();
        return findWorkItem(principal, workItemId)
                .orElseThrow(() -> new WorkbenchNotFoundException("work item not found: " + workItemId));
    }

    private void verifyRelatedWorkItems(Connection connection,
                                        AuthenticatedPrincipal principal,
                                        WorkRelation relation) throws SQLException {
        AgentWorkItem source = readWorkItemById(
                connection, principal, relation.sourceWorkItemId(), false).orElseThrow();
        AgentWorkItem target = readWorkItemById(
                connection, principal, relation.targetWorkItemId(), false).orElseThrow();
        if (!source.tenantId().equals(target.tenantId())) {
            throw new WorkbenchAccessDeniedException("cross-tenant WorkRelation detected");
        }
    }

    private AgentConversationTurn mapInput(ResultSet resultSet) throws SQLException {
        String relation = resultSet.getString("relation_type");
        String goalOrigin = resultSet.getString("goal_origin");
        String commandType = resultSet.getString("command_type");
        return new AgentConversationTurn(
                resultSet.getString("input_id"),
                resultSet.getString("client_input_id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("owner_principal_id"),
                resultSet.getString("content"),
                resultSet.getString("content_digest"),
                resultSet.getString("request_digest"),
                hasText(goalOrigin) ? GoalOrigin.valueOf(goalOrigin) : null,
                normalize(resultSet.getString("command_decision_id"), ""),
                normalize(resultSet.getString("parent_work_item_id"), ""),
                hasText(relation) ? WorkRelationType.valueOf(relation) : null,
                instant(resultSet, "created_at"),
                WorkInputKind.valueOf(resultSet.getString("input_kind")),
                hasText(commandType) ? WorkCommandType.valueOf(commandType) : null,
                normalize(resultSet.getString("target_work_item_id"), ""),
                InputClassificationStatus.valueOf(resultSet.getString("classification_status")),
                normalize(resultSet.getString("classification_reason"), ""),
                nullableInstant(resultSet, "classified_at"),
                readStringSet(resultSet.getString("principal_roles")),
                resultSet.getLong("version")
        );
    }

    private AgentWorkItem mapWorkItem(ResultSet resultSet) throws SQLException {
        return new AgentWorkItem(
                resultSet.getString("work_item_id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("owner_principal_id"),
                resultSet.getString("original_goal"),
                resultSet.getString("normalized_goal"),
                WorkControlState.valueOf(resultSet.getString("control_state")),
                WorkExecutionState.valueOf(resultSet.getString("execution_state")),
                WorkOutcome.valueOf(resultSet.getString("outcome")),
                normalize(resultSet.getString("active_execution_target"), ""),
                normalize(resultSet.getString("active_run_id"), ""),
                normalize(resultSet.getString("active_incident_id"), ""),
                normalize(resultSet.getString("active_recovery_plan_id"), ""),
                normalize(resultSet.getString("route_decision_id"), ""),
                resultSet.getString("source_input_id"),
                normalize(resultSet.getString("parent_work_item_id"), ""),
                resultSet.getString("routing_request_id"),
                resultSet.getInt("routing_attempt_count"),
                nullableInstant(resultSet, "routing_last_attempt_at"),
                nullableInstant(resultSet, "routing_next_retry_at"),
                normalize(resultSet.getString("routing_failure_code"), ""),
                normalize(resultSet.getString("dispatch_request_id"), ""),
                resultSet.getLong("next_event_sequence"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                nullableInstant(resultSet, "completed_at")
        );
    }

    private ConversationWorkState mapConversation(ResultSet resultSet) throws SQLException {
        return new ConversationWorkState(
                resultSet.getString("conversation_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("owner_principal_id"),
                normalize(resultSet.getString("focused_work_item_id"), ""),
                resultSet.getLong("version"),
                instant(resultSet, "updated_at")
        );
    }

    private WorkEvent mapEvent(ResultSet resultSet) throws SQLException {
        Object sourceSequence = resultSet.getObject("source_sequence");
        return new WorkEvent(
                resultSet.getString("event_id"),
                resultSet.getString("work_item_id"),
                resultSet.getLong("sequence"),
                resultSet.getString("source_type"),
                resultSet.getString("source_id"),
                resultSet.getString("source_event_id"),
                sourceSequence == null ? null : resultSet.getLong("source_sequence"),
                WorkEventType.valueOf(resultSet.getString("event_type")),
                normalize(resultSet.getString("phase"), ""),
                normalize(resultSet.getString("summary"), ""),
                readMap(resultSet.getString("payload")),
                resultSet.getString("correlation_id"),
                normalize(resultSet.getString("causation_id"), ""),
                instant(resultSet, "source_created_at"),
                instant(resultSet, "projected_at")
        );
    }

    private WorkRelation mapRelation(ResultSet resultSet) throws SQLException {
        return new WorkRelation(
                resultSet.getString("source_work_item_id"),
                resultSet.getString("target_work_item_id"),
                WorkRelationType.valueOf(resultSet.getString("relation_type")),
                resultSet.getString("created_by_input_id"),
                instant(resultSet, "created_at")
        );
    }

    private WorkLink mapLink(ResultSet resultSet) throws SQLException {
        return new WorkLink(
                resultSet.getString("work_item_id"),
                normalize(resultSet.getString("dispatch_request_id"), ""),
                WorkLinkType.valueOf(resultSet.getString("link_type")),
                resultSet.getString("linked_id"),
                WorkLinkRelation.valueOf(resultSet.getString("relation")),
                instant(resultSet, "created_at")
        );
    }

    private String requestDigest(CreateWorkItemCommand command) {
        return sha256(String.join("\n",
                command.conversationId(),
                command.content(),
                command.goal().goalText(),
                command.goal().goalOrigin().name(),
                command.goal().commandDecisionId(),
                command.goal().parentWorkItemId(),
                command.goal().relationType() == null ? "" : command.goal().relationType().name()
        ));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        }
        catch (RuntimeException exception) {
            throw new AgentStorageException("Failed to serialize WorkEvent payload", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (!hasText(json)) {
            return Map.of();
        }
        try {
            return Map.copyOf(objectMapper.readValue(json, Map.class));
        }
        catch (RuntimeException exception) {
            throw new AgentStorageException("Failed to deserialize WorkEvent payload", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> readStringSet(String json) {
        if (!hasText(json)) {
            return Set.of();
        }
        try {
            return Set.copyOf(objectMapper.readValue(json, List.class));
        }
        catch (RuntimeException exception) {
            throw new AgentStorageException("Failed to deserialize principal role snapshot", exception);
        }
    }

    private void ensureSchema() {
        if (schemaReady.get()) {
            return;
        }
        synchronized (schemaReady) {
            if (schemaReady.get()) {
                return;
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                for (String ddl : schemaStatements()) {
                    statement.execute(ddl);
                }
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw storageFailure("Failed to initialize M1-A workbench schema", exception);
            }
        }
    }

    private List<String> schemaStatements() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS agent_work_input (
                    input_id TEXT PRIMARY KEY,
                    client_input_id TEXT NOT NULL,
                    conversation_id TEXT NOT NULL,
                    tenant_id TEXT NOT NULL,
                    owner_principal_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    content_digest CHAR(64) NOT NULL,
                    request_digest CHAR(64) NOT NULL,
                    goal_origin TEXT NOT NULL,
                    command_decision_id TEXT,
                    parent_work_item_id TEXT,
                    relation_type TEXT,
                    created_at TIMESTAMPTZ NOT NULL,
                    UNIQUE(tenant_id, owner_principal_id, client_input_id)
                )
                """,
                """
                ALTER TABLE agent_work_input
                    ADD COLUMN IF NOT EXISTS principal_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
                    ADD COLUMN IF NOT EXISTS input_kind TEXT NOT NULL DEFAULT 'NORMAL_GOAL',
                    ADD COLUMN IF NOT EXISTS command_type TEXT,
                    ADD COLUMN IF NOT EXISTS target_work_item_id TEXT,
                    ADD COLUMN IF NOT EXISTS classification_status TEXT NOT NULL DEFAULT 'CLASSIFIED',
                    ADD COLUMN IF NOT EXISTS classification_reason TEXT,
                    ADD COLUMN IF NOT EXISTS classified_at TIMESTAMPTZ,
                    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0
                """,
                """
                CREATE INDEX IF NOT EXISTS idx_agent_work_input_conversation
                ON agent_work_input(tenant_id, owner_principal_id, conversation_id, created_at)
                """,
                """
                CREATE TABLE IF NOT EXISTS agent_work_item (
                    work_item_id TEXT PRIMARY KEY,
                    conversation_id TEXT NOT NULL,
                    tenant_id TEXT NOT NULL,
                    owner_principal_id TEXT NOT NULL,
                    original_goal TEXT NOT NULL,
                    normalized_goal TEXT NOT NULL,
                    control_state TEXT NOT NULL,
                    execution_state TEXT NOT NULL,
                    outcome TEXT NOT NULL,
                    active_execution_target TEXT,
                    active_run_id TEXT,
                    active_incident_id TEXT,
                    active_recovery_plan_id TEXT,
                    route_decision_id TEXT,
                    source_input_id TEXT NOT NULL REFERENCES agent_work_input(input_id),
                    parent_work_item_id TEXT REFERENCES agent_work_item(work_item_id),
                    routing_request_id TEXT NOT NULL UNIQUE,
                    routing_attempt_count INT NOT NULL DEFAULT 0,
                    routing_last_attempt_at TIMESTAMPTZ,
                    routing_next_retry_at TIMESTAMPTZ,
                    routing_failure_code TEXT,
                    dispatch_request_id TEXT,
                    next_event_sequence BIGINT NOT NULL DEFAULT 0,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    completed_at TIMESTAMPTZ,
                    UNIQUE(source_input_id)
                )
                """,
                """
                CREATE INDEX IF NOT EXISTS idx_agent_work_item_conversation
                ON agent_work_item(tenant_id, owner_principal_id, conversation_id, created_at)
                """,
                """
                CREATE INDEX IF NOT EXISTS idx_agent_work_item_control_state
                ON agent_work_item(control_state, updated_at)
                """,
                """
                CREATE TABLE IF NOT EXISTS agent_conversation_work_state (
                    conversation_id TEXT NOT NULL,
                    tenant_id TEXT NOT NULL,
                    owner_principal_id TEXT NOT NULL,
                    focused_work_item_id TEXT REFERENCES agent_work_item(work_item_id),
                    version BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY(tenant_id, owner_principal_id, conversation_id)
                )
                """,
                """
                ALTER TABLE agent_conversation_work_state
                DROP CONSTRAINT IF EXISTS agent_conversation_work_state_conversation_id_key
                """,
                """
                CREATE TABLE IF NOT EXISTS agent_work_relation (
                    source_work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
                    target_work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
                    relation_type TEXT NOT NULL,
                    created_by_input_id TEXT NOT NULL REFERENCES agent_work_input(input_id),
                    created_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY(source_work_item_id, target_work_item_id, relation_type),
                    CHECK(source_work_item_id <> target_work_item_id)
                )
                """,
                """
                CREATE INDEX IF NOT EXISTS idx_agent_work_relation_target
                ON agent_work_relation(target_work_item_id, relation_type)
                """,
                """
                CREATE TABLE IF NOT EXISTS agent_work_link (
                    work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
                    dispatch_request_id TEXT,
                    link_type TEXT NOT NULL,
                    linked_id TEXT NOT NULL,
                    relation TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    UNIQUE(work_item_id, link_type, linked_id),
                    UNIQUE(dispatch_request_id)
                )
                """,
                """
                CREATE INDEX IF NOT EXISTS idx_agent_work_link_work_item
                ON agent_work_link(work_item_id, created_at)
                """,
                """
                CREATE TABLE IF NOT EXISTS agent_work_event (
                    event_id TEXT PRIMARY KEY,
                    work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
                    sequence BIGINT NOT NULL,
                    source_type TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    source_event_id TEXT NOT NULL,
                    source_sequence BIGINT,
                    event_type TEXT NOT NULL,
                    phase TEXT,
                    summary TEXT,
                    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
                    correlation_id TEXT NOT NULL,
                    causation_id TEXT,
                    source_created_at TIMESTAMPTZ NOT NULL,
                    projected_at TIMESTAMPTZ NOT NULL,
                    UNIQUE(work_item_id, sequence),
                    UNIQUE(work_item_id, source_type, source_id, source_event_id)
                )
                """,
                """
                CREATE INDEX IF NOT EXISTS idx_agent_work_event_sequence
                ON agent_work_event(work_item_id, sequence)
                """
        );
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword()
        );
    }

    private void requirePrincipal(AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new IllegalArgumentException("authenticated principal must not be null");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        if (value == null) {
            throw new AgentStorageException("Required timestamp is null: " + column, null);
        }
        return value.toInstant();
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private void setTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setTimestamp(index, value == null ? null : Timestamp.from(value));
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        }
        catch (SQLException ignored) {
            // Preserve the original exception.
        }
    }

    private AgentStorageException storageFailure(String message, SQLException exception) {
        return new AgentStorageException(message, exception);
    }
}
