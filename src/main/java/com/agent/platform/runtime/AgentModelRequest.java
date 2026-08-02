package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

public record AgentModelRequest(
        String runId,
        String sessionId,
        String systemPrompt,
        List<AgentMessage> messages,// USER、ASSISTANT_TEXT、TOOL_RESULT、ASSISTANT_TOOL_CALL 四种消息
        List<ToolDefinition> tools,
        Map<String, Object> metadata
) {

    public AgentModelRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
