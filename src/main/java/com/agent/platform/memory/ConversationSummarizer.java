package com.agent.platform.memory;

import java.util.List;

public interface ConversationSummarizer {

    String summarize(String previousSummary, List<MemoryMessage> messages, int maxChars);
}
