package com.agent.platform.tool;

/**
 * 业务工具执行适配器；通用执行器仍统一负责 Schema 校验、记录和异常边界。
 */
public interface ToolHandler {

    boolean supports(String toolName);

    ToolCallResult execute(ToolCallRequest request);
}
