package com.agent.platform.runtime;

import java.util.List;
import java.util.Map;

public record AgentContextView(
        List<AgentMessage> messages,// 实际给 LLM 的消息列表
        long estimatedTokens,// 占了多少 token
        int omittedMessages,// 丢了多少条消息
        boolean compacted,// 是否发生过压缩
        Map<String, Object> metadata// 本次投影/压缩的观测元数据
) {

    public AgentContextView(List<AgentMessage> messages,
                            long estimatedTokens,
                            int omittedMessages,
                            boolean compacted) {
        this(messages, estimatedTokens, omittedMessages, compacted, Map.of());
    }

    public AgentContextView {
        messages = messages == null ? List.of() : List.copyOf(messages);
        estimatedTokens = Math.max(0, estimatedTokens);
        omittedMessages = Math.max(0, omittedMessages);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
