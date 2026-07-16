package com.agent.platform.runtime;

import java.util.List;

public record AgentContextView(
        List<AgentMessage> messages,// 实际给 LLM 的消息列表
        long estimatedTokens,// 占了多少 token
        int omittedMessages,// 丢了多少条消息
        boolean compacted// 是否发生过压缩
) {

    public AgentContextView {
        messages = messages == null ? List.of() : List.copyOf(messages);
        estimatedTokens = Math.max(0, estimatedTokens);
        omittedMessages = Math.max(0, omittedMessages);
    }
}
