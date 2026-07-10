package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.workflow.WorkflowExecutionPlan;
import com.agent.platform.workflow.WorkflowNode;

import java.time.Instant;
import java.util.List;

public record AgentRunRecord(
        String runId,
        String traceId,
        String conversationId,
        String userId,
        AgentRequest request,
        AgentRunState state,
        WorkflowExecutionPlan plan,
        WorkflowNode currentNode,
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
        currentNode = currentNode == null ? WorkflowNode.START : currentNode;
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
        Instant now = Instant.now();
        return new AgentRunRecord(
                runId,
                traceId,
                conversationId,
                request == null ? "" : request.userId(),
                request,
                AgentRunState.RUNNING,
                null,
                WorkflowNode.START,
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

    public AgentRunRecord withPlan(WorkflowExecutionPlan executionPlan) {
        return copy(
                AgentRunState.RUNNING,
                executionPlan,
                WorkflowNode.START,
                approvalId,
                pendingToolCall,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                answer,
                failureReason,
                resumeCount
        );
    }

    public AgentRunRecord withRequest(AgentRequest nextRequest) {
        return new AgentRunRecord(
                runId,
                traceId,
                conversationId,
                nextRequest == null ? userId : nextRequest.userId(),
                nextRequest == null ? request : nextRequest,
                state,
                plan,
                currentNode,
                approvalId,
                pendingToolCall,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                answer,
                failureReason,
                resumeCount,
                version,
                createdAt,
                Instant.now()
        );
    }

    public AgentRunRecord waitingForApproval(String pendingApprovalId,
                                             ToolCallRequest toolCall,
                                             List<ToolCallResult> completedToolResults,
                                             List<String> completedTools,
                                             boolean ragUsed) {
        return copy(
                AgentRunState.WAITING_APPROVAL,
                plan,
                WorkflowNode.TOOL_APPROVAL,
                pendingApprovalId,
                toolCall,
                completedToolResults,
                completedTools,
                ragUsed,
                blockedByGuardrail,
                "等待人工审批",
                "",
                resumeCount
        );
    }

    public AgentRunRecord claimedForResume() {
        return copy(
                AgentRunState.RUNNING,
                plan,
                WorkflowNode.TOOL_EXECUTE,
                approvalId,
                pendingToolCall,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                "",
                "",
                resumeCount + 1
        );
    }

    public AgentRunRecord finished(AgentRunState targetState,
                                   WorkflowNode node,
                                   String finalAnswer,
                                   String error,
                                   List<ToolCallResult> finalToolResults,
                                   List<String> finalUsedTools,
                                   boolean ragUsed,
                                   boolean guardrailBlocked) {
        return copy(
                targetState,
                plan,
                node,
                approvalId,
                pendingToolCall,
                finalToolResults,
                finalUsedTools,
                ragUsed,
                guardrailBlocked,
                finalAnswer,
                error,
                resumeCount
        );
    }

    public AgentRunRecord withVersion(long nextVersion, Instant timestamp) {
        return new AgentRunRecord(
                runId,
                traceId,
                conversationId,
                userId,
                request,
                state,
                plan,
                currentNode,
                approvalId,
                pendingToolCall,
                toolResults,
                usedTools,
                usedRag,
                blockedByGuardrail,
                answer,
                failureReason,
                resumeCount,
                nextVersion,
                createdAt,
                timestamp == null ? Instant.now() : timestamp
        );
    }

    private AgentRunRecord copy(AgentRunState nextState,
                                WorkflowExecutionPlan executionPlan,
                                WorkflowNode node,
                                String nextApprovalId,
                                ToolCallRequest nextPendingToolCall,
                                List<ToolCallResult> nextToolResults,
                                List<String> nextUsedTools,
                                boolean nextUsedRag,
                                boolean nextBlockedByGuardrail,
                                String nextAnswer,
                                String nextFailureReason,
                                int nextResumeCount) {
        return new AgentRunRecord(
                runId,
                traceId,
                conversationId,
                userId,
                request,
                nextState,
                executionPlan,
                node,
                nextApprovalId,
                nextPendingToolCall,
                nextToolResults,
                nextUsedTools,
                nextUsedRag,
                nextBlockedByGuardrail,
                nextAnswer,
                nextFailureReason,
                nextResumeCount,
                version,
                createdAt,
                Instant.now()
        );
    }
}
