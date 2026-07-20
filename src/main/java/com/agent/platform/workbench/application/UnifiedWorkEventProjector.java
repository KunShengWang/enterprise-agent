package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchProjectionProperties;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanEventRecord;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.workbench.model.ProjectedWorkEventDraft;
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
    private final TaskEventStore taskEventStore;
    private final IncidentRecoveryPlanStore recoveryPlanStore;
    private final WorkbenchProjectionProperties properties;
    private final String leaseOwner;

    public UnifiedWorkEventProjector(WorkEventProjectionStore projectionStore,
                                     AgentTimelineStore timelineStore,
                                     TaskEventStore taskEventStore,
                                     IncidentRecoveryPlanStore recoveryPlanStore,
                                     WorkbenchProjectionProperties properties) {
        this.projectionStore = projectionStore;
        this.timelineStore = timelineStore;
        this.taskEventStore = taskEventStore;
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
        return projected;
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
}
