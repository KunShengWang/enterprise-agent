package com.agent.platform.runtime;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface AgentRunStore {

    /**
     * 把 AgentRunRecord 保存到数据库
     */
    AgentRunRecord create(AgentRunRecord record);

    Optional<AgentRunRecord> find(String runId);

    List<AgentRunRecord> recent(int limit);

    /**
     * 根据新的 AgentRunRecord 更新数据库
     */
    AgentRunRecord update(String runId, UnaryOperator<AgentRunRecord> updater);

    Optional<AgentRunRecord> claimForResume(String runId);
}
