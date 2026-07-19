package com.agent.platform.workbench.persistence;

public class WorkbenchIdempotencyConflictException extends RuntimeException {
    public WorkbenchIdempotencyConflictException(String message) {
        super(message);
    }
}
