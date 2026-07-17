package com.agent.platform.runtime;

import com.agent.platform.tool.ToolCallResult;

/** 领域适配器可用权威下游事实修复 RUNNING 工具的崩溃窗口。 */
public interface UncertainToolExecutionResolver {

    boolean supports(ToolExecutionRecord execution);

    ToolCallResult resolve(ToolExecutionRecord execution);
}
