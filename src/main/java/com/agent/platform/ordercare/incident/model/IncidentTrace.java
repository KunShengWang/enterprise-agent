package com.agent.platform.ordercare.incident.model;

import com.agent.platform.trace.TraceRun;
import com.agent.platform.trace.TraceSpan;

import java.util.List;
import java.util.Map;

public record IncidentTrace(
        String incidentId,
        TraceSpan syntheticCoordinatorSpan,
        List<ChildRunTrace> childRuns,
        Map<String, Object> modelMetrics
) {
    public IncidentTrace {
        childRuns = childRuns == null ? List.of() : List.copyOf(childRuns);
        modelMetrics = modelMetrics == null ? Map.of() : Map.copyOf(modelMetrics);
    }

    public record ChildRunTrace(
            String runRole,
            String taskId,
            TraceRun trace
    ) {}
}
