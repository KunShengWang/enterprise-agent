package com.agent.platform.guardrail;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;

public interface GuardrailService {

    /**
     * 输入 Guardrail
     */
    GuardrailDecision checkInput(String userQuestion);

    /**
     * 工具执行前单独检查权限和风险级别；高风险副作用不能直接执行
     */
    GuardrailDecision checkToolCall(ToolDefinition toolDefinition, ToolCallRequest toolCallRequest);

    default GuardrailDecision checkToolCall(ToolDefinition toolDefinition,
                                            ToolCallRequest toolCallRequest,
                                            ToolPolicyContext context) {
        return checkToolCall(toolDefinition, toolCallRequest);
    }

    GuardrailDecision checkOutput(String answer);
}
