package com.agent.platform.workbench.persistence;

public class WorkbenchAccessDeniedException extends RuntimeException {
    public WorkbenchAccessDeniedException(String message) {
        super(message);
    }
}
