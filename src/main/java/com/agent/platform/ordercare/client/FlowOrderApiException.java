package com.agent.platform.ordercare.client;

public class FlowOrderApiException extends RuntimeException {

    private final int statusCode;
    private final boolean retryable;
    private final boolean outcomeUnknown;

    public FlowOrderApiException(String message, int statusCode, boolean retryable) {
        this(message, statusCode, retryable, false, null);
    }

    public FlowOrderApiException(String message, int statusCode, boolean retryable, Throwable cause) {
        this(message, statusCode, retryable, false, cause);
    }

    public FlowOrderApiException(String message,
                                 int statusCode,
                                 boolean retryable,
                                 boolean outcomeUnknown,
                                 Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.outcomeUnknown = outcomeUnknown;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
