package com.agent.platform.workbench.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import com.agent.platform.workbench.dispatch.DispatchClaim;
import com.agent.platform.workbench.dispatch.DispatchRequest;
import com.agent.platform.workbench.dispatch.DispatchResult;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.DispatchAttempt;
import com.agent.platform.workbench.model.DispatchAttemptStatus;
import com.agent.platform.workbench.model.DispatchRecoveryCandidate;
import com.agent.platform.workbench.model.RoutePreview;
import com.agent.platform.workbench.model.RoutePreviewStatus;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.model.WorkLinkRelation;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.model.ValidatedExecutionInput;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class JdbcDispatchStore implements DispatchStore {

    private final AgentStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcDispatchStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public RoutePreview ensurePreview(AuthenticatedPrincipal principal,
                                      AgentWorkItem workItem,
                                      RoutingDecisionRecord routingDecision,
                                      long ttlSeconds) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem locked = requireWork(connection, principal, workItem.workItemId(), true);
                Optional<RoutePreview> existing = findPreview(connection, principal, locked.workItemId());
                if (existing.isPresent()) {
                    connection.commit();
                    return existing.get();
                }
                if (locked.controlState() != WorkControlState.WAITING_CONFIRMATION
                        || !locked.routeDecisionId().equals(routingDecision.decisionId())) {
                    throw new IllegalStateException("work item is not waiting for this route confirmation");
                }
                Map<String, Object> validation = routingDecision.validation();
                String inputDigest = text(validation.get("inputDigest"));
                Map<String, Object> typed = map(validation.get("typedPayload"));
                String scopeDigest = sha256(objectMapper.writeValueAsString(new TreeMap<>(typed)));
                Instant now = Instant.now();
                RoutePreview preview = new RoutePreview(
                        "preview-" + UUID.randomUUID(), locked.workItemId(), routingDecision.decisionId(),
                        locked.activeExecutionTarget(), 1, inputDigest, scopeDigest,
                        Map.of("targetId", locked.activeExecutionTarget(), "validatedInput", typed,
                                "reason", routingDecision.decision().getOrDefault("reason", "")),
                        RoutePreviewStatus.ACTIVE, now.plusSeconds(Math.max(30, ttlSeconds)), "", null, now);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO agent_route_preview(
                            preview_id, work_item_id, route_decision_id, target_id, preview_version,
                            validated_input_digest, scope_digest, payload_json, status, expires_at,
                            confirmed_by, confirmed_at, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, NULL, NULL, ?)
                        """)) {
                    bindPreview(statement, preview);
                    statement.executeUpdate();
                }
                connection.commit();
                return preview;
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            if (isUniqueViolation(exception)) {
                return findPreview(principal, workItem.workItemId())
                        .orElseThrow(() -> storage("Route preview raced but no winner was readable", exception));
            }
            throw storage("Failed to create route preview", exception);
        }
    }

    @Override
    public Optional<RoutePreview> findPreview(AuthenticatedPrincipal principal, String workItemId) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            requireWork(connection, principal, workItemId, false);
            return findPreview(connection, principal, workItemId);
        }
        catch (SQLException exception) { throw storage("Failed to read route preview", exception); }
    }

    @Override
    public AgentWorkItem confirmPreview(AuthenticatedPrincipal principal,
                                        String workItemId,
                                        String previewId,
                                        int previewVersion,
                                        String validatedInputDigest,
                                        String scopeDigest) {
        return decidePreview(principal, workItemId, previewId, previewVersion,
                validatedInputDigest, scopeDigest, true);
    }

    @Override
    public AgentWorkItem rejectPreview(AuthenticatedPrincipal principal, String workItemId, String previewId) {
        return decidePreview(principal, workItemId, previewId, -1, "", "", false);
    }

    private AgentWorkItem decidePreview(AuthenticatedPrincipal principal,
                                        String workItemId,
                                        String previewId,
                                        int previewVersion,
                                        String inputDigest,
                                        String scopeDigest,
                                        boolean confirm) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem work = requireWork(connection, principal, workItemId, true);
                RoutePreview preview = requirePreview(connection, principal, workItemId, true);
                if (!preview.previewId().equals(previewId)) throw new WorkbenchIdempotencyConflictException("previewId mismatch");
                if (preview.status() != RoutePreviewStatus.ACTIVE) {
                    if (confirm && preview.status() == RoutePreviewStatus.CONFIRMED) {
                        connection.commit();
                        return work;
                    }
                    throw new WorkbenchCasConflictException("preview is no longer ACTIVE");
                }
                Instant now = Instant.now();
                if (preview.expiresAt().isBefore(now)) {
                    expirePreview(connection, preview.previewId());
                    connection.commit();
                    throw new WorkbenchCasConflictException("route preview expired");
                }
                if (work.controlState() != WorkControlState.WAITING_CONFIRMATION) {
                    throw new WorkbenchCasConflictException("work item is not waiting for confirmation");
                }
                if (confirm && (preview.previewVersion() != previewVersion
                        || !preview.validatedInputDigest().equals(inputDigest)
                        || !preview.scopeDigest().equals(scopeDigest))) {
                    throw new WorkbenchIdempotencyConflictException("confirmation is not bound to the immutable preview");
                }
                String dispatchId = confirm
                        ? hasText(work.dispatchRequestId()) ? work.dispatchRequestId() : "dispatch-" + UUID.randomUUID()
                        : work.dispatchRequestId();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_route_preview SET status=?, confirmed_by=?, confirmed_at=?
                        WHERE preview_id=? AND status='ACTIVE'
                        """)) {
                    statement.setString(1, confirm ? "CONFIRMED" : "REJECTED");
                    statement.setString(2, principal.principalId());
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setString(4, preview.previewId());
                    if (statement.executeUpdate() != 1) throw new WorkbenchCasConflictException("preview changed");
                }
                WorkControlState next = confirm ? WorkControlState.READY_TO_DISPATCH : WorkControlState.ABANDONED;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_item SET control_state=?, dispatch_request_id=?, outcome=?,
                            version=version+1, updated_at=?, completed_at=? WHERE work_item_id=?
                        """)) {
                    statement.setString(1, next.name());
                    statement.setString(2, blankToNull(dispatchId));
                    statement.setString(3, confirm ? work.outcome().name() : WorkOutcome.REJECTED.name());
                    statement.setTimestamp(4, Timestamp.from(now));
                    statement.setTimestamp(5, confirm ? null : Timestamp.from(now));
                    statement.setString(6, work.workItemId());
                    statement.executeUpdate();
                }
                appendEvent(connection, work.workItemId(),
                        (confirm ? "route-confirmed:" : "route-rejected:") + preview.previewId(),
                        confirm ? WorkEventType.DISPATCH_READY : WorkEventType.WORK_ITEM_ABANDONED,
                        next.name(), confirm ? "Immutable route preview confirmed" : "Route preview rejected",
                        Map.of("previewId", preview.previewId(), "previewVersion", preview.previewVersion(),
                                "scopeDigest", preview.scopeDigest()), preview.previewId());
                AgentWorkItem updated = requireWork(connection, principal, workItemId, false);
                connection.commit();
                return updated;
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) { throw storage("Failed to decide route preview", exception); }
    }

    @Override
    public Optional<DispatchClaim> claimDispatch(AuthenticatedPrincipal principal,
                                                 String workItemId,
                                                 Instant staleBefore,
                                                 int maxAttempts) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem work = requireWork(connection, principal, workItemId, true);
                if (effectiveAttempt(connection, workItemId).isPresent()
                        || (work.controlState() != WorkControlState.READY_TO_DISPATCH
                        && work.controlState() != WorkControlState.DISPATCHING)) {
                    connection.commit();
                    return Optional.empty();
                }
                boolean reconciliation = work.controlState() == WorkControlState.DISPATCHING;
                Optional<DispatchAttempt> started = startedAttempt(connection, workItemId);
                if (started.isPresent()) {
                    if (started.get().createdAt().isAfter(staleBefore)) {
                        connection.commit();
                        return Optional.empty();
                    }
                    markUnknown(connection, started.get().attemptId());
                    reconciliation = true;
                }
                else if (reconciliation) {
                    Optional<DispatchAttempt> latest = latestAttempt(connection, workItemId);
                    if (latest.isPresent() && latest.get().completedAt() != null
                            && latest.get().completedAt().isAfter(staleBefore)) {
                        connection.commit();
                        return Optional.empty();
                    }
                }
                int attemptNo = nextAttemptNo(connection, workItemId);
                if (attemptNo > maxAttempts) {
                    moveManualReview(connection, work, "DISPATCH_RETRY_EXHAUSTED");
                    connection.commit();
                    return Optional.empty();
                }
                DispatchAttempt attempt = new DispatchAttempt(
                        "dattempt-" + UUID.randomUUID(), workItemId, work.dispatchRequestId(), attemptNo,
                        reconciliation, work.activeExecutionTarget(), DispatchAttemptStatus.STARTED,
                        "", "", Instant.now(), null);
                insertAttempt(connection, attempt);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_work_item SET control_state='DISPATCHING', execution_state='STARTING',
                            version=version+1, updated_at=? WHERE work_item_id=?
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(Instant.now()));
                    statement.setString(2, workItemId);
                    statement.executeUpdate();
                }
                appendEvent(connection, workItemId, "dispatch-started:" + attempt.attemptId(),
                        WorkEventType.DISPATCH_STARTED, "DISPATCHING", "Execution target dispatch started",
                        Map.of("attemptId", attempt.attemptId(), "attemptNo", attempt.attemptNo(),
                                "reconciliation", reconciliation, "targetId", attempt.targetId()), attempt.attemptId());
                DispatchRequest request = request(connection, principal, work);
                connection.commit();
                return Optional.of(new DispatchClaim(attempt, request));
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) { throw storage("Failed to claim dispatch", exception); }
    }

    @Override
    public WorkLink completeDispatch(AuthenticatedPrincipal principal, DispatchClaim claim, DispatchResult result) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem work = requireWork(connection, principal, claim.attempt().workItemId(), true);
                DispatchAttempt attempt = requireAttempt(
                        connection, principal, work.workItemId(), claim.attempt().attemptId(), true);
                if (!work.dispatchRequestId().equals(result.dispatchRequestId())
                        || !attempt.dispatchRequestId().equals(result.dispatchRequestId())) {
                    throw new WorkbenchIdempotencyConflictException("adapter returned another dispatchRequestId");
                }
                Optional<DispatchAttempt> effective = effectiveAttempt(connection, work.workItemId());
                if (effective.isPresent() && !effective.get().attemptId().equals(attempt.attemptId())) {
                    markSuperseded(connection, attempt.attemptId());
                    WorkLink existing = requireLinkByDispatch(
                            connection, principal, work.workItemId(), work.dispatchRequestId());
                    connection.commit();
                    return existing;
                }
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_dispatch_attempt SET status='EFFECTIVE', completed_at=?
                        WHERE attempt_id=? AND status='STARTED'
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(now));
                    statement.setString(2, attempt.attemptId());
                    if (statement.executeUpdate() != 1) throw new WorkbenchCasConflictException("dispatch attempt changed");
                }
                WorkLinkRelation relation = result.linkType() == WorkLinkType.RECOVERY_PLAN
                        ? WorkLinkRelation.RECOVERY : WorkLinkRelation.PRIMARY;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO agent_work_link(work_item_id, dispatch_request_id, link_type, linked_id, relation, created_at)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(dispatch_request_id) DO NOTHING
                        """)) {
                    statement.setString(1, work.workItemId());
                    statement.setString(2, work.dispatchRequestId());
                    statement.setString(3, result.linkType().name());
                    statement.setString(4, result.linkedId());
                    statement.setString(5, relation.name());
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.executeUpdate();
                }
                WorkLink link = requireLinkByDispatch(
                        connection, principal, work.workItemId(), work.dispatchRequestId());
                if (!link.linkedId().equals(result.linkedId()) || link.linkType() != result.linkType()) {
                    throw new WorkbenchIdempotencyConflictException("dispatchRequestId is linked to another target");
                }
                updateDispatchedWork(connection, work, link, now);
                appendEvent(connection, work.workItemId(), "execution-dispatched:" + work.dispatchRequestId(),
                        WorkEventType.EXECUTION_DISPATCHED, "DISPATCHED", "Execution target linked",
                        Map.of("attemptId", attempt.attemptId(), "linkType", link.linkType().name(),
                                "linkedId", link.linkedId(), "reconciled", attempt.reconciliation()), attempt.attemptId());
                if (attempt.reconciliation()) {
                    appendEvent(connection, work.workItemId(), "dispatch-reconciled:" + attempt.attemptId(),
                            WorkEventType.DISPATCH_RECONCILED, "DISPATCHED", "Dispatch reconciled by stable request id",
                            Map.of("attemptId", attempt.attemptId(), "linkedId", link.linkedId()), attempt.attemptId());
                }
                connection.commit();
                return link;
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) { throw storage("Failed to complete dispatch", exception); }
    }

    @Override
    public DispatchAttempt failDispatch(AuthenticatedPrincipal principal,
                                        DispatchClaim claim,
                                        String failureCode,
                                        String failureReason,
                                        long retryBackoffMillis,
                                        int maxAttempts) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentWorkItem work = requireWork(connection, principal, claim.attempt().workItemId(), true);
                DispatchAttempt attempt = requireAttempt(
                        connection, principal, work.workItemId(), claim.attempt().attemptId(), true);
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE agent_dispatch_attempt SET status='FAILED_ATTEMPT', failure_code=?,
                            failure_reason=?, completed_at=? WHERE attempt_id=? AND status='STARTED'
                        """)) {
                    statement.setString(1, failureCode);
                    statement.setString(2, failureReason);
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setString(4, attempt.attemptId());
                    statement.executeUpdate();
                }
                boolean exhausted = attempt.attemptNo() >= maxAttempts;
                if (exhausted) moveManualReview(connection, work, "DISPATCH_RETRY_EXHAUSTED");
                else {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE agent_work_item SET execution_state='UNKNOWN', version=version+1,
                                updated_at=? WHERE work_item_id=?
                            """)) {
                        statement.setTimestamp(1, Timestamp.from(now.plusMillis(Math.max(0, retryBackoffMillis))));
                        statement.setString(2, work.workItemId());
                        statement.executeUpdate();
                    }
                }
                appendEvent(connection, work.workItemId(), "dispatch-failed:" + attempt.attemptId(),
                        WorkEventType.DISPATCH_RECONCILED, exhausted ? "MANUAL_REVIEW" : "DISPATCHING",
                        "Dispatch attempt failed",
                        Map.of("attemptId", attempt.attemptId(), "failureCode", failureCode,
                                "exhausted", exhausted), attempt.attemptId());
                connection.commit();
                return requireAttempt(principal, work.workItemId(), attempt.attemptId());
            }
            catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        }
        catch (SQLException exception) { throw storage("Failed to record dispatch failure", exception); }
    }

    @Override
    public List<DispatchRecoveryCandidate> findStaleDispatch(Instant staleBefore, int limit) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT w.*, i.principal_roles AS recovery_principal_roles
                     FROM agent_work_item w JOIN agent_work_input i ON i.input_id=w.source_input_id
                     WHERE w.control_state='DISPATCHING'
                       AND NOT EXISTS (SELECT 1 FROM agent_dispatch_attempt e WHERE e.work_item_id=w.work_item_id AND e.status='EFFECTIVE')
                       AND EXISTS (SELECT 1 FROM agent_dispatch_attempt a WHERE a.work_item_id=w.work_item_id
                                   AND ((a.status='STARTED' AND a.created_at<=?)
                                     OR (a.status='FAILED_ATTEMPT' AND a.completed_at<=?)))
                     ORDER BY w.updated_at LIMIT ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(staleBefore));
            statement.setTimestamp(2, Timestamp.from(staleBefore));
            statement.setInt(3, Math.max(1, Math.min(100, limit)));
            try (ResultSet rs = statement.executeQuery()) {
                List<DispatchRecoveryCandidate> result = new ArrayList<>();
                while (rs.next()) {
                    AgentWorkItem work = mapWork(rs);
                    result.add(new DispatchRecoveryCandidate(work,
                            new AuthenticatedPrincipal(work.tenantId(), work.ownerPrincipalId(),
                                    stringSet(rs.getString("recovery_principal_roles")))));
                }
                return List.copyOf(result);
            }
        }
        catch (SQLException exception) { throw storage("Failed to find stale dispatch", exception); }
    }

    @Override
    public List<DispatchAttempt> listAttempts(AuthenticatedPrincipal principal, String workItemId) {
        requirePrincipal(principal);
        ensureSchema();
        try (Connection connection = openConnection()) {
            requireWork(connection, principal, workItemId, false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT a.* FROM agent_dispatch_attempt a"
                            + " JOIN agent_work_item w ON w.work_item_id=a.work_item_id"
                            + " WHERE a.work_item_id=? AND w.tenant_id=? AND w.owner_principal_id=?"
                            + " ORDER BY a.attempt_no")) {
                statement.setString(1, workItemId);
                statement.setString(2, principal.tenantId());
                statement.setString(3, principal.principalId());
                try (ResultSet rs = statement.executeQuery()) {
                    List<DispatchAttempt> result = new ArrayList<>();
                    while (rs.next()) result.add(mapAttempt(rs));
                    return List.copyOf(result);
                }
            }
        }
        catch (SQLException exception) { throw storage("Failed to list dispatch attempts", exception); }
    }

    private DispatchRequest request(Connection connection,
                                    AuthenticatedPrincipal principal,
                                    AgentWorkItem work) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT validation_json FROM agent_routing_decision WHERE decision_id=? AND decision_status='EFFECTIVE'")) {
            statement.setString(1, work.routeDecisionId());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new WorkbenchNotFoundException("effective routing decision missing");
                Map<String, Object> validation = jsonMap(rs.getString(1));
                Map<String, Object> payload = map(validation.get("typedPayload"));
                ValidatedExecutionInput input = new ValidatedExecutionInput(
                        text(validation.get("targetId")), Map.of(), payload, text(validation.get("inputDigest")));
                return new DispatchRequest(work.dispatchRequestId(), work.workItemId(), work.conversationId(),
                        work.normalizedGoal(), work.activeExecutionTarget(), principal, input, work.createdAt());
            }
        }
    }

    private void updateDispatchedWork(Connection connection, AgentWorkItem work, WorkLink link, Instant now) throws SQLException {
        String column = switch (link.linkType()) {
            case RUN -> "active_run_id";
            case INCIDENT -> "active_incident_id";
            case RECOVERY_PLAN -> "active_recovery_plan_id";
            default -> throw new IllegalArgumentException("unsupported primary dispatch link: " + link.linkType());
        };
        try (PreparedStatement statement = connection.prepareStatement("UPDATE agent_work_item SET control_state='DISPATCHED', execution_state='RUNNING', "
                + column + "=?, version=version+1, updated_at=? WHERE work_item_id=?")) {
            statement.setString(1, link.linkedId());
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, work.workItemId());
            statement.executeUpdate();
        }
    }

    private void moveManualReview(Connection connection, AgentWorkItem work, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_work_item SET control_state='MANUAL_REVIEW', execution_state='UNKNOWN',
                    outcome='MANUAL_REVIEW', version=version+1, updated_at=? WHERE work_item_id=?
                """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, work.workItemId());
            statement.executeUpdate();
        }
    }

    private void insertAttempt(Connection connection, DispatchAttempt attempt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_dispatch_attempt(attempt_id, work_item_id, dispatch_request_id, attempt_no,
                    reconciliation, target_id, status, failure_code, failure_reason, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL)
                """)) {
            statement.setString(1, attempt.attemptId()); statement.setString(2, attempt.workItemId());
            statement.setString(3, attempt.dispatchRequestId()); statement.setInt(4, attempt.attemptNo());
            statement.setBoolean(5, attempt.reconciliation()); statement.setString(6, attempt.targetId());
            statement.setString(7, attempt.status().name()); statement.setTimestamp(8, Timestamp.from(attempt.createdAt()));
            statement.executeUpdate();
        }
    }

    private Optional<DispatchAttempt> startedAttempt(Connection connection, String workItemId) throws SQLException {
        return oneAttempt(connection, "SELECT * FROM agent_dispatch_attempt WHERE work_item_id=? AND status='STARTED' ORDER BY attempt_no DESC LIMIT 1", workItemId);
    }
    private Optional<DispatchAttempt> effectiveAttempt(Connection connection, String workItemId) throws SQLException {
        return oneAttempt(connection, "SELECT * FROM agent_dispatch_attempt WHERE work_item_id=? AND status='EFFECTIVE'", workItemId);
    }
    private Optional<DispatchAttempt> latestAttempt(Connection connection, String workItemId) throws SQLException {
        return oneAttempt(connection, "SELECT * FROM agent_dispatch_attempt WHERE work_item_id=? ORDER BY attempt_no DESC LIMIT 1", workItemId);
    }
    private Optional<DispatchAttempt> oneAttempt(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(mapAttempt(rs)) : Optional.empty(); }
        }
    }
    private int nextAttemptNo(Connection connection, String workItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(attempt_no),0)+1 FROM agent_dispatch_attempt WHERE work_item_id=?")) {
            statement.setString(1, workItemId); try (ResultSet rs=statement.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
    private void markUnknown(Connection connection, String attemptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE agent_dispatch_attempt SET status='RESULT_UNKNOWN', failure_code='RESULT_PERSISTENCE_UNKNOWN', completed_at=? WHERE attempt_id=? AND status='STARTED'")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now())); statement.setString(2, attemptId); statement.executeUpdate();
        }
    }
    private void markSuperseded(Connection connection, String attemptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE agent_dispatch_attempt SET status='SUPERSEDED', completed_at=? WHERE attempt_id=? AND status='STARTED'")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now())); statement.setString(2, attemptId); statement.executeUpdate();
        }
    }

    private AgentWorkItem requireWork(Connection connection, AuthenticatedPrincipal principal, String id, boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_work_item"
                        + " WHERE work_item_id=? AND tenant_id=? AND owner_principal_id=?"
                        + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, id);
            statement.setString(2, principal.tenantId());
            statement.setString(3, principal.principalId());
            try (ResultSet rs=statement.executeQuery()) {
                if (!rs.next()) throw new WorkbenchNotFoundException("work item not found: " + id);
                return mapWork(rs);
            }
        }
    }
    private DispatchAttempt requireAttempt(Connection connection,
                                           AuthenticatedPrincipal principal,
                                           String workItemId,
                                           String id,
                                           boolean lock) throws SQLException {
        String sql = "SELECT a.* FROM agent_dispatch_attempt a"
                + " JOIN agent_work_item w ON w.work_item_id=a.work_item_id"
                + " WHERE a.attempt_id=? AND a.work_item_id=?"
                + " AND w.tenant_id=? AND w.owner_principal_id=?"
                + (lock ? " FOR UPDATE OF a" : "");
        try (PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setString(1,id);
            statement.setString(2,workItemId);
            statement.setString(3,principal.tenantId());
            statement.setString(4,principal.principalId());
            try(ResultSet rs=statement.executeQuery()){
                if(!rs.next())throw new WorkbenchNotFoundException("dispatch attempt missing");
                return mapAttempt(rs);
            }
        }
    }
    private DispatchAttempt requireAttempt(AuthenticatedPrincipal principal,String workItemId,String id) {
        try(Connection connection=openConnection()){
            return requireAttempt(connection,principal,workItemId,id,false);
        }catch(SQLException e){throw storage("Failed to read dispatch attempt",e);}
    }
    private RoutePreview requirePreview(Connection connection,AuthenticatedPrincipal principal,String workItemId,boolean lock)throws SQLException{
        return findPreview(connection,principal,workItemId,lock).orElseThrow(() -> new WorkbenchNotFoundException("route preview missing"));
    }
    private Optional<RoutePreview> findPreview(Connection connection,AuthenticatedPrincipal principal,String workItemId)throws SQLException{
        return findPreview(connection,principal,workItemId,false);
    }
    private Optional<RoutePreview> findPreview(Connection connection,AuthenticatedPrincipal principal,String workItemId,boolean lock)throws SQLException{
        String sql="SELECT p.* FROM agent_route_preview p JOIN agent_work_item w ON w.work_item_id=p.work_item_id WHERE p.work_item_id=? AND w.tenant_id=? AND w.owner_principal_id=?"+(lock?" FOR UPDATE OF p":"");
        try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setString(1,workItemId);statement.setString(2,principal.tenantId());statement.setString(3,principal.principalId());try(ResultSet rs=statement.executeQuery()){return rs.next()?Optional.of(mapPreview(rs)):Optional.empty();}}
    }
    private WorkLink requireLinkByDispatch(Connection connection,AuthenticatedPrincipal principal,String workItemId,String dispatchId)throws SQLException{
        String sql="SELECT l.* FROM agent_work_link l JOIN agent_work_item w ON w.work_item_id=l.work_item_id WHERE l.dispatch_request_id=? AND l.work_item_id=? AND w.tenant_id=? AND w.owner_principal_id=?";
        try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setString(1,dispatchId);statement.setString(2,workItemId);statement.setString(3,principal.tenantId());statement.setString(4,principal.principalId());try(ResultSet rs=statement.executeQuery()){if(!rs.next())throw new WorkbenchNotFoundException("work link missing");return new WorkLink(rs.getString("work_item_id"),rs.getString("dispatch_request_id"),WorkLinkType.valueOf(rs.getString("link_type")),rs.getString("linked_id"),WorkLinkRelation.valueOf(rs.getString("relation")),rs.getTimestamp("created_at").toInstant());}}
    }
    private void expirePreview(Connection connection,String id)throws SQLException{try(PreparedStatement s=connection.prepareStatement("UPDATE agent_route_preview SET status='EXPIRED' WHERE preview_id=? AND status='ACTIVE'")){s.setString(1,id);s.executeUpdate();}}

    private void bindPreview(PreparedStatement s,RoutePreview p)throws SQLException{
        s.setString(1,p.previewId());s.setString(2,p.workItemId());s.setString(3,p.routeDecisionId());s.setString(4,p.targetId());s.setInt(5,p.previewVersion());s.setString(6,p.validatedInputDigest());s.setString(7,p.scopeDigest());s.setString(8,json(p.payload()));s.setString(9,p.status().name());s.setTimestamp(10,Timestamp.from(p.expiresAt()));s.setTimestamp(11,Timestamp.from(p.createdAt()));
    }
    private AgentWorkItem mapWork(ResultSet rs)throws SQLException{return new AgentWorkItem(rs.getString("work_item_id"),rs.getString("conversation_id"),rs.getString("tenant_id"),rs.getString("owner_principal_id"),rs.getString("original_goal"),rs.getString("normalized_goal"),WorkControlState.valueOf(rs.getString("control_state")),WorkExecutionState.valueOf(rs.getString("execution_state")),WorkOutcome.valueOf(rs.getString("outcome")),blank(rs.getString("active_execution_target")),blank(rs.getString("active_run_id")),blank(rs.getString("active_incident_id")),blank(rs.getString("active_recovery_plan_id")),blank(rs.getString("route_decision_id")),rs.getString("source_input_id"),blank(rs.getString("parent_work_item_id")),rs.getString("routing_request_id"),rs.getInt("routing_attempt_count"),nullableInstant(rs,"routing_last_attempt_at"),nullableInstant(rs,"routing_next_retry_at"),blank(rs.getString("routing_failure_code")),blank(rs.getString("dispatch_request_id")),rs.getLong("next_event_sequence"),rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),nullableInstant(rs,"completed_at"));}
    private DispatchAttempt mapAttempt(ResultSet rs)throws SQLException{return new DispatchAttempt(rs.getString("attempt_id"),rs.getString("work_item_id"),rs.getString("dispatch_request_id"),rs.getInt("attempt_no"),rs.getBoolean("reconciliation"),rs.getString("target_id"),DispatchAttemptStatus.valueOf(rs.getString("status")),blank(rs.getString("failure_code")),blank(rs.getString("failure_reason")),rs.getTimestamp("created_at").toInstant(),nullableInstant(rs,"completed_at"));}
    private RoutePreview mapPreview(ResultSet rs)throws SQLException{return new RoutePreview(rs.getString("preview_id"),rs.getString("work_item_id"),rs.getString("route_decision_id"),rs.getString("target_id"),rs.getInt("preview_version"),rs.getString("validated_input_digest"),rs.getString("scope_digest"),jsonMap(rs.getString("payload_json")),RoutePreviewStatus.valueOf(rs.getString("status")),rs.getTimestamp("expires_at").toInstant(),blank(rs.getString("confirmed_by")),nullableInstant(rs,"confirmed_at"),rs.getTimestamp("created_at").toInstant());}

    private void appendEvent(Connection c,String workId,String sourceEventId,WorkEventType type,String phase,String summary,Map<String,Object> payload,String causation)throws SQLException{
        long sequence;try(PreparedStatement s=c.prepareStatement("SELECT next_event_sequence FROM agent_work_item WHERE work_item_id=? FOR UPDATE")){s.setString(1,workId);try(ResultSet rs=s.executeQuery()){if(!rs.next())throw new WorkbenchNotFoundException("work item missing");sequence=rs.getLong(1);}}
        Instant now=Instant.now();try(PreparedStatement s=c.prepareStatement("INSERT INTO agent_work_event(event_id,work_item_id,sequence,source_type,source_id,source_event_id,source_sequence,event_type,phase,summary,payload,correlation_id,causation_id,source_created_at,projected_at) VALUES(?,?,?,'WORK_ITEM',?,?,?,?,?,?,?::jsonb,?,?,?,?) ON CONFLICT(work_item_id,source_type,source_id,source_event_id) DO NOTHING")){s.setString(1,"wevt-"+UUID.randomUUID());s.setString(2,workId);s.setLong(3,sequence);s.setString(4,workId);s.setString(5,sourceEventId);s.setLong(6,sequence);s.setString(7,type.name());s.setString(8,phase);s.setString(9,summary);s.setString(10,json(payload));s.setString(11,workId);s.setString(12,causation);s.setTimestamp(13,Timestamp.from(now));s.setTimestamp(14,Timestamp.from(now));if(s.executeUpdate()==1){try(PreparedStatement u=c.prepareStatement("UPDATE agent_work_item SET next_event_sequence=?,updated_at=? WHERE work_item_id=?")){u.setLong(1,sequence+1);u.setTimestamp(2,Timestamp.from(now));u.setString(3,workId);u.executeUpdate();}}}
    }

    @SuppressWarnings("unchecked") private Map<String,Object> jsonMap(String value){return value==null?Map.of():Map.copyOf(objectMapper.readValue(value,Map.class));}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value){if(!(value instanceof Map<?,?> source))return Map.of();return objectMapper.convertValue(source,Map.class);}
    @SuppressWarnings("unchecked") private Set<String> stringSet(String value){return value==null?Set.of():Set.copyOf(objectMapper.readValue(value,List.class));}
    private String json(Object value){return objectMapper.writeValueAsString(value);}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String text(Object value){return value==null?"":String.valueOf(value).trim();}
    private String blank(String value){return value==null?"":value.trim();}
    private String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
    private boolean hasText(String value){return value!=null&&!value.isBlank();}
    private Instant nullableInstant(ResultSet rs,String column)throws SQLException{Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant();}
    private void requirePrincipal(AuthenticatedPrincipal principal){if(principal==null)throw new IllegalArgumentException("authenticated principal is required");}
    private void rollback(Connection c){try{c.rollback();}catch(SQLException ignored){}}
    private AgentStorageException storage(String message,Exception e){return new AgentStorageException(message,e);}
    private boolean isUniqueViolation(SQLException exception){for(SQLException current=exception;current!=null;current=current.getNextException()){if("23505".equals(current.getSQLState()))return true;}return false;}
    private Connection openConnection()throws SQLException{return DriverManager.getConnection(properties.getDatasource().getUrl(),properties.getDatasource().getUsername(),properties.getDatasource().getPassword());}

    private void ensureSchema(){if(schemaReady.get())return;synchronized(schemaReady){if(schemaReady.get())return;try(Connection c=openConnection();Statement s=c.createStatement()){for(String ddl:schema())s.execute(ddl);schemaReady.set(true);}catch(SQLException e){throw storage("Failed to initialize M1-C schema",e);}}}
    private List<String> schema(){return List.of(
            "CREATE TABLE IF NOT EXISTS agent_route_preview(preview_id TEXT PRIMARY KEY,work_item_id TEXT NOT NULL UNIQUE REFERENCES agent_work_item(work_item_id),route_decision_id TEXT NOT NULL REFERENCES agent_routing_decision(decision_id),target_id TEXT NOT NULL,preview_version INT NOT NULL,validated_input_digest CHAR(64) NOT NULL,scope_digest CHAR(64) NOT NULL,payload_json JSONB NOT NULL,status TEXT NOT NULL,expires_at TIMESTAMPTZ NOT NULL,confirmed_by TEXT,confirmed_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL)",
            "CREATE TABLE IF NOT EXISTS agent_dispatch_attempt(attempt_id TEXT PRIMARY KEY,work_item_id TEXT NOT NULL REFERENCES agent_work_item(work_item_id),dispatch_request_id TEXT NOT NULL,attempt_no INT NOT NULL,reconciliation BOOLEAN NOT NULL,target_id TEXT NOT NULL,status TEXT NOT NULL,failure_code TEXT,failure_reason TEXT,created_at TIMESTAMPTZ NOT NULL,completed_at TIMESTAMPTZ,UNIQUE(work_item_id,attempt_no),UNIQUE(dispatch_request_id,attempt_no))",
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_dispatch_effective_per_work ON agent_dispatch_attempt(work_item_id) WHERE status='EFFECTIVE'",
            "CREATE INDEX IF NOT EXISTS idx_dispatch_started ON agent_dispatch_attempt(status,created_at)"
    );}
}
