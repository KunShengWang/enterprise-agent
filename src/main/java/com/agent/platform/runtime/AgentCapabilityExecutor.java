package com.agent.platform.runtime;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;

public interface AgentCapabilityExecutor {

    ToolCallResult execute(ToolCallRequest request);
}
