package com.agent.platform.trace;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryTraceRecorder implements TraceRecorder {

    private static final int MAX_RUNS = 500;

    private final ConcurrentMap<String, TraceRun> runsByTraceId = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> recentTraceIds = new ConcurrentLinkedDeque<>();

    @Override
    public TraceContext start(String conversationId, String question) {
        TraceContext context = new TraceContext(UUID.randomUUID().toString(), conversationId, question);
        context.addEvent("trace.start", "question received");
        context.addReplayEvent("run.started", "Agent run started", Map.of(
                "traceId", context.traceId(),
                "conversationId", context.conversationId(),
                "question", question == null ? "" : question
        ));
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
        context.addReplayEvent("span." + span.status().name().toLowerCase(), span.name() + ": " + span.summary(), Map.of(
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
        runsByTraceId.put(run.traceId(), run);
        recentTraceIds.remove(run.traceId());
        recentTraceIds.addFirst(run.traceId());
        trimOldRuns();
        return context.summary();
    }

    @Override
    public Optional<TraceRun> findRun(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(runsByTraceId.get(traceId.trim()));
    }

    @Override
    public List<TraceRun> recentRuns(int limit) {
        int effectiveLimit = Math.max(1, limit);
        List<TraceRun> runs = new ArrayList<>();
        for (String traceId : recentTraceIds) {
            TraceRun run = runsByTraceId.get(traceId);
            if (run != null) {
                runs.add(run);
            }
            if (runs.size() >= effectiveLimit) {
                break;
            }
        }
        return runs;
    }

    @Override
    public List<TraceReplayEvent> replay(String traceId) {
        return findRun(traceId)
                .map(TraceRun::replayEvents)
                .orElse(List.of());
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
            if ("COMPLETED".equalsIgnoreCase(status)) {
                completed++;
            }
            else if ("FAILED".equalsIgnoreCase(status)) {
                failed++;
            }
            else if ("BLOCKED".equalsIgnoreCase(status)) {
                blocked++;
            }
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

    private void trimOldRuns() {
        while (recentTraceIds.size() > MAX_RUNS) {
            String removed = recentTraceIds.pollLast();
            if (removed != null) {
                runsByTraceId.remove(removed);
            }
        }
    }

    private TraceSpanKind inferKind(String stage) {
        String value = stage == null ? "" : stage.toLowerCase();
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
        String value = detail == null ? "" : detail.toLowerCase();
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
