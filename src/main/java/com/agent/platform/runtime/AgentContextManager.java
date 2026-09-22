package com.agent.platform.runtime;

public interface AgentContextManager {

    /**
     * 兼容旧调用方；新的 Runtime 调用应提供可信 tenant/profile，以便完成 canonical context 投影和记忆门控。
     */
    AgentContextView project(String sessionId, String userId, String query, long maxTokens);

    default AgentContextView project(String sessionId,
                                     String userId,
                                     String tenantId,
                                     String query,
                                     long maxTokens,
                                     AgentExecutionProfile profile) {
        return project(sessionId, userId, query, maxTokens);
    }

    /**
     * 将已被上下文窗口淘汰的完整消息单元压缩为持久化摘要，再重新投影模型视图。
     */
    AgentContextView compact(String sessionId,
                             String userId,
                             String runId,
                             String query,
                             long maxTokens,
                             String reason);

    default AgentContextView compact(String sessionId,
                                     String userId,
                                     String tenantId,
                                     String runId,
                                     String query,
                                     long maxTokens,
                                     String reason,
                                     AgentExecutionProfile profile) {
        return compact(sessionId, userId, runId, query, maxTokens, reason);
    }
}
