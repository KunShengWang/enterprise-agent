package com.agent.platform.workbench.budget;

public record BudgetLimit(
        int modelCalls,
        long tokens,
        int toolCalls,
        long durationMillis,
        double estimatedCost
) {
    public BudgetLimit {
        if (modelCalls < 0 || tokens < 0 || toolCalls < 0 || durationMillis < 0 || estimatedCost < 0) {
            throw new IllegalArgumentException("budget dimensions must not be negative");
        }
    }

    public BudgetLimit plus(BudgetLimit other) {
        if (other == null) return this;
        return new BudgetLimit(modelCalls + other.modelCalls, tokens + other.tokens,
                toolCalls + other.toolCalls, durationMillis + other.durationMillis,
                estimatedCost + other.estimatedCost);
    }

    public BudgetLimit minus(BudgetLimit other) {
        if (other == null) return this;
        return new BudgetLimit(Math.max(0, modelCalls - other.modelCalls),
                Math.max(0, tokens - other.tokens), Math.max(0, toolCalls - other.toolCalls),
                Math.max(0, durationMillis - other.durationMillis),
                Math.max(0, estimatedCost - other.estimatedCost));
    }

    public boolean fitsWithin(BudgetLimit maximum) {
        return maximum != null && modelCalls <= maximum.modelCalls && tokens <= maximum.tokens
                && toolCalls <= maximum.toolCalls && durationMillis <= maximum.durationMillis
                && estimatedCost <= maximum.estimatedCost + 0.000001d;
    }

    public boolean zero() {
        return modelCalls == 0 && tokens == 0 && toolCalls == 0
                && durationMillis == 0 && estimatedCost == 0;
    }
}
