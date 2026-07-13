package com.agent.platform.runtime;

import com.agent.platform.llm.LlmUsage;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime 强制执行的轮次、调用、Token、成本、截止时间和取消预算。
 */
public final class AgentRunBudget {

    private final AgentRunLimits limits;
    private final Instant startedAt;
    private final Instant deadline;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private int turns;
    private int modelCalls;
    private int toolCalls;
    private long inputTokens;
    private long outputTokens;
    private double estimatedCost;

    public AgentRunBudget(AgentRunLimits limits) {
        if (limits == null) {
            throw new IllegalArgumentException("run limits must not be null");
        }
        this.limits = limits;
        this.startedAt = Instant.now();
        this.deadline = startedAt.plusMillis(limits.maxRunDurationMillis());
    }

    public synchronized Optional<AgentStopReason> beforeTurn() {
        Optional<AgentStopReason> common = commonStopReason();
        if (common.isPresent()) {
            return common;
        }
        return turns >= limits.maxTurns()
                ? Optional.of(AgentStopReason.MAX_TURNS)
                : Optional.empty();
    }

    public synchronized void recordTurnStarted() {
        turns++;
    }

    public synchronized Optional<AgentStopReason> beforeModelCall() {
        Optional<AgentStopReason> common = commonStopReason();
        if (common.isPresent()) {
            return common;
        }
        if (modelCalls >= limits.maxModelCalls()
                || inputTokens >= limits.maxInputTokens()
                || outputTokens >= limits.maxOutputTokens()
                || (limits.maxEstimatedCost() > 0 && estimatedCost >= limits.maxEstimatedCost())) {
            return Optional.of(AgentStopReason.MODEL_BUDGET_EXHAUSTED);
        }
        return Optional.empty();
    }

    public synchronized void recordModelCall(LlmUsage usage, double cost) {
        modelCalls++;
        if (usage != null) {
            inputTokens += Math.max(0, usage.promptTokens());
            outputTokens += Math.max(0, usage.completionTokens());
        }
        estimatedCost += Math.max(0, cost);
    }

    public synchronized Optional<AgentStopReason> beforeToolCall() {
        Optional<AgentStopReason> common = commonStopReason();
        if (common.isPresent()) {
            return common;
        }
        return toolCalls >= limits.maxToolCalls()
                ? Optional.of(AgentStopReason.TOOL_BUDGET_EXHAUSTED)
                : Optional.empty();
    }

    public synchronized void recordToolCall() {
        toolCalls++;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public synchronized AgentRunBudgetSnapshot snapshot() {
        return new AgentRunBudgetSnapshot(
                turns,
                modelCalls,
                toolCalls,
                inputTokens,
                outputTokens,
                estimatedCost,
                startedAt,
                deadline,
                cancelled.get()
        );
    }

    private Optional<AgentStopReason> commonStopReason() {
        if (cancelled.get()) {
            return Optional.of(AgentStopReason.CANCELLED);
        }
        if (!Instant.now().isBefore(deadline)) {
            return Optional.of(AgentStopReason.TIMEOUT);
        }
        return Optional.empty();
    }
}
