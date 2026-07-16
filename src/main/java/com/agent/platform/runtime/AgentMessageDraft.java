package com.agent.platform.runtime;

import java.util.Map;

/**
 * 尚未分配持久化标识和会话序号的消息草稿。
 * AgentMessageDraft 是“准备写入会话时间线、但还没有持久化身份的消息草稿”。
 * AgentMessageDraft（待保存）;AgentMessage（已保存）
 * AgentMessageDraft：只有消息内容、类型、工具调用信息、预估 Token 等业务数据。
 * AgentMessage：在草稿基础上，由存储层补充：messageId、sessionId、runId、会话内递增的 sequence、createdAt
 */
public record AgentMessageDraft(
        AgentMessageType type,// 消息的类型
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
