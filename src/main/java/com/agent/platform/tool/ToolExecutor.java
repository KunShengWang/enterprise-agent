package com.agent.platform.tool;

public interface ToolExecutor {

    ToolCallResult execute(ToolCallRequest request);

    default ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context) {
        return execute(request);
    }
}
