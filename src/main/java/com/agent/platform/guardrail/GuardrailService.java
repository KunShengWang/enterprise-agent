package com.agent.platform.guardrail;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;

public interface GuardrailService {

    GuardrailDecision checkInput(String userQuestion);

    GuardrailDecision checkToolCall(ToolDefinition toolDefinition, ToolCallRequest toolCallRequest);

    GuardrailDecision checkOutput(String answer);
}
