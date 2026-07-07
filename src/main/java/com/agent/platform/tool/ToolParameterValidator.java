package com.agent.platform.tool;

public interface ToolParameterValidator {

    ToolValidationResult validate(ToolDefinition definition, ToolCallRequest request);
}
