package com.agent.platform.llm;

public class LlmCallException extends RuntimeException {

    private final String errorType;

    private final String safeMessage;

    public LlmCallException(String errorType, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorType = errorType;
        this.safeMessage = safeMessage;
    }

    public String errorType() {
        return errorType;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
