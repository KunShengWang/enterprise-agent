package com.agent.platform.runtime;

import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;

public record AgentToolRuntimeResult(
        AgentToolExecutionStatus status,
        ToolCallRequest request,
        ToolCallResult result,
        GuardrailAction policyAction,
        String policyReason,
        String approvalId,
        boolean reused
) {

    public AgentToolRuntimeResult {
        policyReason = policyReason == null ? "" : policyReason;
        approvalId = approvalId == null ? "" : approvalId;
    }

    public boolean terminalPause() {
        return status == AgentToolExecutionStatus.WAITING_APPROVAL
                || status == AgentToolExecutionStatus.MANUAL_REVIEW;
    }

    public com.agent.platform.tool.ToolExecutionOutcome outcome() {
        return switch (status) {
            case WAITING_APPROVAL -> com.agent.platform.tool.ToolExecutionOutcome.APPROVAL_REQUIRED;
            case DENIED -> com.agent.platform.tool.ToolExecutionOutcome.POLICY_DENIED;
            case MANUAL_REVIEW -> com.agent.platform.tool.ToolExecutionOutcome.FATAL_ERROR;
            default -> com.agent.platform.tool.ToolExecutionOutcome.from(result);
        };
    }
}
