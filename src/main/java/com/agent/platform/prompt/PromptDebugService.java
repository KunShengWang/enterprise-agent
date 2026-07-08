package com.agent.platform.prompt;

import com.agent.platform.agent.AgentRequest;

public interface PromptDebugService {

    PromptDebugResponse debug(AgentRequest request);
}
