package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.WorkCommandDecision;

public record WorkCommandRequest(
        AgentConversationTurn input,
        WorkCommandDecision decision,
        String explicitWorkItemId,
        Long expectedWorkVersion
) {
}
