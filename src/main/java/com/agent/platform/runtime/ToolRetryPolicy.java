package com.agent.platform.runtime;

import com.agent.platform.tool.*;

/** One replay decision for the existing tool execution loop. Never model-owned. */
final class ToolRetryPolicy {
    record Decision(boolean retry, String reason, long backoffMillis) {}

    static Decision decide(ToolCallResult result, ToolDefinition definition, int attempt,
                           int maxAttempts, long backoffMillis) {
        if (result.outcome() != ToolExecutionOutcome.RETRYABLE_ERROR)
            return new Decision(false, "OUTCOME_NOT_RETRYABLE", 0);
        var contract = definition.runtimeContract();
        boolean safe = contract.effect() == ToolRuntimeContract.Effect.READ_ONLY
                || contract.effect() == ToolRuntimeContract.Effect.INTERNAL_WRITE
                && "safe".equals(contract.retryMetadata());
        if (!safe) return new Decision(false, "EFFECT_NOT_REPLAY_SAFE", 0);
        if (Boolean.FALSE.equals(result.metadata().get("retryable")))
            return new Decision(false, "EXECUTOR_RETRY_VETO", 0);
        if (attempt >= maxAttempts) return new Decision(false, "RETRY_BUDGET_EXHAUSTED", 0);
        if (Thread.currentThread().isInterrupted()) return new Decision(false, "INTERRUPTED", 0);
        long delay = Math.max(0, backoffMillis) * attempt;
        if (definition.metadata().get("_harnessDeadlineEpochMillis") instanceof Number deadline
                && deadline.longValue() - System.currentTimeMillis() <= delay)
            return new Decision(false, "RUN_DEADLINE_EXHAUSTED", 0);
        return new Decision(true, "TRANSIENT_REPLAY_SAFE_WITHIN_BUDGET", delay);
    }
}
