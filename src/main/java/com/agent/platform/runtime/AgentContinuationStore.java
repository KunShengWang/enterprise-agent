package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;

import java.util.Optional;

/**
 * 同一个 PostgreSQL 事务内维护 WAITING_INPUT 检查点、Timeline 和事件序列。
 */
public interface AgentContinuationStore {

    AgentContinuationTransition checkpointWaitingInput(
            String runId,
            long expectedVersion,
            String answer,
            AgentRunBudgetSnapshot budget,
            AgentMessageDraft assistantMessage,
            AgentEventDraft waitingEvent);

    Optional<AgentContinuationTransition> claimWaitingInput(
            String runId,
            long expectedVersion,
            AgentRequest followUpRequest,
            AgentMessageDraft followUpMessage,
            AgentEventDraft inputEvent);

    Optional<AgentContinuationTransition> completeWaitingInput(
            String runId,
            AgentEventDraft completedEvent);
}
