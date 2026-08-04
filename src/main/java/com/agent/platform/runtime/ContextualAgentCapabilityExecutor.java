package com.agent.platform.runtime;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutionContext;

/** 显式声明能够接收 Runtime 可信工具上下文的能力执行器。 */
public interface ContextualAgentCapabilityExecutor extends AgentCapabilityExecutor {

    ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context);
}
