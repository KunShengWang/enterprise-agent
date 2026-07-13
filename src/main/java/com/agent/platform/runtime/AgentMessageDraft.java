package com.agent.platform.runtime;

import java.util.Map;

/**
 * 尚未分配持久化标识和会话序号的消息草稿。
 */
public record AgentMessageDraft(
        AgentMessageType type,
        String content,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments,
        Map<String, Object> metadata,
        long estimatedTokens
) {

    public AgentMessageDraft {
        if (type == null) {
            throw new IllegalArgumentException("message type must not be null");
        }
        content = content == null ? "" : content;
        toolCallId = toolCallId == null ? "" : toolCallId.trim();
        toolName = toolName == null ? "" : toolName.trim();
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        estimatedTokens = Math.max(0, estimatedTokens);

        if ((type == AgentMessageType.ASSISTANT_TOOL_CALL || type == AgentMessageType.TOOL_RESULT)
                && toolCallId.isBlank()) {
            throw new IllegalArgumentException(type + " requires toolCallId");
        }
        if (type == AgentMessageType.ASSISTANT_TOOL_CALL && toolName.isBlank()) {
            throw new IllegalArgumentException("ASSISTANT_TOOL_CALL requires toolName");
        }
    }

    public static AgentMessageDraft system(String content, long estimatedTokens) {
        return new AgentMessageDraft(AgentMessageType.SYSTEM, content, "", "", Map.of(), Map.of(), estimatedTokens);
    }

    public static AgentMessageDraft user(String content, long estimatedTokens) {
        return new AgentMessageDraft(AgentMessageType.USER, content, "", "", Map.of(), Map.of(), estimatedTokens);
    }

    public static AgentMessageDraft assistant(String content, long estimatedTokens) {
        return new AgentMessageDraft(AgentMessageType.ASSISTANT_TEXT, content, "", "", Map.of(), Map.of(), estimatedTokens);
    }

    public static AgentMessageDraft toolCall(String toolCallId,
                                             String toolName,
                                             Map<String, Object> arguments,
                                             Map<String, Object> metadata,
                                             long estimatedTokens) {
        return new AgentMessageDraft(
                AgentMessageType.ASSISTANT_TOOL_CALL,
                "",
                toolCallId,
                toolName,
                arguments,
                metadata,
                estimatedTokens
        );
    }

    public static AgentMessageDraft toolResult(String toolCallId,
                                               String toolName,
                                               boolean success,
                                               String content,
                                               String error,
                                               Map<String, Object> metadata,
                                               long estimatedTokens) {
        java.util.LinkedHashMap<String, Object> resultMetadata = new java.util.LinkedHashMap<>(
                metadata == null ? Map.of() : metadata
        );
        resultMetadata.put("success", success);
        resultMetadata.put("error", error == null ? "" : error);
        return new AgentMessageDraft(
                AgentMessageType.TOOL_RESULT,
                content,
                toolCallId,
                toolName,
                Map.of(),
                resultMetadata,
                estimatedTokens
        );
    }

    public static AgentMessageDraft summary(String content,
                                            Map<String, Object> metadata,
                                            long estimatedTokens) {
        return new AgentMessageDraft(
                AgentMessageType.CONTEXT_SUMMARY,
                content,
                "",
                "",
                Map.of(),
                metadata,
                estimatedTokens
        );
    }
}
