package com.agent.platform.workflow;

public record WorkflowTransition(
        WorkflowNode from,
        WorkflowNode to,
        String condition
) {
}
