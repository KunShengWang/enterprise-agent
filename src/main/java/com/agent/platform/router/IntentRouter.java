package com.agent.platform.router;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;

public interface IntentRouter {

    /**
     * 根据用户问题进行路由判断，选择对应的意图类型
     */
    IntentRoute route(AgentRequest request, ConversationMemory memory);
}
