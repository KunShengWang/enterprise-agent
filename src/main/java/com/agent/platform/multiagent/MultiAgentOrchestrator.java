package com.agent.platform.multiagent;

import com.agent.platform.agent.AgentRequest;

public interface MultiAgentOrchestrator {

    MultiAgentRunResponse execute(AgentRequest request);
}
