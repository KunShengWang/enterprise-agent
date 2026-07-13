package com.agent.platform.runtime;

import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.tool.ToolDefinition;

public interface AgentToolRuntime {

    AgentToolRuntimeResult execute(String runId,
                                   String sessionId,
                                   AgentToolCall toolCall,
                                   ToolDefinition definition);

    AgentToolRuntimeResult executeApproved(ApprovalRecord approval, ToolDefinition definition);
}
