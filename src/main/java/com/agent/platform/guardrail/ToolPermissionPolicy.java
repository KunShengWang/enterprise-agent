package com.agent.platform.guardrail;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;

public interface ToolPermissionPolicy {

    GuardrailDecision check(ToolDefinition toolDefinition, ToolCallRequest toolCallRequest);
}
