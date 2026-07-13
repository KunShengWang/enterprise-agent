package com.agent.platform.runtime;

import java.util.List;

public record AgentContextView(
        List<AgentMessage> messages,
        long estimatedTokens,
        int omittedMessages,
        boolean compacted
) {

    public AgentContextView {
        messages = messages == null ? List.of() : List.copyOf(messages);
        estimatedTokens = Math.max(0, estimatedTokens);
        omittedMessages = Math.max(0, omittedMessages);
    }
}
