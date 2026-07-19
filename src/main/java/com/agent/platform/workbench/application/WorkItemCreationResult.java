package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkRelation;

public record WorkItemCreationResult(
        AgentConversationTurn input,
        AgentWorkItem workItem,
        WorkRelation relation,
        ConversationWorkState focus,
        WorkEvent createdEvent,
        boolean duplicate
) {
}
