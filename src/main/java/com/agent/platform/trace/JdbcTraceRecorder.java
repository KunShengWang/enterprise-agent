package com.agent.platform.trace;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Primary
@Component
@ConditionalOnProperty(prefix = "enterprise-agent.storage", name = "mode", havingValue = "jdbc", matchIfMissing = true)
public class JdbcTraceRecorder implements TraceRecorder {

    private static final String CATEGORY = "trace_run";

    private final JdbcAgentStoreSupport store;
    private final RuntimeTraceProjector runtimeTraceProjector;

    public JdbcTraceRecorder(JdbcAgentStoreSupport store,
                             RuntimeTraceProjector runtimeTraceProjector) {
        this.store = store;
        this.runtimeTraceProjector = runtimeTraceProjector;
    }

    @Override
    public TraceContext start(String conversationId, String question) {
        // ① 创建 TraceContext，生成唯一 traceId
        TraceContext context = new TraceContext(UUID.randomUUID().toString(), conversationId, question);
        // ② 记录开始事件，普通 TraceEvent 用于让人快速查看 Agent 做过什么
        context.addEvent("trace.start", "question received");
        // ③ 记录可重放事件（用于执行回放），ReplayEvent 重新还原 Agent 的执行过程
        context.addReplayEvent("run.started", "Agent run started", Map.of(
                "traceId", context.traceId(),
                "conversationId", context.conversationId(),
                "question", question == null ? "" : question
        ));
        // ④ 开启根 span：agent.run
        recordSpan(context, "agent.run", TraceSpanKind.AGENT, TraceSpanStatus.STARTED, "agent run started",
                0, question, "", "", Map.of("conversationId", conversationId));
        return context;
    }

    @Override
    public TraceContext resume(String traceId) {
        TraceRun run = findRun(traceId)
                .orElseThrow(() -> new IllegalArgumentException("trace run not found: " + traceId));
        TraceContext context = TraceContext.resume(run);
        recordSpan(context, "agent.resume", TraceSpanKind.AGENT, TraceSpanStatus.STARTED,
                "agent run resumed", 0, traceId, "", "", Map.of("traceId", traceId));
        return context;
    }

    @Override
    public void record(TraceContext context, String stage, String detail) {
        if (context == null) {
            return;
        }
        context.addEvent(stage, detail);
        recordSpan(context, stage, inferKind(stage), inferStatus(detail), detail, 0, "", detail, "", Map.of());
    }

    @Override
    public void recordSpan(TraceContext context,
                           String name,
                           TraceSpanKind kind,
                           TraceSpanStatus status,
                           String summary,
                           long durationMs,
                           String input,
                           String output,
                           String error,
                           Map<String, Object> attributes) {
        if (context == null) {
            return;
        }
        Instant endedAt = Instant.now();
        Instant startedAt = endedAt.minusMillis(Math.max(0, durationMs));
        TraceSpan span = new TraceSpan(
                UUID.randomUUID().toString(),
                context.traceId(),
                null,
                blankToDefault(name, "unknown"),
                kind == null ? TraceSpanKind.SYSTEM : kind,
                status == null ? TraceSpanStatus.COMPLETED : status,
                summary == null ? "" : summary,
                startedAt,
                endedAt,
                Math.max(0, durationMs),
                input == null ? "" : input,
                output == null ? "" : output,
                error == null ? "" : error,
                attributes
        );
        context.addSpan(span);
        context.addReplayEvent("span." + span.status().name().toLowerCase(Locale.ROOT), span.name() + ": " + span.summary(), Map.of(
                "spanId", span.spanId(),
                "kind", span.kind().name(),
                "status", span.status().name(),
                "durationMs", span.durationMs()
        ));
    }

    @Override
    public void recordReplay(TraceContext context, String eventType, String summary, Map<String, Object> payload) {
        if (context != null) {
            context.addReplayEvent(blankToDefault(eventType, "event"), summary == null ? "" : summary, payload);
        }
    }

    @Override
    public void recordMetric(TraceContext context, String key, Object value) {
        if (context != null && key != null && !key.isBlank()) {
            context.putMetric(key, value);
        }
    }

    @Override
    public void recordTokenUsage(TraceContext context, long promptTokens, long completionTokens, double estimatedCost) {
        if (context != null) {
            context.addTokenUsage(promptTokens, completionTokens, estimatedCost);
            context.putMetric("estimatedPromptTokens", promptTokens);
            context.putMetric("estimatedCompletionTokens", completionTokens);
            context.putMetric("estimatedCost", estimatedCost);
        }
    }

    @Override
    public void markStatus(TraceContext context, String status, String failureReason) {
        if (context != null) {
            context.markStatus(status, failureReason);
        }
    }

    @Override
    public TraceSummary finish(TraceContext context) {
        if (context == null) {
            return new TraceSummary("", "", List.of());
        }
        context.addEvent("trace.finish", "agent run finished");
        context.addReplayEvent("run.finished", "Agent run finished", Map.of("traceId", context.traceId()));
        TraceRun run = context.runSnapshot(Instant.now());
        store.save(CATEGORY, run.traceId(), run, run.startedAt(), run.endedAt());
        return context.summary();
    }

    @Override
    public Optional<TraceRun> findRun(String traceId) {
        return runtimeTraceProjector.project(traceId)
                .or(() -> store.find(CATEGORY, traceId, TraceRun.class));
    }

    @Override
    public List<TraceRun> recentRuns(int limit) {
        int effectiveLimit = Math.max(1, limit);
        LinkedHashMap<String, TraceRun> byTraceId = new LinkedHashMap<>();
        runtimeTraceProjector.recent(effectiveLimit).forEach(run -> byTraceId.put(run.traceId(), run));
        store.recent(CATEGORY, TraceRun.class, effectiveLimit).forEach(run -> byTraceId.putIfAbsent(run.traceId(), run));
        return byTraceId.values().stream()
                .sorted(Comparator.comparing(TraceRun::startedAt).reversed())
                .limit(effectiveLimit)
                .toList();
    }

    @Override
    public List<TraceReplayEvent> replay(String traceId) {
        return findRun(traceId).map(TraceRun::replayEvents).orElse(List.of());
    }

    @Override
    public TraceRunStats stats(int limit) {
        List<TraceRun> runs = recentRuns(limit);
        int completed = 0;
        int failed = 0;
        int blocked = 0;
        long totalDuration = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        double cost = 0;
        for (TraceRun run : runs) {
            String status = run.status() == null ? "" : run.status();
            if ("COMPLETED".equalsIgnoreCase(status)) completed++;
            else if ("FAILED".equalsIgnoreCase(status)) failed++;
            else if ("BLOCKED".equalsIgnoreCase(status)) blocked++;
            totalDuration += run.durationMs();
            promptTokens += run.estimatedPromptTokens();
            completionTokens += run.estimatedCompletionTokens();
            cost += run.estimatedCost();
        }
        return new TraceRunStats(
                runs.size(),
                completed,
                failed,
                blocked,
                runs.isEmpty() ? 0 : (double) totalDuration / runs.size(),
                promptTokens,
                completionTokens,
                cost
        );
    }

    private TraceSpanKind inferKind(String stage) {
        String value = stage == null ? "" : stage.toLowerCase(Locale.ROOT);
        if (value.contains("memory")) return TraceSpanKind.MEMORY;
        if (value.contains("guardrail")) return TraceSpanKind.GUARDRAIL;
        if (value.contains("skill")) return TraceSpanKind.SKILL;
        if (value.contains("intent") || value.contains("route")) return TraceSpanKind.ROUTER;
        if (value.contains("rewrite")) return TraceSpanKind.QUERY_REWRITE;
        if (value.contains("rag")) return TraceSpanKind.RAG;
        if (value.contains("tool")) return TraceSpanKind.TOOL;
        if (value.contains("approval")) return TraceSpanKind.APPROVAL;
        if (value.contains("prompt")) return TraceSpanKind.PROMPT;
        if (value.contains("llm")) return TraceSpanKind.LLM;
        if (value.contains("eval")) return TraceSpanKind.EVAL;
        if (value.contains("error")) return TraceSpanKind.ERROR;
        return TraceSpanKind.SYSTEM;
    }

    private TraceSpanStatus inferStatus(String detail) {
        String value = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (value.contains("failed") || value.contains("error")) return TraceSpanStatus.FAILED;
        if (value.contains("blocked")) return TraceSpanStatus.BLOCKED;
        if (value.contains("skipped")) return TraceSpanStatus.SKIPPED;
        if (value.contains("miss")) return TraceSpanStatus.MISS;
        if (value.contains("hit")) return TraceSpanStatus.HIT;
        return TraceSpanStatus.COMPLETED;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
