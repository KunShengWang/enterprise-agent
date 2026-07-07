package com.agent.platform.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowExecutionPlan(
        String traceId,
        String conversationId,
        String routeType,
        List<WorkflowNode> nodes,
        List<WorkflowTransition> transitions,
        boolean interruptible,
        boolean resumable,
        Instant createdAt,
        Map<String, Object> metadata
) {

    public WorkflowExecutionPlan {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
