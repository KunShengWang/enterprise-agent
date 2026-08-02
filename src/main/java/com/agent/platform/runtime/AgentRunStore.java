package com.agent.platform.runtime;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface AgentRunStore {

    String DISPATCH_REQUEST_METADATA_KEY = "_workbenchDispatchRequestId";

    /**
     * 把 AgentRunRecord 保存到数据库
     */
    AgentRunRecord create(AgentRunRecord record);

    Optional<AgentRunRecord> find(String runId);

    default Optional<AgentRunRecord> findByDispatchRequestId(String dispatchRequestId) {
        return Optional.empty();
    }

    List<AgentRunRecord> recent(int limit);

    /**
     * 根据新的 AgentRunRecord 更新数据库
     */
    AgentRunRecord update(String runId, UnaryOperator<AgentRunRecord> updater);

    /**
     * 更新数据库中的 agent 运行状态和阶段
     */
    Optional<AgentRunRecord> claimForResume(String runId);

    /**
     * 只恢复已请求暂停状态和已暂停状态的 agent
     * 把 agent 从暂停状态恢复为运行状态并持久化到数据库
     */
    Optional<AgentRunRecord> claimPausedForResume(String runId);
}
