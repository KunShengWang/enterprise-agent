package com.agent.platform.workbench.application;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.config.WorkbenchProjectionProperties;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.model.TaskEventCategory;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.persistence.JdbcIncidentStore;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.incident.recovery.persistence.JdbcIncidentRecoveryPlanStore;
import com.agent.platform.runtime.AgentEventDraft;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentRunPhase;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.JdbcAgentRuntimeStore;
import com.agent.platform.runtime.JdbcAgentTimelineStore;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.storage.AgentStorageException;
import com.agent.platform.workbench.model.ProjectedWorkEventDraft;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.model.WorkExecutionProjection;
import com.agent.platform.workbench.model.WorkProjectionSource;
import com.agent.platform.workbench.model.WorkProjectionClaim;
import com.agent.platform.workbench.persistence.WorkbenchCasConflictException;
import com.agent.platform.workbench.persistence.JdbcWorkbenchStore;
import com.agent.platform.workbench.persistence.WorkEventProjectionStore;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class UnifiedWorkEventProjectorPostgresIT {

    private final String suffix = UUID.randomUUID().toString();
    private final AgentStorageProperties properties = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-m2a-" + suffix, "projector-user", Set.of("USER", "INCIDENT_OPERATOR"));
    private final String sessionId = "session-m2a-" + suffix;
    private final String runId = "run-m2a-" + suffix;
    private final String incidentId = "inc-m2a-" + suffix;
    private final String planId = "plan-m2a-" + suffix;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT work_item_id FROM agent_work_item WHERE tenant_id=? FOR UPDATE")) {
                statement.setString(1, principal.tenantId());
                statement.executeQuery();
            }
            execute(connection, "DELETE FROM agent_dispatch_attempt WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_route_preview WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_routing_decision WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_command_decision WHERE tenant_id = ?", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_event WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_projection_cursor WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_link WHERE work_item_id IN (SELECT work_item_id FROM agent_work_item WHERE tenant_id = ?)", principal.tenantId());
            execute(connection, "DELETE FROM agent_conversation_work_state WHERE tenant_id = ?", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_item WHERE tenant_id = ?", principal.tenantId());
            execute(connection, "DELETE FROM agent_work_input WHERE tenant_id = ?", principal.tenantId());
            execute(connection, "DELETE FROM agent_incident_recovery_plan_event WHERE incident_id = ?", incidentId);
            execute(connection, "DELETE FROM agent_incident_recovery_plan WHERE incident_id = ?", incidentId);
            execute(connection, "DELETE FROM agent_task_event WHERE incident_id = ?", incidentId);
            execute(connection, "DELETE FROM agent_evidence WHERE incident_id = ?", incidentId);
            execute(connection, "DELETE FROM agent_task WHERE incident_id = ?", incidentId);
            execute(connection, "DELETE FROM agent_incident WHERE incident_id = ?", incidentId);
            execute(connection, "DELETE FROM agent_run_state WHERE run_id = ?", runId);
            execute(connection, "DELETE FROM agent_session WHERE session_id = ?", sessionId);
            connection.commit();
        }
    }

    @Test
    void projectsActualRuntimeIncidentAndRecoverySourcesAndReplayRemainsIdempotent() {
        JdbcWorkbenchStore workbench = new JdbcWorkbenchStore(properties, objectMapper);
        WorkInputService inputService = new WorkInputService(new WorkItemService(workbench));
        var runWork = inputService.submit(principal, SubmitWorkInputCommand.direct(
                "client-run-" + suffix, "conversation-run-" + suffix, "run goal", 0));
        var incidentWork = inputService.submit(principal, SubmitWorkInputCommand.direct(
                "client-incident-" + suffix, "conversation-incident-" + suffix, "incident goal", 0));
        var planWork = inputService.submit(principal, SubmitWorkInputCommand.direct(
                "client-plan-" + suffix, "conversation-plan-" + suffix, "plan goal", 0));
        insertLink(runWork.workItem().workItemId(), WorkLinkType.RUN, runId, "dispatch-run");
        insertLink(incidentWork.workItem().workItemId(), WorkLinkType.INCIDENT, incidentId, "dispatch-incident");
        insertLink(planWork.workItem().workItemId(), WorkLinkType.RECOVERY_PLAN, planId, "dispatch-plan");

        JdbcAgentTimelineStore timeline = new JdbcAgentTimelineStore(properties, objectMapper);
        JdbcAgentRuntimeStore runs = new JdbcAgentRuntimeStore(properties, objectMapper);
        AgentRequest runRequest = new AgentRequest(runWork.workItem().conversationId(), principal.principalId(),
                "run goal", Map.of("workItemId", runWork.workItem().workItemId(),
                com.agent.platform.runtime.AgentRunStore.DISPATCH_REQUEST_METADATA_KEY,
                "dispatch-run-" + suffix));
        runs.create(AgentRunRecord.create(runId, "trace-" + suffix,
                        runWork.workItem().conversationId(), runRequest)
                .finished(AgentRunState.COMPLETED, AgentRunPhase.FINISHED, "final answer", "",
                        List.of(), List.of(), false, false));
        timeline.openSession(sessionId, principal.principalId());
        timeline.appendEvent(sessionId, principal.principalId(), runId,
                new AgentEventDraft(AgentEventType.RUN_STARTED, "run started", Map.of()));
        timeline.appendEvent(sessionId, principal.principalId(), runId,
                new AgentEventDraft(AgentEventType.MODEL_DELTA, "hidden delta", Map.of()));
        timeline.appendEvent(sessionId, principal.principalId(), runId,
                new AgentEventDraft(AgentEventType.RUN_COMPLETED, "run completed", Map.of()));

        JdbcIncidentStore incidents = new JdbcIncidentStore(properties, objectMapper);
        incidents.create(incident());
        incidents.appendEvent(new TaskEventRecord(
                "incident-event-" + suffix, incidentId, null, null, 0,
                TaskEventType.INCIDENT_STATE_CHANGED, TaskEventCategory.LIFECYCLE,
                TaskEventActorType.SYSTEM, "incident-store", null, null, 0,
                incidentId, null, "incident-event-key-" + suffix,
                Map.of("summary", "incident assessed"), Instant.now()));

        JdbcIncidentRecoveryPlanStore plans = new JdbcIncidentRecoveryPlanStore(properties, objectMapper);
        plans.create(new IncidentRecoveryPlanRecord(
                planId, incidentId, "request-" + suffix, "", "digest-" + suffix,
                RecoveryPlanStatus.CREATED, RecoveryPlanOutcome.NOT_STARTED, null,
                List.of(), List.of(), 0, Instant.now(), Instant.now()));

        List<WorkProjectionSource> sources = List.of(
                new WorkProjectionSource(runWork.workItem().workItemId(), "AGENT_RUN", runId),
                new WorkProjectionSource(incidentWork.workItem().workItemId(), "INCIDENT", incidentId),
                new WorkProjectionSource(planWork.workItem().workItemId(), "RECOVERY_PLAN", planId));
        seedProjectionCursors(sources);
        Instant ownerALease = Instant.now().plusSeconds(30);
        List<WorkProjectionClaim> ownerA = sources.stream()
                .map(source -> new WorkProjectionClaim(source, "projector-a", 1, ownerALease)).toList();
        assertTrue(workbench.claimProjectionSources(
                "projector-b", Instant.now().plusSeconds(30), 1000).stream()
                .noneMatch(claim -> sources.contains(claim.source())));
        Instant ownerBLease = Instant.now().plusSeconds(30);
        handoffProjectionLease(sources, "projector-b", 2, ownerBLease);
        List<WorkProjectionClaim> ownerB = sources.stream()
                .map(source -> new WorkProjectionClaim(source, "projector-b", 2, ownerBLease)).toList();
        assertEquals(3, ownerB.size());
        assertTrue(ownerB.stream().allMatch(claim -> claim.fencingToken() == 2));
        assertThrows(WorkbenchCasConflictException.class,
                () -> workbench.advanceProjectionCursor(ownerA.get(0), 0));
        AgentRunRecord completedRun = runs.find(runId).orElseThrow();
        WorkProjectionClaim staleRunClaim = ownerA.stream()
                .filter(claim -> claim.source().sourceType().equals("AGENT_RUN")).findFirst().orElseThrow();
        assertThrows(WorkbenchCasConflictException.class, () -> workbench.reconcileExecutionState(
                staleRunClaim, new WorkExecutionProjection("AGENT_RUN", runId, completedRun.version(),
                        completedRun.resumeCount(), completedRun.state().name(), completedRun.failureReason(),
                        WorkControlState.CLOSED, WorkExecutionState.COMPLETED, WorkOutcome.ANSWERED,
                        completedRun.updatedAt(), completedRun.updatedAt())));
        handoffProjectionLease(sources, "projector-test", 3, Instant.now().plusSeconds(30));
        WorkbenchProjectionProperties projectionProperties = new WorkbenchProjectionProperties();
        projectionProperties.setEnabled(true);
        projectionProperties.setInstanceId("projector-test");
        var projector = new UnifiedWorkEventProjector(
                new FixedSourceProjectionStore(workbench, sources), timeline,
                runs,
                incidents, incidents, plans, projectionProperties);

        var first = projector.projectOnce();
        var completedRunWork = workbench.findWorkItem(principal, runWork.workItem().workItemId()).orElseThrow();
        long completedVersion = completedRunWork.version();
        for (int attempt = 0; attempt < 10; attempt++) projector.projectOnce();

        assertEquals(4, first.projectedEventCount());
        var runEvents = workbench.loadEvents(principal, runWork.workItem().workItemId(), -1, 100);
        assertEquals(2, runEvents.stream()
                .filter(event -> event.eventType() == WorkEventType.RUN_EVENT_PROJECTED).count());
        assertTrue(runEvents.stream().noneMatch(event -> "MODEL_DELTA".equals(event.phase())));
        assertEquals(1, workbench.loadEvents(principal, incidentWork.workItem().workItemId(), -1, 100).stream()
                .filter(event -> event.eventType() == WorkEventType.INCIDENT_EVENT_PROJECTED).count());
        assertEquals(1, workbench.loadEvents(principal, planWork.workItem().workItemId(), -1, 100).stream()
                .filter(event -> event.eventType() == WorkEventType.RECOVERY_PLAN_EVENT_PROJECTED).count());
        assertEquals(WorkControlState.CLOSED, completedRunWork.controlState());
        assertEquals(WorkExecutionState.COMPLETED, completedRunWork.executionState());
        assertEquals(WorkOutcome.ANSWERED, completedRunWork.outcome());
        assertEquals(completedVersion,
                workbench.findWorkItem(principal, runWork.workItem().workItemId()).orElseThrow().version());
        assertEquals(WorkOutcome.ASSESSED,
                workbench.findWorkItem(principal, incidentWork.workItem().workItemId()).orElseThrow().outcome());

        runs.update(runId, AgentRunRecord::claimedForRecovery);
        projector.projectOnce();
        var resumedWork = workbench.findWorkItem(principal, runWork.workItem().workItemId()).orElseThrow();
        assertEquals(WorkControlState.DISPATCHED, resumedWork.controlState());
        assertEquals(WorkExecutionState.RUNNING, resumedWork.executionState());
        assertEquals(WorkOutcome.UNDETERMINED, resumedWork.outcome());
        assertEquals(null, resumedWork.completedAt());
    }

    private IncidentRecord incident() {
        Instant now = Instant.now();
        IncidentSnapshot snapshot = new IncidentSnapshot(
                "snapshot-" + suffix, incidentId, "alert", "DLQ", principal.tenantId(),
                new IncidentSnapshot.IncidentOrderScope(List.of("REQ-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of()),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope-" + suffix);
        return new IncidentRecord(
                incidentId, null, null, "incident:" + incidentId, "scenario",
                IncidentStatus.ASSESSED, snapshot, Map.of(), Map.of(), 0, 1, 1, 0, now, now);
    }

    private void seedProjectionCursors(List<WorkProjectionSource> sources) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_work_projection_cursor(
                         work_item_id, source_type, source_id, last_source_sequence,
                         lease_owner, lease_until, fencing_token, updated_at)
                     VALUES (?, ?, ?, -1, 'projector-a', ?, 1, ?)
                     ON CONFLICT(work_item_id, source_type, source_id) DO UPDATE
                     SET lease_owner='projector-a', lease_until=EXCLUDED.lease_until,
                         fencing_token=1, updated_at=EXCLUDED.updated_at
                     """)) {
            Instant leaseUntil = Instant.now().plusSeconds(30);
            for (WorkProjectionSource source : sources) {
                statement.setString(1, source.workItemId());
                statement.setString(2, source.sourceType());
                statement.setString(3, source.sourceId());
                statement.setTimestamp(4, java.sql.Timestamp.from(leaseUntil));
                statement.setTimestamp(5, java.sql.Timestamp.from(Instant.EPOCH.plusSeconds(1)));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (Exception exception) {
            throw new AgentStorageException("failed to seed projection cursors", exception);
        }
    }

    private void handoffProjectionLease(List<WorkProjectionSource> sources, String owner,
                                        long fencingToken, Instant leaseUntil) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_work_projection_cursor
                     SET lease_owner=?, lease_until=?, fencing_token=?
                     WHERE work_item_id=? AND source_type=? AND source_id=?
                     """)) {
            for (WorkProjectionSource source : sources) {
                statement.setString(1, owner);
                statement.setTimestamp(2, java.sql.Timestamp.from(leaseUntil));
                statement.setLong(3, fencingToken);
                statement.setString(4, source.workItemId());
                statement.setString(5, source.sourceType());
                statement.setString(6, source.sourceId());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (Exception exception) {
            throw new AgentStorageException("failed to hand off projection fixture lease", exception);
        }
    }

    private void insertLink(String workItemId, WorkLinkType type, String linkedId, String dispatch) {
        try (Connection connection = openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_work_link(work_item_id, dispatch_request_id, link_type, linked_id, relation, created_at)
                     VALUES (?, ?, ?, ?, 'PRIMARY', ?)
                     """)) {
                statement.setString(1, workItemId);
                statement.setString(2, dispatch + "-" + suffix);
                statement.setString(3, type.name());
                statement.setString(4, linkedId);
                statement.setTimestamp(5, java.sql.Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            String activeColumn = switch (type) {
                case RUN -> "active_run_id";
                case INCIDENT -> "active_incident_id";
                case RECOVERY_PLAN -> "active_recovery_plan_id";
                default -> throw new IllegalArgumentException("unsupported link type");
            };
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE agent_work_item SET control_state='DISPATCHED', execution_state='RUNNING', "
                            + activeColumn + "=? WHERE work_item_id=?")) {
                statement.setString(1, linkedId);
                statement.setString(2, workItemId);
                statement.executeUpdate();
            }
        } catch (Exception exception) {
            throw new AgentStorageException("failed to insert projector link", exception);
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
        return DriverManager.getConnection(properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof Instant instant) {
                    statement.setTimestamp(index + 1, java.sql.Timestamp.from(instant));
                } else {
                    statement.setObject(index + 1, value);
                }
            }
            statement.executeUpdate();
        }
    }

    private record FixedSourceProjectionStore(
            WorkEventProjectionStore delegate,
            List<WorkProjectionSource> sources
    ) implements WorkEventProjectionStore {
        @Override public List<WorkProjectionSource> listProjectionSources(int limit) { return sources; }
        @Override public List<WorkProjectionClaim> claimProjectionSources(String owner, Instant until, int limit) {
            List<WorkProjectionClaim> claimed = delegate.claimProjectionSources(owner, until, 1000);
            claimed.stream().filter(claim -> !sources.contains(claim.source())).forEach(delegate::releaseProjectionClaim);
            return claimed.stream().filter(claim -> sources.contains(claim.source())).toList();
        }
        @Override public long projectionCursor(String workItemId, String sourceType, String sourceId) {
            return delegate.projectionCursor(workItemId, sourceType, sourceId);
        }
        @Override public WorkEvent appendProjectedEvent(String workItemId, ProjectedWorkEventDraft event) {
            return delegate.appendProjectedEvent(workItemId, event);
        }
        @Override public WorkEvent appendProjectedEvent(WorkProjectionClaim claim, ProjectedWorkEventDraft event) {
            return delegate.appendProjectedEvent(claim, event);
        }
        @Override public void advanceProjectionCursor(String workItemId, String sourceType, String sourceId, long sourceSequence) {
            delegate.advanceProjectionCursor(workItemId, sourceType, sourceId, sourceSequence);
        }
        @Override public void advanceProjectionCursor(WorkProjectionClaim claim, long sourceSequence) {
            delegate.advanceProjectionCursor(claim, sourceSequence);
        }
        @Override public void releaseProjectionClaim(WorkProjectionClaim claim) {
            // Keep this test's valid lease across replay scans so a locally running application
            // cannot claim the fixture source between the terminal and resumed assertions.
        }
        @Override public boolean reconcileExecutionState(WorkProjectionClaim claim, WorkExecutionProjection projection) {
            return delegate.reconcileExecutionState(claim, projection);
        }
    }
}
