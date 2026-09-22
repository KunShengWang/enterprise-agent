package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchProjectionProperties;
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
    private final WorkbenchProjectionProperties properties;
    private final String leaseOwner;
    private final Map<String, WorkEventProjectionContributor> contributors;
    private final java.util.Set<String> supportedSourceTypes;

    public UnifiedWorkEventProjector(WorkEventProjectionStore projectionStore,
                                     AgentTimelineStore timelineStore,
                                     AgentRunStore runStore,
                                     List<WorkEventProjectionContributor> contributors,
                                     WorkbenchProjectionProperties properties) {
        this.projectionStore = projectionStore;
        this.timelineStore = timelineStore;
        this.runStore = runStore;
        Map<String, WorkEventProjectionContributor> indexed = new LinkedHashMap<>();
        for (WorkEventProjectionContributor contributor : contributors) {
            for (String type : contributor.sourceTypes()) {
                if (!java.util.Set.of("INCIDENT", "RECOVERY_PLAN").contains(type)
                        || indexed.putIfAbsent(type, contributor) != null) {
                    throw new IllegalArgumentException("unsupported or duplicate historical projection contributor: " + type);
                }
            }
        }
        this.contributors = Map.copyOf(indexed);
        // Historical contributors cannot opt old sources back into background projection.
        this.supportedSourceTypes = java.util.Set.of("AGENT_RUN");
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
                leaseOwner, Instant.now().plusMillis(properties.getLeaseMillis()), properties.getSourceBatchSize(), supportedSourceTypes);
        for (WorkProjectionClaim claim : claims) {
            if (!supportedSourceTypes.contains(claim.source().sourceType())) continue;
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
            default -> contributors.get(source.sourceType()).projectEvents(
                    claim, cursor, properties.getEventBatchSize(), projectionStore);
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
            default -> contributors.get(source.sourceType()).executionProjection(source);
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

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ProjectionBatchResult(int sourceCount, int projectedEventCount, int failedSourceCount) {
    }

    private record ProjectionState(WorkControlState controlState, WorkExecutionState executionState,
                                   WorkOutcome outcome, boolean completed) {
    }
}
