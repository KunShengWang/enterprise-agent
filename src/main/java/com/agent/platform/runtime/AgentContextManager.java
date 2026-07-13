package com.agent.platform.runtime;

public interface AgentContextManager {

    AgentContextView project(String sessionId, long maxTokens);
}
