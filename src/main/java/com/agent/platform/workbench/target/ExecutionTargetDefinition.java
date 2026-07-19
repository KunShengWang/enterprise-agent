package com.agent.platform.workbench.target;

import java.util.Set;

public record ExecutionTargetDefinition(
        ExecutionTargetId targetId,
        String description,
        Set<String> supportedIntents,
        Set<String> requiredInputs,
        TargetRiskLevel riskLevel,
        TargetCostClass costClass,
        String executionProfileId,
        boolean enabled
) {
    public ExecutionTargetDefinition {
        if (targetId == null || riskLevel == null || costClass == null) {
            throw new IllegalArgumentException("targetId, riskLevel and costClass are required");
        }
        description = description == null ? "" : description.trim();
        supportedIntents = supportedIntents == null ? Set.of() : Set.copyOf(supportedIntents);
        requiredInputs = requiredInputs == null ? Set.of() : Set.copyOf(requiredInputs);
        executionProfileId = executionProfileId == null ? "" : executionProfileId.trim();
    }
}

