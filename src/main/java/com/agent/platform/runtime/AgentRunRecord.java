package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;

import java.time.Instant;
import java.util.List;

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
        ToolCallRequest pendingToolCall,
        List<ToolCallResult> toolResults,
        List<String> usedTools,
        boolean usedRag,
        boolean blockedByGuardrail,
        String answer,
        String failureReason,
        int resumeCount,
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
                0,
                now,
                now
        );
    }

    public AgentRunRecord withRequest(AgentRequest nextRequest) {
        return new AgentRunRecord(
                runId, traceId, conversationId,
                nextRequest == null ? userId : nextRequest.userId(),
                nextRequest == null ? request : nextRequest,
                executionProfile, budgetSnapshot,
                state, phase, approvalId, pendingToolCall, toolResults, usedTools,
                usedRag, blockedByGuardrail, answer, failureReason, resumeCount,
                version, createdAt, Instant.now()
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
                blockedByGuardrail, answer, failureReason, resumeCount, version, createdAt, Instant.now()
        );
    }

    public AgentRunRecord withVersion(long nextVersion, Instant timestamp) {
        return new AgentRunRecord(
                runId, traceId, conversationId, userId, request, executionProfile, budgetSnapshot, state, phase,
                approvalId, pendingToolCall, toolResults, usedTools, usedRag,
                blockedByGuardrail, answer, failureReason, resumeCount,
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
                nextResumeCount, version, createdAt, Instant.now()
        );
    }
}
