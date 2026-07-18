package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;

import java.time.Instant;
import java.util.List;

/**
 * AgentRunRecord 记录的是：某一次 Agent Run 当前执行到哪里、已经消耗多少预算、调用过哪些工具、是否等待审批，以及最终执行结果。
 * 它可以理解为一次 Agent 执行的“状态快照 + 恢复检查点”。
 * 对应数据库 agent_run_state
 */
public record AgentRunRecord(
        String runId,
        String traceId,
        String conversationId,
        String userId,
        AgentRequest request,
        AgentExecutionProfile executionProfile,
        AgentRunBudgetSnapshot budgetSnapshot,
        AgentRunState state,
        AgentRunPhase phase,
        String approvalId,
        ToolCallRequest pendingToolCall,// 当前准备执行的工具调用
        List<ToolCallResult> toolResults,
        List<String> usedTools,
        boolean usedRag,
        boolean blockedByGuardrail,
        String answer,
        String failureReason,
        int resumeCount,// 这个 Run 被恢复过多少次
        boolean inputCheckpointEnabled,
        int followUpCount,
        int maxFollowUps,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public AgentRunRecord {
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        usedTools = usedTools == null ? List.of() : List.copyOf(usedTools);
        state = state == null ? AgentRunState.CREATED : state;
        phase = phase == null ? AgentRunPhase.START : phase;
        approvalId = approvalId == null ? "" : approvalId;
        answer = answer == null ? "" : answer;
        failureReason = failureReason == null ? "" : failureReason;
        followUpCount = Math.max(0, followUpCount);
        maxFollowUps = Math.max(0, maxFollowUps);
        if (followUpCount > maxFollowUps) {
            throw new IllegalArgumentException("followUpCount must not exceed maxFollowUps");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static AgentRunRecord create(String runId,
                                        String traceId,
                                        String conversationId,
                                        AgentRequest request) {
        return create(runId, traceId, conversationId, request, null, null);
    }

    public static AgentRunRecord create(String runId,
                                        String traceId,
                                        String conversationId,
                                        AgentRequest request,
                                        AgentExecutionProfile executionProfile,
                                        AgentRunBudgetSnapshot budgetSnapshot) {
        Instant now = Instant.now();
        return new AgentRunRecord(
                runId, traceId, conversationId,
                request == null ? "" : request.userId(),
                request,
                executionProfile,
                budgetSnapshot,
                AgentRunState.RUNNING,
                AgentRunPhase.START,
                "",
                null,
                List.of(),
                List.of(),
                false,
                false,
                "",
                "",
                0,
                false,
                0,
                0,
                0,
                now,
                now
        );
    }

    /**
     * 根据 AgentRequest 更新 AgentRunRecord
     */
    public AgentRunRecord withRequest(AgentRequest nextRequest) {
        return new AgentRunRecord(
                runId, traceId, conversationId,
                nextRequest == null ? userId : nextRequest.userId(),
                nextRequest == null ? request : nextRequest,
                executionProfile, budgetSnapshot,
                state, phase, approvalId, pendingToolCall, toolResults, usedTools,
                usedRag, blockedByGuardrail, answer, failureReason, resumeCount,
                inputCheckpointEnabled, followUpCount, maxFollowUps,
                version, createdAt, Instant.now()
        );
    }

    public AgentRunRecord enableInputCheckpoint(int allowedFollowUps) {
        if (state != AgentRunState.RUNNING || version != 0 || allowedFollowUps < 1) {
            throw new IllegalStateException("input checkpoint must be enabled when creating a running run");
        }
        return new AgentRunRecord(
                runId, traceId, conversationId, userId, request, executionProfile, budgetSnapshot,
                state, phase, approvalId, pendingToolCall, toolResults, usedTools, usedRag,
                blockedByGuardrail, answer, failureReason, resumeCount,
                true, 0, allowedFollowUps, version, createdAt, Instant.now()
        );
    }

    public AgentRunRecord waitingForInput(String checkpointAnswer,
                                          List<ToolCallResult> completedToolResults,
                                          List<String> completedTools,
                                          boolean ragUsed,
                                          AgentRunBudgetSnapshot currentBudget) {
        if (!inputCheckpointEnabled || followUpCount >= maxFollowUps) {
            throw new IllegalStateException("run is not eligible for an input checkpoint");
        }
        return copy(
                AgentRunState.WAITING_INPUT,
                AgentRunPhase.WAITING_INPUT,
                "",
                null,
                completedToolResults,
                completedTools,
                ragUsed,
                blockedByGuardrail,
                checkpointAnswer,
                "",
                resumeCount,
                currentBudget
        );
    }

    public AgentRunRecord claimedForInput(AgentRequest followUpRequest) {
        if (state != AgentRunState.WAITING_INPUT || followUpCount >= maxFollowUps) {
            throw new IllegalStateException("run is not waiting for permitted follow-up input");
        }
        AgentRunRecord claimed = copy(
                AgentRunState.RUNNING,
                AgentRunPhase.CONTEXT_PREPARATION,
                "",
                null,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                "",
                "",
                resumeCount + 1,
                budgetSnapshot
        );
        return new AgentRunRecord(
                claimed.runId, claimed.traceId, claimed.conversationId,
                followUpRequest == null ? claimed.userId : followUpRequest.userId(),
                followUpRequest == null ? claimed.request : followUpRequest,
                claimed.executionProfile, claimed.budgetSnapshot, claimed.state, claimed.phase,
                claimed.approvalId, claimed.pendingToolCall, claimed.toolResults, claimed.usedTools,
                claimed.usedRag, claimed.blockedByGuardrail, claimed.answer, claimed.failureReason,
                claimed.resumeCount, claimed.inputCheckpointEnabled, followUpCount + 1,
                claimed.maxFollowUps, claimed.version, claimed.createdAt, Instant.now()
        );
    }

    public AgentRunRecord waitingForApproval(String pendingApprovalId,
                                             ToolCallRequest toolCall,
                                             List<ToolCallResult> completedToolResults,
                                             List<String> completedTools,
                                             boolean ragUsed,
                                             AgentRunBudgetSnapshot currentBudget) {
        return copy(
                AgentRunState.WAITING_APPROVAL,
                AgentRunPhase.WAITING_APPROVAL,
                pendingApprovalId,
                toolCall,
                completedToolResults,
                completedTools,
                ragUsed,
                blockedByGuardrail,
                "等待人工审批",
                "",
                resumeCount,
                currentBudget
        );
    }

    public AgentRunRecord claimedForResume() {
        return copy(
                AgentRunState.RUNNING,
                AgentRunPhase.EXECUTING_TOOL,
                approvalId,
                pendingToolCall,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                "",
                "",
                resumeCount + 1,
                budgetSnapshot
        );
    }

    public AgentRunRecord claimedForRecovery() {
        return copy(
                AgentRunState.RUNNING,
                phase,
                approvalId,
                pendingToolCall,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                "",
                "",
                resumeCount + 1,
                budgetSnapshot
        );
    }

    public AgentRunRecord pauseRequested(AgentRunBudgetSnapshot currentBudget) {
        return copy(
                AgentRunState.PAUSE_REQUESTED,
                phase,
                approvalId,
                pendingToolCall,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                "",
                "",
                resumeCount,
                currentBudget == null ? budgetSnapshot : currentBudget
        );
    }

    public AgentRunRecord paused(AgentRunBudgetSnapshot pausedBudget) {
        return copy(
                AgentRunState.PAUSED,
                phase,
                approvalId,
                pendingToolCall,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                "",
                "",
                resumeCount,
                pausedBudget
        );
    }

    public AgentRunRecord claimedPausedForResume() {
        return copy(
                AgentRunState.RUNNING,
                phase,
                approvalId,
                pendingToolCall,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                "",
                "",
                resumeCount + 1,
                budgetSnapshot
        );
    }

    public AgentRunRecord checkpoint(AgentRunPhase checkpointPhase,
                                     ToolCallRequest activeToolCall,
                                     List<ToolCallResult> completedToolResults,
                                     List<String> completedTools,
                                     boolean ragUsed,
                                     AgentRunBudgetSnapshot currentBudget) {
        AgentRunState checkpointState = state == AgentRunState.PAUSE_REQUESTED
                || state == AgentRunState.PAUSED
                ? state
                : AgentRunState.RUNNING;
        return copy(
                checkpointState,
                checkpointPhase,
                approvalId,
                activeToolCall,
                completedToolResults,
                completedTools,
                ragUsed,
                blockedByGuardrail,
                "",
                "",
                resumeCount,
                currentBudget
        );
    }

    public AgentRunRecord finished(AgentRunState targetState,
                                   AgentRunPhase targetPhase,
                                   String finalAnswer,
                                   String error,
                                   List<ToolCallResult> finalToolResults,
                                   List<String> finalUsedTools,
                                   boolean ragUsed,
                                   boolean guardrailBlocked) {
        return finished(targetState, targetPhase, finalAnswer, error, finalToolResults,
                finalUsedTools, ragUsed, guardrailBlocked, budgetSnapshot);
    }

    public AgentRunRecord finished(AgentRunState targetState,
                                   AgentRunPhase targetPhase,
                                   String finalAnswer,
                                   String error,
                                   List<ToolCallResult> finalToolResults,
                                   List<String> finalUsedTools,
                                   boolean ragUsed,
                                   boolean guardrailBlocked,
                                   AgentRunBudgetSnapshot finalBudget) {
        return copy(
                targetState,
                targetPhase,
                approvalId,
                pendingToolCall,
                finalToolResults,
                finalUsedTools,
                ragUsed,
                guardrailBlocked,
                finalAnswer,
                error,
                resumeCount,
                finalBudget
        );
    }

    public AgentRunRecord withBudgetSnapshot(AgentRunBudgetSnapshot currentBudget) {
        return new AgentRunRecord(
                runId, traceId, conversationId, userId, request, executionProfile, currentBudget,
                state, phase, approvalId, pendingToolCall, toolResults, usedTools, usedRag,
                blockedByGuardrail, answer, failureReason, resumeCount,
                inputCheckpointEnabled, followUpCount, maxFollowUps,
                version, createdAt, Instant.now()
        );
    }

    public AgentRunRecord withVersion(long nextVersion, Instant timestamp) {
        return new AgentRunRecord(
                runId, traceId, conversationId, userId, request, executionProfile, budgetSnapshot, state, phase,
                approvalId, pendingToolCall, toolResults, usedTools, usedRag,
                blockedByGuardrail, answer, failureReason, resumeCount,
                inputCheckpointEnabled, followUpCount, maxFollowUps,
                nextVersion, createdAt, timestamp == null ? Instant.now() : timestamp
        );
    }

    private AgentRunRecord copy(AgentRunState nextState,
                                AgentRunPhase nextPhase,
                                String nextApprovalId,
                                ToolCallRequest nextPendingToolCall,
                                List<ToolCallResult> nextToolResults,
                                List<String> nextUsedTools,
                                boolean nextUsedRag,
                                boolean nextBlockedByGuardrail,
                                String nextAnswer,
                                String nextFailureReason,
                                int nextResumeCount,
                                AgentRunBudgetSnapshot nextBudgetSnapshot) {
        return new AgentRunRecord(
                runId, traceId, conversationId, userId, request, executionProfile, nextBudgetSnapshot, nextState, nextPhase,
                nextApprovalId, nextPendingToolCall, nextToolResults, nextUsedTools,
                nextUsedRag, nextBlockedByGuardrail, nextAnswer, nextFailureReason,
                nextResumeCount, inputCheckpointEnabled, followUpCount, maxFollowUps,
                version, createdAt, Instant.now()
        );
    }
}
