package com.agent.platform.workbench.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import com.agent.platform.workbench.application.CommandClassifierResult;
import com.agent.platform.workbench.application.RouterModelResult;
import com.agent.platform.workbench.application.RouterFailureObservation;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.DecisionStatus;
import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.InputClassificationStatus;
import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.model.RouteValidationResult;
import com.agent.platform.workbench.model.RoutingAttempt;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.model.RoutingRecoveryCandidate;
import com.agent.platform.workbench.model.WorkCommandDecision;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkInputKind;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.model.WorkRelationType;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class JdbcRoutingStore implements RoutingStore {

    private final AgentStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcRoutingStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentConversationTurn persistUnclassifiedInput(AuthenticatedPrincipal principal,
                                                           String inputId,
                                                           String clientInputId,
                                                           String conversationId,
                                                           String content) {
        requirePrincipal(principal);
        inputId = requireText(inputId, "inputId");
        clientInputId = requireText(clientInputId, "clientInputId");
        conversationId = requireText(conversationId, "conversationId");
        content = requireText(content, "content");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureConversation(connection, principal, conversationId);
                String requestDigest = sha256(conversationId + "|" + content);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO agent_work_input(
                            input_id, client_input_id, conversation_id, tenant_id, owner_principal_id,
                            content, content_digest, request_digest, goal_origin, command_decision_id,
                            parent_work_item_id, relation_type, created_at, input_kind, command_type,
                            target_work_item_id, classification_status, classification_reason,
                            classified_at, principal_roles, version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?,
                                  'UNCLASSIFIED', NULL, NULL, 'PENDING', NULL, NULL, ?::jsonb, 0)
                        ON CONFLICT(tenant_id, owner_principal_id, client_input_id) DO NOTHING
                        """)) {
                    statement.setString(1, inputId);
                    statement.setString(2, clientInputId);
                    statement.setString(3, conversationId);
                    statement.setString(4, principal.tenantId());
                    statement.setString(5, principal.principalId());
                    statement.setString(6, content);
                    statement.setString(7, sha256(content));
                    statement.setString(8, requestDigest);
                    statement.setTimestamp(9, Timestamp.from(Instant.now()));
                    statement.setString(10, json(principal.roles()));
                    statement.executeUpdate();
                }
                AgentConversationTurn persisted = readInputByClientId(
                        connection, principal, clientInputId, true).orElseThrow();
                if (!persisted.requestDigest().equals(requestDigest)
                        || !persisted.conversationId().equals(conversationId)) {
                    throw new WorkbenchIdempotencyConflictException(
                            "clientInputId was already used with another input payload");
                }
                connection.commit();
                return persisted;
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storage("Failed to persist unclassified work input", exception);
        }
    }

    @Override
    public WorkCommandDecision beginCommandAttempt(AuthenticatedPrincipal principal,
                                                   String inputId,
                                                   ClassifierType classifierType,
                                                   String traceId) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentConversationTurn input = requireInput(connection, principal, inputId, true);
                Optional<WorkCommandDecision> effective = findEffectiveCommand(connection, input.inputId());
                if (effective.isPresent()) {
                    connection.commit();
                    return effective.get();
                }
                int attemptNo = nextCommandAttempt(connection, input.inputId());
                String decisionId = "cmddec-" + UUID.randomUUID();
                Instant now = Instant.now();
                String focusedWorkItemId = focusedWorkItem(connection, input.conversationId());
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO agent_work_command_decision(
                            command_decision_id, input_id, conversation_id, tenant_id,
                            owner_principal_id, focused_work_item_id, attempt_no, classifier_type,
                            decision_status, command_type, model_name, prompt_digest,
                            raw_output_digest, decision_json, prompt_tokens, completion_tokens,
                            latency_ms, model_confidence, failure_code, failure_reason,
                            trace_id, created_at, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', NULL, NULL, NULL,
                                  NULL, NULL, 0, 0, 0, 0, NULL, NULL, ?, ?, NULL)
                        """)) {
                    statement.setString(1, decisionId);
                    statement.setString(2, input.inputId());
                    statement.setString(3, input.conversationId());
                    statement.setString(4, principal.tenantId());
                    statement.setString(5, principal.principalId());
                    statement.setString(6, nullIfBlank(focusedWorkItemId));
                    statement.setInt(7, attemptNo);
                    statement.setString(8, classifierType.name());
                    statement.setString(9, traceId);
                    statement.setTimestamp(10, Timestamp.from(now));
                    statement.executeUpdate();
                }
                updateInputClassificationStatus(connection, input.inputId(), "CLASSIFYING", null);
                connection.commit();
                return findCommandById(decisionId).orElseThrow();
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storage("Failed to begin command classification", exception);
        }
    }

    @Override
    public WorkCommandDecision completeCommandAttempt(AuthenticatedPrincipal principal,
                                                      String commandDecisionId,
                                                      CommandClassifierResult result) {
        requirePrincipal(principal);
        if (result == null) throw new IllegalArgumentException("classifier result is required");
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                WorkCommandDecision current = requireCommand(connection, commandDecisionId, true);
                verifyDecisionOwner(current.tenantId(), current.ownerPrincipalId(), principal);
                AgentConversationTurn input = requireInput(connection, principal, current.inputId(), true);
                Optional<WorkCommandDecision> existing = findEffectiveCommand(connection, input.inputId());
                if (existing.isPresent() && !existing.get().commandDecisionId().equals(commandDecisionId)) {
                    markCommandSuperseded(connection, commandDecisionId);
                    connection.commit();
                    return existing.get();
                }
                if (current.decisionStatus() == DecisionStatus.EFFECTIVE) {
                    connection.commit();
                    return current;
                }
                WorkCommandType type = result.classification().commandType();
                Instant now = Instant.now();
                Map<String, Object> decision = Map.of(
                        "commandType", type.name(),
                        "modelConfidence", result.classification().modelConfidence(),
                        "reason", result.classification().reason(),
                        "targetWorkItemId", result.classification().targetWorkItemId(),
                        "derivedGoalText", result.classification().derivedGoalText());
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_command_decision SET
                            decision_status='EFFECTIVE', command_type=?, model_name=?,
                            prompt_digest=?, raw_output_digest=?, decision_json=?::jsonb,
                            prompt_tokens=?, completion_tokens=?, latency_ms=?, model_confidence=?,
                            failure_code=NULL, failure_reason=NULL, trace_id=?, completed_at=?
                        WHERE command_decision_id=? AND decision_status='STARTED'
                        """)) {
                    statement.setString(1, type.name());
                    statement.setString(2, nullIfBlank(result.modelName()));
                    statement.setString(3, nullIfBlank(result.promptDigest()));
                    statement.setString(4, nullIfBlank(result.rawOutputDigest()));
                    statement.setString(5, json(decision));
                    statement.setLong(6, result.promptTokens());
                    statement.setLong(7, result.completionTokens());
                    statement.setLong(8, result.latencyMs());
                    statement.setDouble(9, result.classification().modelConfidence());
                    statement.setString(10, result.traceId());
                    statement.setTimestamp(11, Timestamp.from(now));
                    statement.setString(12, commandDecisionId);
                    if (statement.executeUpdate() != 1) throw new WorkbenchCasConflictException("command attempt changed");
                }
                WorkInputKind kind = type == WorkCommandType.NORMAL_GOAL
                        ? WorkInputKind.NORMAL_GOAL : WorkInputKind.WORK_COMMAND;
                String origin = type == WorkCommandType.NORMAL_GOAL ? GoalOrigin.DIRECT_NORMAL_GOAL.name()
                        : type == WorkCommandType.START_NEW_WORK ? GoalOrigin.DERIVED_FROM_START_NEW_WORK.name() : null;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_input SET input_kind=?, command_type=?, target_work_item_id=?,
                            classification_status='CLASSIFIED', classification_reason=?, classified_at=?,
                            command_decision_id=?, goal_origin=?, version=version+1
                        WHERE input_id=?
                        """)) {
                    statement.setString(1, kind.name());
                    statement.setString(2, type.name());
                    statement.setString(3, nullIfBlank(result.classification().targetWorkItemId()));
                    statement.setString(4, result.classification().reason());
                    statement.setTimestamp(5, Timestamp.from(now));
                    statement.setString(6, commandDecisionId);
                    statement.setString(7, origin);
                    statement.setString(8, input.inputId());
                    statement.executeUpdate();
                }
                connection.commit();
                return findCommandById(commandDecisionId).orElseThrow();
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storage("Failed to complete command classification", exception);
        }
    }

    @Override
    public WorkCommandDecision failCommandAttempt(AuthenticatedPrincipal principal,
                                                  String commandDecisionId,
                                                  String failureCode,
                                                  String failureReason) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                WorkCommandDecision current = requireCommand(connection, commandDecisionId, true);
                verifyDecisionOwner(current.tenantId(), current.ownerPrincipalId(), principal);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_command_decision SET decision_status='FAILED_ATTEMPT',
                            failure_code=?, failure_reason=?, completed_at=?
                        WHERE command_decision_id=? AND decision_status='STARTED'
                        """)) {
                    statement.setString(1, failureCode);
                    statement.setString(2, failureReason);
                    statement.setTimestamp(3, Timestamp.from(Instant.now()));
                    statement.setString(4, commandDecisionId);
                    statement.executeUpdate();
                }
                updateInputClassificationStatus(connection, current.inputId(), "FAILED", failureReason);
                connection.commit();
                return findCommandById(commandDecisionId).orElseThrow();
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw storage("Failed to fail command classification", exception);
        }
    }

    @Override
    public Optional<WorkCommandDecision> findEffectiveCommand(AuthenticatedPrincipal principal, String inputId) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            requireInput(connection, principal, inputId, false);
            return findEffectiveCommand(connection, inputId);
        }
        catch (SQLException exception) { throw storage("Failed to find effective command", exception); }
    }

    @Override
    public List<WorkCommandDecision> listCommandDecisions(AuthenticatedPrincipal principal, String inputId) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            requireInput(connection, principal, inputId, false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM agent_work_command_decision WHERE input_id=? ORDER BY attempt_no
                    """)) {
                statement.setString(1, inputId);
                try (ResultSet rs = statement.executeQuery()) {
                    List<WorkCommandDecision> result = new ArrayList<>();
                    while (rs.next()) result.add(mapCommand(rs));
                    return List.copyOf(result);
                }
            }
        }
        catch (SQLException exception) { throw storage("Failed to list command decisions", exception); }
    }

    @Override
    public Optional<RoutingAttempt> claimRouting(AuthenticatedPrincipal principal,
                                                 String workItemId,
                                                 String routingRequestId,
                                                 Instant staleBefore,
                                                 int maxAttempts,
                                                 long unknownResultTokenReserve,
                                                 String catalogVersion) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem work = requireWork(connection, principal, workItemId, true);
                if (!work.routingRequestId().equals(routingRequestId)) {
                    throw new WorkbenchIdempotencyConflictException("routingRequestId cannot be replaced");
                }
                if (work.controlState() != WorkControlState.ROUTING || findEffectiveRouting(connection, workItemId).isPresent()) {
                    connection.commit();
                    return Optional.empty();
                }
                Optional<RoutingDecisionRecord> started = findStartedRouting(connection, workItemId);
                if (started.isPresent()) {
                    if (started.get().createdAt().isAfter(staleBefore)) {
                        connection.commit();
                        return Optional.empty();
                    }
                    markRoutingUnknown(connection, started.get().decisionId(), unknownResultTokenReserve);
                }
                if (work.routingAttemptCount() >= maxAttempts) {
                    exhaustRouting(connection, work, "routing attempts exhausted before claim");
                    connection.commit();
                    return Optional.empty();
                }
                int attemptNo = work.routingAttemptCount() + 1;
                String decisionId = "rdec-" + UUID.randomUUID();
                String traceId = "router-" + UUID.randomUUID();
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO agent_routing_decision(
                            decision_id, work_item_id, routing_request_id, attempt_no,
                            decision_status, model_name, target_catalog_version, prompt_digest,
                            raw_output_digest, decision_json, validation_json, prompt_tokens,
                            completion_tokens, latency_ms, failure_code, failure_reason,
                            trace_id, created_at, completed_at
                        ) VALUES (?, ?, ?, ?, 'STARTED', NULL, ?, NULL, NULL, NULL, NULL,
                                  0, 0, 0, NULL, NULL, ?, ?, NULL)
                        """)) {
                    statement.setString(1, decisionId);
                    statement.setString(2, workItemId);
                    statement.setString(3, routingRequestId);
                    statement.setInt(4, attemptNo);
                    statement.setString(5, catalogVersion);
                    statement.setString(6, traceId);
                    statement.setTimestamp(7, Timestamp.from(now));
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_item SET routing_attempt_count=?, routing_last_attempt_at=?,
                            routing_next_retry_at=NULL, routing_failure_code=NULL, version=version+1, updated_at=?
                        WHERE work_item_id=?
                        """)) {
                    statement.setInt(1, attemptNo);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setString(4, workItemId);
                    statement.executeUpdate();
                }
                appendEvent(connection, workItemId, "routing-started:" + decisionId,
                        WorkEventType.ROUTING_STARTED, "ROUTING", "Router model attempt started",
                        Map.of("decisionId", decisionId, "attemptNo", attemptNo, "traceId", traceId), decisionId);
                connection.commit();
                return Optional.of(new RoutingAttempt(decisionId, workItemId, routingRequestId, attemptNo, traceId));
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) { throw storage("Failed to claim routing", exception); }
    }

    @Override
    public RoutingDecisionRecord completeRouting(AuthenticatedPrincipal principal,
                                                 RoutingAttempt attempt,
                                                 RouterModelResult modelResult,
                                                 RouteValidationResult validation) {
        requirePrincipal(principal);
        if (attempt == null || modelResult == null || validation == null) {
            throw new IllegalArgumentException("attempt, modelResult and validation are required");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem work = requireWork(connection, principal, attempt.workItemId(), true);
                RoutingDecisionRecord current = requireRouting(connection, attempt.decisionId(), true);
                Optional<RoutingDecisionRecord> effective = findEffectiveRouting(connection, work.workItemId());
                if (effective.isPresent() && !effective.get().decisionId().equals(current.decisionId())) {
                    markRoutingSuperseded(connection, current.decisionId());
                    connection.commit();
                    return effective.get();
                }
                Map<String, Object> decision = decisionMap(modelResult);
                Map<String, Object> validationMap = validationMap(validation);
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_routing_decision SET decision_status='EFFECTIVE', model_name=?,
                            prompt_digest=?, raw_output_digest=?, decision_json=?::jsonb,
                            validation_json=?::jsonb, prompt_tokens=?, completion_tokens=?,
                            latency_ms=?, failure_code=?, failure_reason=?, completed_at=?
                        WHERE decision_id=? AND decision_status='STARTED'
                        """)) {
                    statement.setString(1, nullIfBlank(modelResult.modelName()));
                    statement.setString(2, modelResult.promptDigest());
                    statement.setString(3, modelResult.rawOutputDigest());
                    statement.setString(4, json(decision));
                    statement.setString(5, json(validationMap));
                    statement.setLong(6, modelResult.promptTokens());
                    statement.setLong(7, modelResult.completionTokens());
                    statement.setLong(8, modelResult.latencyMs());
                    statement.setString(9, nullIfBlank(validation.failureCode()));
                    statement.setString(10, validation.reasons().isEmpty() ? null : String.join("; ", validation.reasons()));
                    statement.setTimestamp(11, Timestamp.from(now));
                    statement.setString(12, current.decisionId());
                    if (statement.executeUpdate() != 1) throw new WorkbenchCasConflictException("routing attempt changed");
                }
                WorkControlState nextState = controlState(validation.disposition());
                WorkOutcome outcome = validation.disposition() == RouteDisposition.REJECT
                        ? WorkOutcome.FAILED : work.outcome();
                String targetId = validation.validatedInput() == null
                        ? modelResult.decision().targetId() : validation.validatedInput().targetId();
                String dispatchRequestId = validation.disposition() == RouteDisposition.AUTO_DISPATCH
                        ? hasText(work.dispatchRequestId()) ? work.dispatchRequestId() : "dispatch-" + UUID.randomUUID()
                        : work.dispatchRequestId();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_item SET route_decision_id=?, active_execution_target=?,
                            control_state=?, outcome=?, dispatch_request_id=?, routing_failure_code=?,
                            routing_next_retry_at=NULL, version=version+1, updated_at=?, completed_at=?
                        WHERE work_item_id=? AND control_state='ROUTING'
                        """)) {
                    statement.setString(1, current.decisionId());
                    statement.setString(2, nullIfBlank(targetId));
                    statement.setString(3, nextState.name());
                    statement.setString(4, outcome.name());
                    statement.setString(5, nullIfBlank(dispatchRequestId));
                    statement.setString(6, nullIfBlank(validation.failureCode()));
                    statement.setTimestamp(7, Timestamp.from(now));
                    if (nextState == WorkControlState.CLOSED) statement.setTimestamp(8, Timestamp.from(now));
                    else statement.setTimestamp(8, null);
                    statement.setString(9, work.workItemId());
                    if (statement.executeUpdate() != 1) throw new WorkbenchCasConflictException("work item left ROUTING");
                }
                appendEvent(connection, work.workItemId(), "routing-decided:" + current.decisionId(),
                        WorkEventType.ROUTING_DECIDED, nextState.name(), "Routing decision validated",
                        Map.of("decisionId", current.decisionId(), "targetId", targetId,
                                "disposition", validation.disposition().name(),
                                "traceId", current.traceId()), current.decisionId());
                appendDispositionEvent(connection, work.workItemId(), current.decisionId(), validation, dispatchRequestId);
                connection.commit();
                return findRoutingById(current.decisionId()).orElseThrow();
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) { throw storage("Failed to complete routing", exception); }
    }

    @Override
    public RoutingDecisionRecord failRouting(AuthenticatedPrincipal principal,
                                             RoutingAttempt attempt,
                                             String failureCode,
                                             String failureReason,
                                             RouterFailureObservation observation,
                                             long retryBackoffMillis,
                                             int maxAttempts) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem work = requireWork(connection, principal, attempt.workItemId(), true);
                RoutingDecisionRecord current = requireRouting(connection, attempt.decisionId(), true);
                Instant now = Instant.now();
                RouterFailureObservation observed = observation == null
                        ? RouterFailureObservation.empty() : observation;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_routing_decision SET decision_status='FAILED_ATTEMPT',
                            model_name=?, prompt_digest=?, raw_output_digest=?, prompt_tokens=?,
                            completion_tokens=?, latency_ms=?, failure_code=?, failure_reason=?, completed_at=?
                        WHERE decision_id=? AND decision_status IN ('STARTED','RESULT_UNKNOWN')
                        """)) {
                    statement.setString(1, nullIfBlank(observed.modelName()));
                    statement.setString(2, nullIfBlank(observed.promptDigest()));
                    statement.setString(3, nullIfBlank(observed.rawOutputDigest()));
                    statement.setLong(4, observed.promptTokens());
                    statement.setLong(5, observed.completionTokens());
                    statement.setLong(6, observed.latencyMs());
                    statement.setString(7, failureCode);
                    statement.setString(8, failureReason);
                    statement.setTimestamp(9, Timestamp.from(now));
                    statement.setString(10, current.decisionId());
                    statement.executeUpdate();
                }
                boolean exhausted = work.routingAttemptCount() >= maxAttempts;
                WorkControlState next = exhausted ? WorkControlState.MANUAL_REVIEW : WorkControlState.ROUTING;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_item SET control_state=?, routing_failure_code=?,
                            routing_next_retry_at=?, version=version+1, updated_at=? WHERE work_item_id=?
                        """)) {
                    statement.setString(1, next.name());
                    statement.setString(2, exhausted ? "RETRY_EXHAUSTED" : failureCode);
                    if (exhausted) statement.setTimestamp(3, null);
                    else statement.setTimestamp(3, Timestamp.from(now.plusMillis(Math.max(0, retryBackoffMillis))));
                    statement.setTimestamp(4, Timestamp.from(now));
                    statement.setString(5, work.workItemId());
                    statement.executeUpdate();
                }
                appendEvent(connection, work.workItemId(), "routing-failed:" + current.decisionId(),
                        WorkEventType.ROUTING_FAILED, next.name(), "Router attempt failed",
                        Map.of("decisionId", current.decisionId(), "failureCode", failureCode,
                                "attemptNo", current.attemptNo(), "exhausted", exhausted), current.decisionId());
                connection.commit();
                return findRoutingById(current.decisionId()).orElseThrow();
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) { throw storage("Failed to record routing failure", exception); }
    }

    @Override
    public List<RoutingRecoveryCandidate> findStaleRouting(Instant staleBefore, int limit) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT w.*, i.principal_roles AS recovery_principal_roles
                     FROM agent_work_item w
                     JOIN agent_work_input i ON i.input_id = w.source_input_id
                     WHERE w.control_state='ROUTING'
                       AND (w.routing_next_retry_at IS NULL OR w.routing_next_retry_at <= ?)
                       AND (w.routing_last_attempt_at IS NULL OR w.routing_last_attempt_at <= ?)
                       AND NOT EXISTS (SELECT 1 FROM agent_routing_decision d
                                       WHERE d.work_item_id=w.work_item_id AND d.decision_status='EFFECTIVE')
                     ORDER BY w.created_at LIMIT ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setTimestamp(2, Timestamp.from(staleBefore));
            statement.setInt(3, Math.max(1, Math.min(100, limit)));
            try (ResultSet rs = statement.executeQuery()) {
                List<RoutingRecoveryCandidate> result = new ArrayList<>();
                while (rs.next()) {
                    AgentWorkItem workItem = mapWork(rs);
                    result.add(new RoutingRecoveryCandidate(
                            workItem,
                            new AuthenticatedPrincipal(workItem.tenantId(), workItem.ownerPrincipalId(),
                                    readStringSet(rs.getString("recovery_principal_roles")))
                    ));
                }
                return List.copyOf(result);
            }
        }
        catch (SQLException exception) { throw storage("Failed to find stale routing work items", exception); }
    }

    @Override
    public Optional<RoutingDecisionRecord> findEffectiveRouting(AuthenticatedPrincipal principal, String workItemId) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            requireWork(connection, principal, workItemId, false);
            return findEffectiveRouting(connection, workItemId);
        }
        catch (SQLException exception) { throw storage("Failed to find effective routing", exception); }
    }

    @Override
    public List<RoutingDecisionRecord> listRoutingDecisions(AuthenticatedPrincipal principal, String workItemId) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            requireWork(connection, principal, workItemId, false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM agent_routing_decision WHERE work_item_id=? ORDER BY attempt_no
                    """)) {
                statement.setString(1, workItemId);
                try (ResultSet rs = statement.executeQuery()) {
                    List<RoutingDecisionRecord> result = new ArrayList<>();
                    while (rs.next()) result.add(mapRouting(rs));
                    return List.copyOf(result);
                }
            }
        }
        catch (SQLException exception) { throw storage("Failed to list routing decisions", exception); }
    }

    @Override
    public long totalRoutingTokens(AuthenticatedPrincipal principal, String workItemId) {
        return listRoutingDecisions(principal, workItemId).stream()
                .mapToLong(value -> value.promptTokens() + value.completionTokens()).sum();
    }

    private void appendDispositionEvent(Connection connection,
                                        String workItemId,
                                        String decisionId,
                                        RouteValidationResult validation,
                                        String dispatchRequestId) throws SQLException {
        WorkEventType eventType;
        String phase;
        switch (validation.disposition()) {
            case AUTO_DISPATCH -> { eventType = WorkEventType.DISPATCH_READY; phase = "READY_TO_DISPATCH"; }
            case REQUIRE_CONFIRMATION -> { eventType = WorkEventType.ROUTE_CONFIRMATION_REQUIRED; phase = "WAITING_CONFIRMATION"; }
            case REQUIRE_CLARIFICATION -> { eventType = WorkEventType.CLARIFICATION_REQUIRED; phase = "WAITING_INPUT"; }
            case REJECT -> { return; }
            default -> { return; }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", decisionId);
        payload.put("reasons", validation.reasons());
        if (hasText(dispatchRequestId)) payload.put("dispatchRequestId", dispatchRequestId);
        appendEvent(connection, workItemId, "routing-disposition:" + decisionId,
                eventType, phase, "Routing disposition established", payload, decisionId);
    }

    private void appendEvent(Connection connection,
                             String workItemId,
                             String sourceEventId,
                             WorkEventType type,
                             String phase,
                             String summary,
                             Map<String, Object> payload,
                             String causationId) throws SQLException {
        long sequence;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT next_event_sequence FROM agent_work_item WHERE work_item_id=? FOR UPDATE")) {
            statement.setString(1, workItemId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new WorkbenchNotFoundException("work item missing during event append");
                sequence = rs.getLong(1);
            }
        }
        Instant now = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_work_event(
                    event_id, work_item_id, sequence, source_type, source_id, source_event_id,
                    source_sequence, event_type, phase, summary, payload, correlation_id,
                    causation_id, source_created_at, projected_at
                ) VALUES (?, ?, ?, 'WORK_ITEM', ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT(work_item_id, source_type, source_id, source_event_id) DO NOTHING
                """)) {
            statement.setString(1, "wevt-" + UUID.randomUUID());
            statement.setString(2, workItemId);
            statement.setLong(3, sequence);
            statement.setString(4, workItemId);
            statement.setString(5, sourceEventId);
            statement.setLong(6, sequence);
            statement.setString(7, type.name());
            statement.setString(8, phase);
            statement.setString(9, summary);
            statement.setString(10, json(payload));
            statement.setString(11, workItemId);
            statement.setString(12, causationId);
            statement.setTimestamp(13, Timestamp.from(now));
            statement.setTimestamp(14, Timestamp.from(now));
            if (statement.executeUpdate() == 1) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE agent_work_item SET next_event_sequence=?, updated_at=? WHERE work_item_id=?
                        """)) {
                    update.setLong(1, sequence + 1);
                    update.setTimestamp(2, Timestamp.from(now));
                    update.setString(3, workItemId);
                    update.executeUpdate();
                }
            }
        }
    }

    private void exhaustRouting(Connection connection, AgentWorkItem work, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_work_item SET control_state='MANUAL_REVIEW', routing_failure_code='RETRY_EXHAUSTED',
                    routing_next_retry_at=NULL, version=version+1, updated_at=? WHERE work_item_id=?
                """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, work.workItemId());
            statement.executeUpdate();
        }
        appendEvent(connection, work.workItemId(), "routing-exhausted:" + work.routingRequestId(),
                WorkEventType.ROUTING_FAILED, "MANUAL_REVIEW", reason,
                Map.of("failureCode", "RETRY_EXHAUSTED"), work.routingRequestId());
    }

    private void markRoutingUnknown(Connection connection, String decisionId, long tokenReserve) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_routing_decision SET decision_status='RESULT_UNKNOWN',
                    prompt_tokens=GREATEST(prompt_tokens, ?),
                    failure_code='RESULT_PERSISTENCE_UNKNOWN',
                    failure_reason='stale STARTED attempt recovered before a persisted result', completed_at=?
                WHERE decision_id=? AND decision_status='STARTED'
                """)) {
            statement.setLong(1, Math.max(0, tokenReserve));
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setString(3, decisionId);
            statement.executeUpdate();
        }
    }

    private WorkControlState controlState(RouteDisposition disposition) {
        return switch (disposition) {
            case AUTO_DISPATCH -> WorkControlState.READY_TO_DISPATCH;
            case REQUIRE_CONFIRMATION -> WorkControlState.WAITING_CONFIRMATION;
            case REQUIRE_CLARIFICATION -> WorkControlState.WAITING_INPUT;
            case REJECT -> WorkControlState.CLOSED;
        };
    }

    private Map<String, Object> decisionMap(RouterModelResult result) {
        return Map.of(
                "targetId", result.decision().targetId(),
                "modelConfidence", result.decision().modelConfidence(),
                "reason", result.decision().reason(),
                "extractedInputs", result.decision().extractedInputs(),
                "missingInputs", result.decision().missingInputs(),
                "userFacingSummary", result.decision().userFacingSummary());
    }

    private Map<String, Object> validationMap(RouteValidationResult validation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("disposition", validation.disposition().name());
        result.put("reasons", validation.reasons());
        result.put("failureCode", validation.failureCode());
        if (validation.validatedInput() != null) {
            result.put("targetId", validation.validatedInput().targetId());
            result.put("inputDigest", validation.validatedInput().inputDigest());
            result.put("identifiers", validation.validatedInput().identifiers());
            result.put("typedPayload", validation.validatedInput().typedPayload());
        }
        return Map.copyOf(result);
    }

    private Optional<AgentConversationTurn> readInputByClientId(Connection connection,
                                                                 AuthenticatedPrincipal principal,
                                                                 String clientInputId,
                                                                 boolean lock) throws SQLException {
        String sql = "SELECT * FROM agent_work_input WHERE tenant_id=? AND owner_principal_id=? AND client_input_id=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal.tenantId());
            statement.setString(2, principal.principalId());
            statement.setString(3, clientInputId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(mapInput(rs)) : Optional.empty(); }
        }
    }

    private AgentConversationTurn requireInput(Connection connection,
                                               AuthenticatedPrincipal principal,
                                               String inputId,
                                               boolean lock) throws SQLException {
        String sql = "SELECT * FROM agent_work_input WHERE input_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, inputId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new WorkbenchNotFoundException("input not found: " + inputId);
                AgentConversationTurn input = mapInput(rs);
                verifyDecisionOwner(input.tenantId(), input.ownerPrincipalId(), principal);
                return input;
            }
        }
    }

    private AgentWorkItem requireWork(Connection connection,
                                      AuthenticatedPrincipal principal,
                                      String workItemId,
                                      boolean lock) throws SQLException {
        String sql = "SELECT * FROM agent_work_item WHERE work_item_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workItemId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new WorkbenchNotFoundException("work item not found: " + workItemId);
                AgentWorkItem work = mapWork(rs);
                verifyDecisionOwner(work.tenantId(), work.ownerPrincipalId(), principal);
                return work;
            }
        }
    }

    private WorkCommandDecision requireCommand(Connection connection, String id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM agent_work_command_decision WHERE command_decision_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new WorkbenchNotFoundException("command decision not found: " + id);
                return mapCommand(rs);
            }
        }
    }

    private RoutingDecisionRecord requireRouting(Connection connection, String id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM agent_routing_decision WHERE decision_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new WorkbenchNotFoundException("routing decision not found: " + id);
                return mapRouting(rs);
            }
        }
    }

    private Optional<WorkCommandDecision> findEffectiveCommand(Connection connection, String inputId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_work_command_decision WHERE input_id=? AND decision_status='EFFECTIVE'
                """)) {
            statement.setString(1, inputId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(mapCommand(rs)) : Optional.empty(); }
        }
    }

    private Optional<RoutingDecisionRecord> findEffectiveRouting(Connection connection, String workItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_routing_decision WHERE work_item_id=? AND decision_status='EFFECTIVE'
                """)) {
            statement.setString(1, workItemId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(mapRouting(rs)) : Optional.empty(); }
        }
    }

    private Optional<RoutingDecisionRecord> findStartedRouting(Connection connection, String workItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_routing_decision WHERE work_item_id=? AND decision_status='STARTED'
                ORDER BY attempt_no DESC LIMIT 1
                """)) {
            statement.setString(1, workItemId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(mapRouting(rs)) : Optional.empty(); }
        }
    }

    private Optional<WorkCommandDecision> findCommandById(String id) {
        try (Connection connection = openConnection()) { return Optional.of(requireCommand(connection, id, false)); }
        catch (SQLException exception) { throw storage("Failed to read command decision", exception); }
    }

    private Optional<RoutingDecisionRecord> findRoutingById(String id) {
        try (Connection connection = openConnection()) { return Optional.of(requireRouting(connection, id, false)); }
        catch (SQLException exception) { throw storage("Failed to read routing decision", exception); }
    }

    private int nextCommandAttempt(Connection connection, String inputId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(attempt_no),0)+1 FROM agent_work_command_decision WHERE input_id=?")) {
            statement.setString(1, inputId);
            try (ResultSet rs = statement.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private String focusedWorkItem(Connection connection, String conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT focused_work_item_id FROM agent_conversation_work_state WHERE conversation_id=?")) {
            statement.setString(1, conversationId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? blank(rs.getString(1)) : ""; }
        }
    }

    private void ensureConversation(Connection connection,
                                    AuthenticatedPrincipal principal,
                                    String conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_conversation_work_state(
                    conversation_id, tenant_id, owner_principal_id, focused_work_item_id, version, updated_at)
                VALUES (?, ?, ?, NULL, 0, ?) ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, conversationId);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tenant_id, owner_principal_id FROM agent_conversation_work_state
                WHERE conversation_id=? FOR UPDATE
                """)) {
            statement.setString(1, conversationId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new WorkbenchNotFoundException("conversation state missing");
                verifyDecisionOwner(rs.getString(1), rs.getString(2), principal);
            }
        }
    }

    private void updateInputClassificationStatus(Connection connection, String inputId,
                                                 String status, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_work_input SET classification_status=?, classification_reason=?,
                    version=version+1 WHERE input_id=?
                """)) {
            statement.setString(1, status);
            statement.setString(2, reason);
            statement.setString(3, inputId);
            statement.executeUpdate();
        }
    }

    private void markCommandSuperseded(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_work_command_decision SET decision_status='SUPERSEDED', completed_at=?
                WHERE command_decision_id=? AND decision_status='STARTED'
                """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now())); statement.setString(2, id); statement.executeUpdate();
        }
    }

    private void markRoutingSuperseded(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_routing_decision SET decision_status='SUPERSEDED', completed_at=?
                WHERE decision_id=? AND decision_status='STARTED'
                """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now())); statement.setString(2, id); statement.executeUpdate();
        }
    }

    private AgentConversationTurn mapInput(ResultSet rs) throws SQLException {
        String origin = rs.getString("goal_origin");
        String relation = rs.getString("relation_type");
        String command = rs.getString("command_type");
        return new AgentConversationTurn(
                rs.getString("input_id"), rs.getString("client_input_id"), rs.getString("conversation_id"),
                rs.getString("tenant_id"), rs.getString("owner_principal_id"), rs.getString("content"),
                rs.getString("content_digest"), rs.getString("request_digest"),
                hasText(origin) ? GoalOrigin.valueOf(origin) : null, blank(rs.getString("command_decision_id")),
                blank(rs.getString("parent_work_item_id")), hasText(relation) ? WorkRelationType.valueOf(relation) : null,
                instant(rs, "created_at"), WorkInputKind.valueOf(rs.getString("input_kind")),
                hasText(command) ? WorkCommandType.valueOf(command) : null,
                blank(rs.getString("target_work_item_id")),
                InputClassificationStatus.valueOf(rs.getString("classification_status")),
                blank(rs.getString("classification_reason")), nullableInstant(rs, "classified_at"),
                readStringSet(rs.getString("principal_roles")), rs.getLong("version"));
    }

    private AgentWorkItem mapWork(ResultSet rs) throws SQLException {
        return new AgentWorkItem(
                rs.getString("work_item_id"), rs.getString("conversation_id"), rs.getString("tenant_id"),
                rs.getString("owner_principal_id"), rs.getString("original_goal"), rs.getString("normalized_goal"),
                WorkControlState.valueOf(rs.getString("control_state")),
                WorkExecutionState.valueOf(rs.getString("execution_state")), WorkOutcome.valueOf(rs.getString("outcome")),
                blank(rs.getString("active_execution_target")), blank(rs.getString("active_run_id")),
                blank(rs.getString("active_incident_id")), blank(rs.getString("active_recovery_plan_id")),
                blank(rs.getString("route_decision_id")), rs.getString("source_input_id"),
                blank(rs.getString("parent_work_item_id")), rs.getString("routing_request_id"),
                rs.getInt("routing_attempt_count"), nullableInstant(rs, "routing_last_attempt_at"),
                nullableInstant(rs, "routing_next_retry_at"), blank(rs.getString("routing_failure_code")),
                blank(rs.getString("dispatch_request_id")), rs.getLong("next_event_sequence"), rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"), nullableInstant(rs, "completed_at"));
    }

    private WorkCommandDecision mapCommand(ResultSet rs) throws SQLException {
        String command = rs.getString("command_type");
        return new WorkCommandDecision(
                rs.getString("command_decision_id"), rs.getString("input_id"), rs.getString("conversation_id"),
                rs.getString("tenant_id"), rs.getString("owner_principal_id"), blank(rs.getString("focused_work_item_id")),
                rs.getInt("attempt_no"), ClassifierType.valueOf(rs.getString("classifier_type")),
                DecisionStatus.valueOf(rs.getString("decision_status")),
                hasText(command) ? WorkCommandType.valueOf(command) : null, blank(rs.getString("model_name")),
                blank(rs.getString("prompt_digest")), blank(rs.getString("raw_output_digest")),
                jsonMap(rs.getString("decision_json")), rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"),
                rs.getLong("latency_ms"), rs.getDouble("model_confidence"), blank(rs.getString("failure_code")),
                blank(rs.getString("failure_reason")), rs.getString("trace_id"), instant(rs, "created_at"),
                nullableInstant(rs, "completed_at"));
    }

    private RoutingDecisionRecord mapRouting(ResultSet rs) throws SQLException {
        return new RoutingDecisionRecord(
                rs.getString("decision_id"), rs.getString("work_item_id"), rs.getString("routing_request_id"),
                rs.getInt("attempt_no"), DecisionStatus.valueOf(rs.getString("decision_status")),
                blank(rs.getString("model_name")), rs.getString("target_catalog_version"),
                blank(rs.getString("prompt_digest")), blank(rs.getString("raw_output_digest")),
                jsonMap(rs.getString("decision_json")), jsonMap(rs.getString("validation_json")),
                rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getLong("latency_ms"),
                blank(rs.getString("failure_code")), blank(rs.getString("failure_reason")), rs.getString("trace_id"),
                instant(rs, "created_at"), nullableInstant(rs, "completed_at"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(String value) {
        if (!hasText(value)) return Map.of();
        return Map.copyOf(objectMapper.readValue(value, Map.class));
    }

    @SuppressWarnings("unchecked")
    private Set<String> readStringSet(String value) {
        if (!hasText(value)) return Set.of();
        return Set.copyOf(objectMapper.readValue(value, List.class));
    }

    private String json(Object value) { return objectMapper.writeValueAsString(value); }

    private void ensureSchema() {
        if (schemaReady.get()) return;
        synchronized (schemaReady) {
            if (schemaReady.get()) return;
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                for (String ddl : schemaStatements()) statement.execute(ddl);
                schemaReady.set(true);
            }
            catch (SQLException exception) { throw storage("Failed to initialize M1-B schema", exception); }
        }
    }

    private List<String> schemaStatements() {
        return List.of(
                """
                ALTER TABLE agent_work_input
                    ALTER COLUMN goal_origin DROP NOT NULL,
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
                CREATE TABLE IF NOT EXISTS agent_work_command_decision (
                    command_decision_id TEXT PRIMARY KEY,
                    input_id TEXT NOT NULL REFERENCES agent_work_input(input_id),
                    conversation_id TEXT NOT NULL, tenant_id TEXT NOT NULL, owner_principal_id TEXT NOT NULL,
                    focused_work_item_id TEXT, attempt_no INT NOT NULL, classifier_type TEXT NOT NULL,
                    decision_status TEXT NOT NULL, command_type TEXT, model_name TEXT,
                    prompt_digest CHAR(64), raw_output_digest CHAR(64), decision_json JSONB,
                    prompt_tokens BIGINT NOT NULL DEFAULT 0, completion_tokens BIGINT NOT NULL DEFAULT 0,
                    latency_ms BIGINT NOT NULL DEFAULT 0, model_confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
                    failure_code TEXT, failure_reason TEXT, trace_id TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ,
                    UNIQUE(input_id, attempt_no)
                )
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_work_command_effective_per_input ON agent_work_command_decision(input_id) WHERE decision_status='EFFECTIVE'",
                "CREATE INDEX IF NOT EXISTS idx_work_command_decision_conversation ON agent_work_command_decision(tenant_id, owner_principal_id, conversation_id, created_at)",
                """
                CREATE TABLE IF NOT EXISTS agent_routing_decision (
                    decision_id TEXT PRIMARY KEY, work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),
                    routing_request_id TEXT NOT NULL, attempt_no INT NOT NULL, decision_status TEXT NOT NULL,
                    model_name TEXT, target_catalog_version TEXT NOT NULL, prompt_digest CHAR(64),
                    raw_output_digest CHAR(64), decision_json JSONB, validation_json JSONB,
                    prompt_tokens BIGINT NOT NULL DEFAULT 0, completion_tokens BIGINT NOT NULL DEFAULT 0,
                    latency_ms BIGINT NOT NULL DEFAULT 0, failure_code TEXT, failure_reason TEXT,
                    trace_id TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ,
                    UNIQUE(work_item_id, attempt_no), UNIQUE(routing_request_id, attempt_no)
                )
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_routing_effective_per_work ON agent_routing_decision(work_item_id) WHERE decision_status='EFFECTIVE'",
                "CREATE INDEX IF NOT EXISTS idx_routing_decision_request ON agent_routing_decision(routing_request_id, attempt_no)",
                "CREATE INDEX IF NOT EXISTS idx_work_item_stale_routing ON agent_work_item(control_state, routing_next_retry_at, routing_last_attempt_at) WHERE control_state='ROUTING'"
        );
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }
    private void requirePrincipal(AuthenticatedPrincipal principal) {
        if (principal == null) throw new IllegalArgumentException("authenticated principal is required");
    }
    private void verifyDecisionOwner(String tenant, String owner, AuthenticatedPrincipal principal) {
        if (!tenant.equals(principal.tenantId()) || !owner.equals(principal.principalId())) {
            throw new WorkbenchAccessDeniedException("record belongs to another tenant or principal");
        }
    }
    private String requireText(String value, String field) {
        if (!hasText(value)) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String blank(String value) { return value == null ? "" : value.trim(); }
    private String nullIfBlank(String value) { return hasText(value) ? value.trim() : null; }
    private Instant instant(ResultSet rs, String column) throws SQLException { return rs.getTimestamp(column).toInstant(); }
    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private void rollback(Connection connection) { try { connection.rollback(); } catch (SQLException ignored) { } }
    private AgentStorageException storage(String message, Exception exception) {
        return new AgentStorageException(message, exception);
    }
}
