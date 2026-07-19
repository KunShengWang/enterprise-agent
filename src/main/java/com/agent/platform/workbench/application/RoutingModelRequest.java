package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.target.ExecutionTargetDefinition;

import java.util.List;

public record RoutingModelRequest(
        AgentWorkItem workItem,
        String goalText,
        List<ExecutionTargetDefinition> enabledTargets,
        String conversationSummary
) {
    public RoutingModelRequest {
        if (workItem == null || enabledTargets == null || enabledTargets.isEmpty()) {
            throw new IllegalArgumentException("workItem and enabledTargets are required");
        }
        goalText = goalText == null ? "" : goalText.trim();
        enabledTargets = List.copyOf(enabledTargets);
        conversationSummary = conversationSummary == null ? "" : conversationSummary.trim();
    }
}

