package com.agent.platform.tool;

/** 需要父 Agent 可信执行上下文的业务工具。 */
public interface ContextualToolHandler extends ToolHandler {

    ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context);

    @Override
    default ToolCallResult execute(ToolCallRequest request) {
        return execute(request, ToolExecutionContext.empty());
    }
}
