package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchProjectionProperties;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanEventRecord;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.workbench.model.ProjectedWorkEventDraft;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionProjection;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkProjectionSource;
import com.agent.platform.workbench.model.WorkProjectionClaim;
import com.agent.platform.workbench.persistence.WorkEventProjectionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;

@Component
public class UnifiedWorkEventProjector {

    private static final Logger log = LoggerFactory.getLogger(UnifiedWorkEventProjector.class);

    private final WorkEventProjectionStore projectionStore;
    private final AgentTimelineStore timelineStore;
    private final AgentRunStore runStore;
    private final TaskEventStore taskEventStore;
    private final IncidentStore incidentStore;
    private final IncidentRecoveryPlanStore recoveryPlanStore;
    private final WorkbenchProjectionProperties properties;
    private final String leaseOwner;

    public UnifiedWorkEventProjector(WorkEventProjectionStore projectionStore,
                                     AgentTimelineStore timelineStore,
                                     AgentRunStore runStore,
                                     TaskEventStore taskEventStore,
                                     IncidentStore incidentStore,
                                     IncidentRecoveryPlanStore recoveryPlanStore,
                                     WorkbenchProjectionProperties properties) {
        this.projectionStore = projectionStore;
        this.timelineStore = timelineStore;
        this.runStore = runStore;
        this.taskEventStore = taskEventStore;
        this.incidentStore = incidentStore;
        this.recoveryPlanStore = recoveryPlanStore;
        this.properties = properties;
        this.leaseOwner = properties.getInstanceId().isBlank()
                ? "work-projector-" + UUID.randomUUID()
                : properties.getInstanceId();
    }

    @Scheduled(fixedDelayString = "${enterprise-agent.workbench.projection.scan-delay-millis:2000}")
    public void scheduledProject() {
        if (!properties.isEnabled()) return;
        projectOnce();
    }

    public ProjectionBatchResult projectOnce() {
        if (!properties.isEnabled()) return new ProjectionBatchResult(0, 0, 0);
        int projected = 0;
        int failed = 0;
        List<WorkProjectionClaim> claims = projectionStore.claimProjectionSources(
                leaseOwner, Instant.now().plusMillis(properties.getLeaseMillis()), properties.getSourceBatchSize());
        for (WorkProjectionClaim claim : claims) {
            try {
                projected += projectSource(claim);
            } catch (RuntimeException exception) {
                failed++;
                WorkProjectionSource source = claim.source();
                log.warn("work event projection delayed: workItemId={}, sourceType={}, sourceId={}",
                        source.workItemId(), source.sourceType(), source.sourceId(), exception);
            } finally {
                try {
                    projectionStore.releaseProjectionClaim(claim);
                } catch (RuntimeException exception) {
                    WorkProjectionSource source = claim.source();
                    log.warn("work event projection lease release delayed: workItemId={}, sourceType={}, sourceId={}",
                            source.workItemId(), source.sourceType(), source.sourceId(), exception);
                }
            }
        }
        return new ProjectionBatchResult(claims.size(), projected, failed);
    }

    private int projectSource(WorkProjectionClaim claim) {
        WorkProjectionSource source = claim.source();
        long cursor = projectionStore.projectionCursor(
                source.workItemId(), source.sourceType(), source.sourceId());
        int projected = switch (source.sourceType()) {
            case "AGENT_RUN" -> projectRun(claim, cursor);
            case "INCIDENT" -> projectIncident(claim, cursor);
            case "RECOVERY_PLAN" -> projectRecoveryPlan(claim, cursor);
            default -> throw new IllegalArgumentException("unsupported work event source: " + source.sourceType());
        };
        if (projected == 0) {
            projectionStore.advanceProjectionCursor(claim, cursor);
        }
        reconcileExecutionState(claim);
        return projected;
    }

    private void reconcileExecutionState(WorkProjectionClaim claim) {
        WorkProjectionSource source = claim.source();
        WorkExecutionProjection projection = switch (source.sourceType()) {
            case "AGENT_RUN" -> runStore.find(source.sourceId()).map(this::runProjection).orElse(null);
            case "INCIDENT" -> incidentStore.find(source.sourceId()).map(this::incidentProjection).orElse(null);
            case "RECOVERY_PLAN" -> recoveryPlanStore.find(source.sourceId())
                    .map(this::recoveryPlanProjection).orElse(null);
            default -> null;
        };
        if (projection != null) projectionStore.reconcileExecutionState(claim, projection);
    }

    private WorkExecutionProjection runProjection(AgentRunRecord run) {
        ProjectionState state = switch (run.state()) {
            case CREATED, RUNNING -> active();
            case WAITING_APPROVAL -> state(WorkControlState.DISPATCHED,
                    WorkExecutionState.WAITING_APPROVAL, WorkOutcome.UNDETERMINED, false);
            case WAITING_INPUT, NEEDS_CLARIFICATION -> state(WorkControlState.WAITING_INPUT,
                    WorkExecutionState.WAITING_INPUT, WorkOutcome.UNDETERMINED, false);
            case PAUSE_REQUESTED -> state(WorkControlState.PAUSE_REQUESTED,
                    WorkExecutionState.RUNNING, WorkOutcome.UNDETERMINED, false);
            case PAUSED -> state(WorkControlState.PAUSED,
                    WorkExecutionState.PAUSED, WorkOutcome.UNDETERMINED, false);
            case COMPLETED -> terminal(WorkExecutionState.COMPLETED, WorkOutcome.ANSWERED);
            case FAILED, BLOCKED -> terminal(WorkExecutionState.FAILED, WorkOutcome.FAILED);
            case REJECTED -> "CANCELLED".equals(run.failureReason())
                    ? terminal(WorkExecutionState.CANCELLED, WorkOutcome.CANCELLED)
                    : terminal(WorkExecutionState.CANCELLED, WorkOutcome.REJECTED);
            case MANUAL_REVIEW -> state(WorkControlState.MANUAL_REVIEW,
                    WorkExecutionState.UNKNOWN, WorkOutcome.MANUAL_REVIEW, true);
        };
        return projection("AGENT_RUN", run.runId(), run.version(), run.resumeCount(), run.state().name(),
                run.failureReason(), run.updatedAt(), state);
    }

    private WorkExecutionProjection incidentProjection(IncidentRecord incident) {
        ProjectionState state = switch (incident.status()) {
            case CREATED, PLANNING, INVESTIGATING, CHECKING_CONSISTENCY, REVIEWING -> active();
            case CLARIFYING -> state(WorkControlState.WAITING_INPUT,
                    WorkExecutionState.WAITING_INPUT, WorkOutcome.UNDETERMINED, false);
            case ASSESSED -> terminal(WorkExecutionState.COMPLETED, WorkOutcome.ASSESSED);
            case PARTIAL -> terminal(WorkExecutionState.COMPLETED, WorkOutcome.NOT_CONVERGED);
            case MANUAL_REVIEW -> state(WorkControlState.MANUAL_REVIEW,
                    WorkExecutionState.UNKNOWN, WorkOutcome.MANUAL_REVIEW, true);
            case FAILED -> terminal(WorkExecutionState.FAILED, WorkOutcome.FAILED);
            case CANCELLED -> terminal(WorkExecutionState.CANCELLED, WorkOutcome.CANCELLED);
        };
        return projection("INCIDENT", incident.incidentId(), incident.version(), 0, incident.status().name(),
                "", incident.updatedAt(), state);
    }

    private WorkExecutionProjection recoveryPlanProjection(IncidentRecoveryPlanRecord plan) {
        ProjectionState state = switch (plan.status()) {
            case CREATED, PLANNING, PREVIEWING, EXECUTING -> active();
            case WAITING_APPROVAL -> state(WorkControlState.DISPATCHED,
                    WorkExecutionState.WAITING_APPROVAL, WorkOutcome.UNDETERMINED, false);
            case FAILED -> terminal(WorkExecutionState.FAILED, WorkOutcome.FAILED);
            case CANCELLED -> terminal(WorkExecutionState.CANCELLED, WorkOutcome.CANCELLED);
            case COMPLETED -> switch (plan.outcome()) {
                case RESOLVED -> terminal(WorkExecutionState.COMPLETED, WorkOutcome.RESOLVED);
                case PARTIAL -> terminal(WorkExecutionState.COMPLETED, WorkOutcome.NOT_CONVERGED);
                case REJECTED -> terminal(WorkExecutionState.COMPLETED, WorkOutcome.REJECTED);
                case MANUAL_REVIEW -> state(WorkControlState.MANUAL_REVIEW,
                        WorkExecutionState.UNKNOWN, WorkOutcome.MANUAL_REVIEW, true);
                case READY, NOT_STARTED -> terminal(WorkExecutionState.COMPLETED, WorkOutcome.ASSESSED);
            };
        };
        return projection("RECOVERY_PLAN", plan.planId(), plan.version(), 0, plan.status().name(),
                plan.outcome().name(), plan.updatedAt(), state);
    }

    private WorkExecutionProjection projection(String sourceType, String sourceId, long sourceVersion,
                                                int sourceAttempt, String sourceStatus, String sourceOutcome,
                                                Instant updatedAt, ProjectionState state) {
        return new WorkExecutionProjection(sourceType, sourceId, sourceVersion, sourceAttempt,
                sourceStatus, sourceOutcome, state.controlState(), state.executionState(), state.outcome(),
                updatedAt, state.completed() ? updatedAt : null);
    }

    private ProjectionState active() {
        return state(WorkControlState.DISPATCHED, WorkExecutionState.RUNNING, WorkOutcome.UNDETERMINED, false);
    }

    private ProjectionState terminal(WorkExecutionState executionState, WorkOutcome outcome) {
        return state(WorkControlState.CLOSED, executionState, outcome, true);
    }

    private ProjectionState state(WorkControlState controlState, WorkExecutionState executionState,
                                  WorkOutcome outcome, boolean completed) {
        return new ProjectionState(controlState, executionState, outcome, completed);
    }

    private int projectRun(WorkProjectionClaim claim, long cursor) {
        WorkProjectionSource source = claim.source();
        int projected = 0;
        for (AgentEvent event : timelineStore.loadEventsAfter(
                source.sourceId(), cursor, properties.getEventBatchSize())) {
            if (event.type() == AgentEventType.MODEL_DELTA || event.type() == AgentEventType.HEARTBEAT) {
                projectionStore.advanceProjectionCursor(claim, event.sequence());
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>(event.payload());
            payload.put("runtimeEventType", event.type().name());
            payload.put("runId", event.runId());
            payload.put("sessionId", event.sessionId());
            projectionStore.appendProjectedEvent(claim, new ProjectedWorkEventDraft(
                    source.sourceType(), source.sourceId(), event.eventId(), event.sequence(),
                    WorkEventType.RUN_EVENT_PROJECTED, event.type().name(), event.content(), payload,
                    source.workItemId(), text(event.payload().get("causationId")), event.createdAt()));
            projected++;
        }
        return projected;
    }

    private int projectIncident(WorkProjectionClaim claim, long cursor) {
        WorkProjectionSource source = claim.source();
        int projected = 0;
        for (TaskEventRecord event : taskEventStore.loadEventsAfter(
                source.sourceId(), cursor, properties.getEventBatchSize())) {
            Map<String, Object> payload = new LinkedHashMap<>(event.payload());
            put(payload, "incidentEventType", event.eventType().name());
            put(payload, "eventCategory", event.eventCategory().name());
            put(payload, "actorType", event.actorType().name());
            put(payload, "actorId", event.actorId());
            put(payload, "taskId", event.taskId());
            put(payload, "childRunId", event.childRunId());
            put(payload, "senderRole", event.senderRole());
            put(payload, "recipientRole", event.recipientRole());
            payload.put("messageDepth", event.messageDepth());
            projectionStore.appendProjectedEvent(claim, new ProjectedWorkEventDraft(
                    source.sourceType(), source.sourceId(), event.eventId(), event.eventSequence(),
                    WorkEventType.INCIDENT_EVENT_PROJECTED, event.eventType().name(),
                    eventSummary(event), payload, event.correlationId(), event.causationId(), event.createdAt()));
            projected++;
        }
        return projected;
    }

    private int projectRecoveryPlan(WorkProjectionClaim claim, long cursor) {
        WorkProjectionSource source = claim.source();
        int projected = 0;
        for (RecoveryPlanEventRecord event : recoveryPlanStore.loadEventsAfter(
                source.sourceId(), cursor, properties.getEventBatchSize())) {
            projectionStore.appendProjectedEvent(claim, new ProjectedWorkEventDraft(
                    source.sourceType(), source.sourceId(), event.eventId(), event.sequence(),
                    WorkEventType.RECOVERY_PLAN_EVENT_PROJECTED, event.eventType(),
                    "Recovery plan state snapshot persisted", event.payload(),
                    source.workItemId(), event.planId(), event.createdAt()));
            projected++;
        }
        return projected;
    }

    private String eventSummary(TaskEventRecord event) {
        Object summary = event.payload().get("summary");
        return summary == null ? event.eventType().name() : String.valueOf(summary);
    }

    private void put(Map<String, Object> payload, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) payload.put(key, value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ProjectionBatchResult(int sourceCount, int projectedEventCount, int failedSourceCount) {
    }

    private record ProjectionState(WorkControlState controlState, WorkExecutionState executionState,
                                   WorkOutcome outcome, boolean completed) {
    }
}
