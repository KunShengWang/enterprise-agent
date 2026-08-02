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
    private Instant deadline;
    private long remainingExecutionMillis;// 剩余执行时间（毫秒）
    private boolean executionPaused;// 执行暂停
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);// 暂停请求

    private int turns;
    private int modelCalls;
    private int toolCalls;
    private long inputTokens;
    private long outputTokens;
    private double estimatedCost;

    public AgentRunBudget(AgentRunLimits limits) {
        this(limits, null);
    }

    public AgentRunBudget(AgentRunLimits limits, AgentRunBudgetSnapshot snapshot) {
        if (limits == null) {
            throw new IllegalArgumentException("run limits must not be null");
        }
        this.limits = limits;
        Instant now = Instant.now();
        this.startedAt = snapshot == null || snapshot.startedAt() == null ? now : snapshot.startedAt();
        if (snapshot == null) {
            this.remainingExecutionMillis = limits.maxRunDurationMillis();
            this.deadline = now.plusMillis(remainingExecutionMillis);
        }
        else if (snapshot.executionPaused()) {
            this.remainingExecutionMillis = restoredPausedRemaining(snapshot);
            this.deadline = now.plusMillis(remainingExecutionMillis);
            this.executionPaused = true;
        }
        else {
            this.deadline = snapshot.deadline() == null
                    ? now.plusMillis(limits.maxRunDurationMillis())
                    : snapshot.deadline();
            this.remainingExecutionMillis = remainingUntil(this.deadline, now);
        }
        if (snapshot != null) {
            this.turns = Math.max(0, snapshot.turns());
            this.modelCalls = Math.max(0, snapshot.modelCalls());
            this.toolCalls = Math.max(0, snapshot.toolCalls());
            this.inputTokens = Math.max(0, snapshot.inputTokens());
            this.outputTokens = Math.max(0, snapshot.outputTokens());
            this.estimatedCost = Math.max(0, snapshot.estimatedCost());
            this.cancelled.set(snapshot.cancelled());
        }
    }

    /**
     * 在 agent 的执行轮次之前判断是否取消 agent
     */
    public synchronized Optional<AgentStopReason> beforeTurn() {
        // 判断是否应该设置取消原因
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

    /**
     * 在模型调用之前判断是否取消 agent
     */
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

    public void requestPause() {
        pauseRequested.set(true);
    }

    public void clearPauseRequest() {
        pauseRequested.set(false);
    }

    public boolean pauseRequested() {
        return pauseRequested.get();
    }

    public synchronized void pauseExecution() {
        if (!executionPaused) {
            // 冻结剩余时间
            remainingExecutionMillis = remainingUntil(deadline, Instant.now());
            executionPaused = true;
        }
    }

    public synchronized void resumeExecution() {
        if (executionPaused) {
            // 当前 agent 运行的截止时间 = 当前时间 + 剩余的执行时间
            deadline = Instant.now().plusMillis(remainingExecutionMillis);
            executionPaused = false;
        }
    }

    public synchronized Optional<AgentStopReason> currentStopReason() {
        return commonStopReason();
    }

    /**
     * 记录当前的快照
     */
    public synchronized AgentRunBudgetSnapshot snapshot() {
        // 打快照时算"还剩多少毫秒"——暂停了就取冻结值，没暂停就实时算
        long remaining = executionPaused
                ? remainingExecutionMillis   // 暂停中：用冻结时的剩余值
                : remainingUntil(deadline, Instant.now());  // 运行中：用 deadline - now 实时算
        return new AgentRunBudgetSnapshot(
                turns,
                modelCalls,
                toolCalls,
                inputTokens,
                outputTokens,
                estimatedCost,
                startedAt,
                deadline,
                cancelled.get(),
                remaining,
                executionPaused
        );
    }

    /**
     * 判断是否应该设置取消原因
     */
    private Optional<AgentStopReason> commonStopReason() {
        if (cancelled.get()) {
            return Optional.of(AgentStopReason.CANCELLED);
        }
        if (!executionPaused && !Instant.now().isBefore(deadline)) {
            return Optional.of(AgentStopReason.TIMEOUT);
        }
        return Optional.empty();
    }

    private long restoredPausedRemaining(AgentRunBudgetSnapshot snapshot) {
        return Math.min(
                Math.max(0, snapshot.remainingExecutionMillis()),
                limits.maxRunDurationMillis()
        );
    }

    private long remainingUntil(Instant targetDeadline, Instant now) {
        return Math.max(0, targetDeadline.toEpochMilli() - now.toEpochMilli());
    }
}
