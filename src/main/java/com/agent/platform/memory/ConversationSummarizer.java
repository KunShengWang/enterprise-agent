package com.agent.platform.memory;

import java.util.List;

public interface ConversationSummarizer {

    /**
     * 压缩消息
     */
    String summarize(String previousSummary, List<MemoryMessage> messages, int maxChars);
}
