package com.agent.platform.runtime;

public interface AgentContextManager {

    AgentContextView project(String sessionId, String userId, String query, long maxTokens);
}
