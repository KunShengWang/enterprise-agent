package com.agent.platform.query;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;

public interface QueryRewriteService {

    /**
     * 用户问题改写
     */
    String rewrite(AgentRequest request, ConversationMemory memory);
}
