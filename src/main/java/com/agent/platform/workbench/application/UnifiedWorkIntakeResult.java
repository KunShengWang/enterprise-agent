package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkCommandDecision;

public record UnifiedWorkIntakeResult(
        AgentConversationTurn input,
        WorkCommandDecision commandDecision,
        AgentWorkItem workItem,
        boolean commandOnly
) {
}
