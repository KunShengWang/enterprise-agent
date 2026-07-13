package com.agent.platform.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TraceContext {

    private final String traceId;           // 全局唯一 ID
    private final String conversationId;    // 会话 ID（同会话多轮对话共享）
    private final String question;          // 用户原始问题
    private final Instant startedAt;        // 开始时间戳
    private final List<TraceEvent> events = new ArrayList<>();  // 阶段事件（时间线）
    private final List<TraceSpan> spans = new ArrayList<>();    // 分段计时（类似 OpenTelemetry span）
    private final List<TraceReplayEvent> replayEvents = new ArrayList<>();  // 可回放事件
    private final Map<String, Object> metrics = new LinkedHashMap<>();          // 自定义指标
    private String status = "RUNNING";                  // 状态机
    private String failureReason = "";
    private long estimatedPromptTokens;                 // token 累计
    private long estimatedCompletionTokens;
    private double estimatedCost;                       // 成本累计

    public TraceContext(String traceId, String conversationId, String question) {
        this.traceId = traceId;
        this.conversationId = conversationId;
        this.question = question;
        this.startedAt = Instant.now();
    }

    private TraceContext(TraceRun run) {
        this.traceId = run.traceId();
        this.conversationId = run.conversationId();
        this.question = run.question();
        this.startedAt = run.startedAt();
        this.spans.addAll(run.spans());
        this.events.addAll(run.events());
        this.replayEvents.addAll(run.replayEvents());
        this.metrics.putAll(run.metrics());
        this.status = run.status();
        this.failureReason = run.failureReason();
        this.estimatedPromptTokens = run.estimatedPromptTokens();
        this.estimatedCompletionTokens = run.estimatedCompletionTokens();
        this.estimatedCost = run.estimatedCost();
    }

    public static TraceContext resume(TraceRun run) {
        if (run == null) {
            throw new IllegalArgumentException("trace run must not be null");
        }
        TraceContext context = new TraceContext(run);
        context.addEvent("trace.resume", "agent run resumed");
        context.addReplayEvent("run.resumed", "Agent run resumed", Map.of(
                "traceId", run.traceId(),
                "previousStatus", run.status()
        ));
        context.markStatus("RUNNING", "");
        return context;
    }

    public String traceId() {
        return traceId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String question() {
        return question;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void addEvent(String stage, String detail) {
        events.add(new TraceEvent(Instant.now(), stage, detail));
    }

    public void addSpan(TraceSpan span) {
        spans.add(span);
    }

    public void addReplayEvent(String eventType, String summary, Map<String, Object> payload) {
        replayEvents.add(new TraceReplayEvent(replayEvents.size() + 1, Instant.now(), eventType, summary, payload));
    }

    public void putMetric(String key, Object value) {
        metrics.put(key, value);
    }

    public void addTokenUsage(long promptTokens, long completionTokens, double cost) {
        estimatedPromptTokens += Math.max(0, promptTokens);
        estimatedCompletionTokens += Math.max(0, completionTokens);
        estimatedCost += Math.max(0, cost);
    }

    public void markStatus(String status, String failureReason) {
        this.status = status == null || status.isBlank() ? this.status : status;
        this.failureReason = failureReason == null ? "" : failureReason;
    }

    public TraceSummary summary() {
        return new TraceSummary(traceId, conversationId, events);
    }

    public TraceRun runSnapshot(Instant endedAt) {
        Instant effectiveEndedAt = endedAt == null ? Instant.now() : endedAt;
        return new TraceRun(
                traceId,
                conversationId,
                question,
                status,
                startedAt,
                effectiveEndedAt,
                Math.max(0, effectiveEndedAt.toEpochMilli() - startedAt.toEpochMilli()),
                failureReason,
                estimatedPromptTokens,
                estimatedCompletionTokens,
                estimatedCost,
                spans,
                events,
                replayEvents,
                metrics
        );
    }
}
