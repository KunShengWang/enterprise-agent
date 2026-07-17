package com.agent.platform.runtime;

import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.tool.ToolDefinition;

public interface AgentToolRuntime {

    AgentToolRuntimeResult execute(String runId,
                                   String sessionId,
                                   String userId,
                                   java.util.Map<String, Object> attributes,
                                   AgentToolCall toolCall,
                                   ToolDefinition definition);

    AgentToolRuntimeResult executeApproved(ApprovalRecord approval,
                                           ToolDefinition definition,
                                           ToolPolicyContext context);

    /**
     * 进程在副作用完成与结果落库之间崩溃时，尝试使用领域权威事实修复工具结果。
     * 默认不处理，保持现有 Runtime 行为。
     */
    default ToolExecutionRecord reconcileUncertain(ToolExecutionRecord execution) {
        return execution;
    }
}
