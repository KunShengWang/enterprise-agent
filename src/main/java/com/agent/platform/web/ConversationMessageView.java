package com.agent.platform.web;

import java.time.Instant;

/**
 * 面向聊天窗口的安全消息投影，不暴露 system prompt、工具参数和工具原始结果。
 */
public record ConversationMessageView(
        String messageId,
        String runId,
        long sequence,
        String role,
        String content,
        Instant createdAt
) {
}
