package com.agent.platform.runtime;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface AgentRunStore {

    AgentRunRecord create(AgentRunRecord record);

    Optional<AgentRunRecord> find(String runId);

    List<AgentRunRecord> recent(int limit);

    AgentRunRecord update(String runId, UnaryOperator<AgentRunRecord> updater);

    Optional<AgentRunRecord> claimForResume(String runId);
}
