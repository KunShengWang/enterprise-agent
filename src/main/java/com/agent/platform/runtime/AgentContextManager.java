package com.agent.platform.runtime;

public interface AgentContextManager {

    AgentContextView project(String sessionId, String userId, String query, long maxTokens);

    /**
     * 将已被上下文窗口淘汰的完整消息单元压缩为持久化摘要，再重新投影模型视图。
     */
    AgentContextView compact(String sessionId,
                             String userId,
                             String runId,
                             String query,
                             long maxTokens,
                             String reason);
}
