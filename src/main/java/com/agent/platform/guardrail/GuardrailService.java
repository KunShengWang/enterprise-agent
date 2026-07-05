package com.agent.platform.guardrail;

import com.agent.platform.tool.ToolCallRequest;

public interface GuardrailService {

    GuardrailDecision checkInput(String userQuestion);

    GuardrailDecision checkToolCall(ToolCallRequest toolCallRequest);

    GuardrailDecision checkOutput(String answer);
}
