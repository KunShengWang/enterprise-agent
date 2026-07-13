package com.agent.platform.guardrail;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;

public interface ToolPermissionPolicy {

    /**
     * 工具风险检查
     */
    GuardrailDecision check(ToolDefinition toolDefinition, ToolCallRequest toolCallRequest);
}
