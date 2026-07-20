package com.agent.platform.workbench.budget;

public class BudgetExceededException extends RuntimeException {
    private final String code;

    public BudgetExceededException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
