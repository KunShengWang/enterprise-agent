package com.agent.platform.router;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;

public interface IntentRouter {

    IntentRoute route(AgentRequest request, ConversationMemory memory);
}
