package com.agent.platform.tool;

public record ToolValidationResult(
        boolean valid,
        String message
) {

    public static ToolValidationResult ok() {
        return new ToolValidationResult(true, "tool arguments are valid");
    }

    public static ToolValidationResult invalid(String message) {
        return new ToolValidationResult(false, message);
    }
}
