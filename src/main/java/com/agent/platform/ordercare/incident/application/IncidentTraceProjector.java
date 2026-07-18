package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentTrace;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.trace.RuntimeTraceProjector;
import com.agent.platform.trace.TraceRun;
import com.agent.platform.trace.TraceSpan;
import com.agent.platform.trace.TraceSpanKind;
import com.agent.platform.trace.TraceSpanStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class IncidentTraceProjector {

    private final IncidentStore incidentStore;
    private final RuntimeTraceProjector runtimeTraceProjector;

    public IncidentTraceProjector(IncidentStore incidentStore,
                                  RuntimeTraceProjector runtimeTraceProjector) {
        this.incidentStore = incidentStore;
        this.runtimeTraceProjector = runtimeTraceProjector;
    }

    public Optional<IncidentTrace> project(String incidentId) {
        IncidentAggregate aggregate = incidentStore.findAggregate(incidentId, 10_000).orElse(null);
        if (aggregate == null) {
            return Optional.empty();
        }
        List<IncidentTrace.ChildRunTrace> children = new ArrayList<>();
        add(children, "COMMANDER", "", aggregate.incident().commanderRunId());
        for (AgentTaskRecord task : aggregate.tasks()) {
            if (task.firstChildRunId() != null
                    && !task.firstChildRunId().isBlank()
                    && !task.firstChildRunId().equals(task.childRunId())) {
                add(children, "SPECIALIST:" + task.role() + ":ATTEMPT_1",
                        task.taskId(), task.firstChildRunId());
            }
            add(children, "SPECIALIST:" + task.role() + ":ATTEMPT_" + (task.attempt() + 1),
                    task.taskId(), task.childRunId());
        }
        add(children, "REVIEWER", "", aggregate.incident().reviewerRunId());

        long promptTokens = children.stream().map(IncidentTrace.ChildRunTrace::trace)
                .mapToLong(TraceRun::estimatedPromptTokens).sum();
        long completionTokens = children.stream().map(IncidentTrace.ChildRunTrace::trace)
                .mapToLong(TraceRun::estimatedCompletionTokens).sum();
        double cost = children.stream().map(IncidentTrace.ChildRunTrace::trace)
                .mapToDouble(TraceRun::estimatedCost).sum();
        long modelFailures = children.stream().map(IncidentTrace.ChildRunTrace::trace)
                .filter(trace -> "FAILED".equals(trace.status())).count();
        long durationMs = Math.max(0, Duration.between(
                aggregate.incident().createdAt(), aggregate.incident().updatedAt()).toMillis());
        String coordinatorSpanId = UUID.nameUUIDFromBytes(
                ("incident-coordinator:" + incidentId).getBytes(StandardCharsets.UTF_8)).toString();
        TraceSpan coordinator = new TraceSpan(
                coordinatorSpanId,
                incidentId,
                "",
                "incident.coordinator.synthetic",
                TraceSpanKind.SYSTEM,
                coordinatorStatus(aggregate),
                "Deterministic Incident coordination root; no model call and no agent_run_state row.",
                aggregate.incident().createdAt(),
                aggregate.incident().status().terminal() ? aggregate.incident().updatedAt() : null,
                durationMs,
                "",
                aggregate.incident().status().name(),
                "",
                Map.of(
                        "synthetic", true,
                        "excludedFromModelMetrics", true,
                        "taskCount", aggregate.tasks().size(),
                        "outcome", aggregate.incident().status().name()
                )
        );
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("modelRunCount", children.size());
        metrics.put("promptTokens", promptTokens);
        metrics.put("completionTokens", completionTokens);
        metrics.put("estimatedCost", cost);
        metrics.put("modelFailureCount", modelFailures);
        metrics.put("syntheticCoordinatorModelCalls", 0);
        return Optional.of(new IncidentTrace(incidentId, coordinator, children, Map.copyOf(metrics)));
    }

    private TraceSpanStatus coordinatorStatus(IncidentAggregate aggregate) {
        return switch (aggregate.incident().status()) {
            case FAILED -> TraceSpanStatus.FAILED;
            case CANCELLED -> TraceSpanStatus.STOPPED;
            case ASSESSED, PARTIAL, MANUAL_REVIEW -> TraceSpanStatus.COMPLETED;
            default -> TraceSpanStatus.STARTED;
        };
    }

    private void add(List<IncidentTrace.ChildRunTrace> children,
                     String role,
                     String taskId,
                     String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        runtimeTraceProjector.project(runId)
                .ifPresent(trace -> children.add(new IncidentTrace.ChildRunTrace(role, taskId, trace)));
    }
}
