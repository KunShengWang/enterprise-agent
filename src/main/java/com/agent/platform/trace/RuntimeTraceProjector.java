package com.agent.platform.trace;

import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentTimelineStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 将统一 Runtime 的持久化 Run + Event 时间线投影为原有 Trace 查询模型。
 *
 * <p>投影是可重复计算的，不在请求结束时再保存一份易漂移的完整 Trace 快照。</p>
 */
@Component
public class RuntimeTraceProjector {

    private static final int MAX_EVENTS_PER_RUN = 10_000;

    private final AgentRunStore runStore;
    private final AgentTimelineStore timelineStore;

    public RuntimeTraceProjector(AgentRunStore runStore, AgentTimelineStore timelineStore) {
        this.runStore = runStore;
        this.timelineStore = timelineStore;
    }

    public Optional<TraceRun> project(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        AgentRunRecord run = runStore.find(runId).orElse(null);
        if (run == null) {
            return Optional.empty();
        }
        List<AgentEvent> events = timelineStore.loadEvents(runId, MAX_EVENTS_PER_RUN);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toTraceRun(run, events));
    }

    public List<TraceRun> recent(int limit) {
        return runStore.recent(Math.max(1, limit)).stream()
                .map(run -> {
                    List<AgentEvent> events = timelineStore.loadEvents(run.runId(), MAX_EVENTS_PER_RUN);
                    return events.isEmpty() ? null : toTraceRun(run, events);
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(TraceRun::startedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private TraceRun toTraceRun(AgentRunRecord run, List<AgentEvent> events) {
        List<AgentEvent> ordered = events.stream()
                .sorted(Comparator.comparingLong(AgentEvent::sequence))
                .toList();
        Instant startedAt = ordered.get(0).createdAt() == null ? run.createdAt() : ordered.get(0).createdAt();
        Instant endedAt = terminalEvent(ordered)
                .map(AgentEvent::createdAt)
                .orElseGet(() -> run.state() == AgentRunState.RUNNING
                        || run.state() == AgentRunState.PAUSE_REQUESTED
                        || run.state() == AgentRunState.PAUSED
                        || run.state() == AgentRunState.WAITING_APPROVAL
                        || run.state() == AgentRunState.WAITING_INPUT
                        ? null
                        : run.updatedAt());
        Instant durationEnd = endedAt == null ? Instant.now() : endedAt;
        long durationMs = Math.max(0, Duration.between(startedAt, durationEnd).toMillis());
        long promptTokens = sumLongPayload(ordered, AgentEventType.MODEL_COMPLETED, "promptTokens");
        long completionTokens = sumLongPayload(ordered, AgentEventType.MODEL_COMPLETED, "completionTokens");
        double estimatedCost = finalBudgetNumber(ordered, "estimatedCost");

        List<TraceEvent> traceEvents = ordered.stream()
                .map(event -> new TraceEvent(event.createdAt(), event.type().name(), event.content()))
                .toList();
        List<TraceReplayEvent> replayEvents = ordered.stream()
                .map(event -> new TraceReplayEvent(
                        event.sequence() > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) event.sequence(),
                        event.createdAt(),
                        event.type().name(),
                        event.content(),
                        event.payload()
                ))
                .toList();
        Map<String, Object> metrics = metrics(ordered, promptTokens, completionTokens, estimatedCost);

        return new TraceRun(
                run.runId(),
                run.conversationId(),
                run.request() == null ? "" : run.request().question(),
                run.state().name(),
                startedAt,
                endedAt,
                durationMs,
                run.failureReason(),
                promptTokens,
                completionTokens,
                estimatedCost,
                spans(run, ordered, startedAt, durationEnd),
                traceEvents,
                replayEvents,
                metrics
        );
    }

    private List<TraceSpan> spans(AgentRunRecord run,
                                  List<AgentEvent> events,
                                  Instant startedAt,
                                  Instant endedAt) {
        String rootSpanId = run.runId() + ":root";
        List<TraceSpan> spans = new ArrayList<>();
        spans.add(new TraceSpan(
                rootSpanId,
                run.runId(),
                null,
                "agent.run",
                TraceSpanKind.AGENT,
                terminalStatus(run.state()),
                "Agent Runtime run",
                startedAt,
                endedAt,
                Math.max(0, Duration.between(startedAt, endedAt).toMillis()),
                "",
                "",
                run.failureReason(),
                Map.of("sessionId", run.conversationId(), "resumeCount", run.resumeCount())
        ));

        Instant modelStartedAt = null;
        Map<String, AgentEvent> toolStarts = new HashMap<>();
        for (AgentEvent event : events) {
            if (event.type() == AgentEventType.MODEL_STARTED) {
                modelStartedAt = event.createdAt();
                continue;
            }
            if (event.type() == AgentEventType.MODEL_COMPLETED) {
                Instant spanStart = modelStartedAt == null ? event.createdAt() : modelStartedAt;
                spans.add(eventSpan(event, rootSpanId, "model.turn", TraceSpanKind.LLM,
                        TraceSpanStatus.COMPLETED, spanStart));
                modelStartedAt = null;
                continue;
            }
            if (event.type() == AgentEventType.TOOL_REQUESTED) {
                toolStarts.put(stringPayload(event, "toolCallId"), event);
                continue;
            }
            if (event.type() == AgentEventType.TOOL_COMPLETED) {
                String toolCallId = stringPayload(event, "toolCallId");
                AgentEvent start = toolStarts.remove(toolCallId);
                boolean success = booleanPayload(event, "success");
                spans.add(eventSpan(
                        event,
                        rootSpanId,
                        "tool." + defaultString(stringPayload(event, "toolName"), "unknown"),
                        "knowledge_search".equals(stringPayload(event, "toolName"))
                                ? TraceSpanKind.RAG : TraceSpanKind.TOOL,
                        success ? TraceSpanStatus.COMPLETED : TraceSpanStatus.FAILED,
                        start == null ? event.createdAt() : start.createdAt()
                ));
                continue;
            }
            TraceSpanKind kind = switch (event.type()) {
                case CONTEXT_PREPARED, CONTEXT_COMPACTED -> TraceSpanKind.PROMPT;
                case POLICY_DECIDED -> TraceSpanKind.GUARDRAIL;
                case APPROVAL_REQUIRED -> TraceSpanKind.APPROVAL;
                case SUB_AGENT_STARTED, SUB_AGENT_COMPLETED -> TraceSpanKind.AGENT;
                case MODEL_FAILED, RUN_FAILED -> TraceSpanKind.ERROR;
                default -> null;
            };
            if (kind != null) {
                spans.add(eventSpan(event, rootSpanId, event.type().name().toLowerCase(), kind,
                        eventStatus(event), event.createdAt()));
            }
        }
        return List.copyOf(spans);
    }

    private TraceSpan eventSpan(AgentEvent event,
                                String parentSpanId,
                                String name,
                                TraceSpanKind kind,
                                TraceSpanStatus status,
                                Instant startedAt) {
        Instant safeStart = startedAt == null ? event.createdAt() : startedAt;
        long durationMs = Math.max(0, Duration.between(safeStart, event.createdAt()).toMillis());
        return new TraceSpan(
                event.eventId(),
                event.runId(),
                parentSpanId,
                name,
                kind,
                status,
                event.content(),
                safeStart,
                event.createdAt(),
                durationMs,
                "",
                "",
                status == TraceSpanStatus.FAILED ? event.content() : "",
                event.payload()
        );
    }

    private Map<String, Object> metrics(List<AgentEvent> events,
                                        long promptTokens,
                                        long completionTokens,
                                        double estimatedCost) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("modelCalls", count(events, AgentEventType.MODEL_COMPLETED));
        metrics.put("toolCalls", count(events, AgentEventType.TOOL_REQUESTED));
        metrics.put("contextCompactions", count(events, AgentEventType.CONTEXT_COMPACTED));
        metrics.put("approvalWaits", count(events, AgentEventType.APPROVAL_REQUIRED));
        metrics.put("promptTokens", promptTokens);
        metrics.put("completionTokens", completionTokens);
        metrics.put("estimatedCost", estimatedCost);
        terminalEvent(events).ifPresent(event -> {
            metrics.put("stopReason", stringPayload(event, "stopReason"));
            Object budget = event.payload().get("budget");
            if (budget != null) {
                metrics.put("budget", budget);
            }
        });
        return Map.copyOf(metrics);
    }

    private Optional<AgentEvent> terminalEvent(List<AgentEvent> events) {
        return events.stream()
                .filter(event -> event.type() == AgentEventType.RUN_COMPLETED
                        || event.type() == AgentEventType.RUN_FAILED
                        || event.type() == AgentEventType.RUN_CANCELLED)
                .reduce((first, second) -> second);
    }

    private TraceSpanStatus terminalStatus(AgentRunState state) {
        return switch (state) {
            case COMPLETED -> TraceSpanStatus.COMPLETED;
            case BLOCKED -> TraceSpanStatus.BLOCKED;
            case REJECTED -> TraceSpanStatus.REJECTED;
            case FAILED, MANUAL_REVIEW -> TraceSpanStatus.FAILED;
            case WAITING_APPROVAL, WAITING_INPUT, NEEDS_CLARIFICATION, PAUSE_REQUESTED, PAUSED, RUNNING, CREATED ->
                    TraceSpanStatus.STARTED;
        };
    }

    private TraceSpanStatus eventStatus(AgentEvent event) {
        if (event.type() == AgentEventType.MODEL_FAILED || event.type() == AgentEventType.RUN_FAILED) {
            return TraceSpanStatus.FAILED;
        }
        if (event.type() == AgentEventType.APPROVAL_REQUIRED) {
            return TraceSpanStatus.STARTED;
        }
        if (event.type() == AgentEventType.POLICY_DECIDED
                && "DENY".equalsIgnoreCase(stringPayload(event, "action"))) {
            return TraceSpanStatus.BLOCKED;
        }
        return TraceSpanStatus.COMPLETED;
    }

    private long sumLongPayload(List<AgentEvent> events, AgentEventType type, String key) {
        return events.stream()
                .filter(event -> event.type() == type)
                .mapToLong(event -> longValue(event.payload().get(key)))
                .sum();
    }

    private double finalBudgetNumber(List<AgentEvent> events, String key) {
        Object budget = terminalEvent(events).map(event -> event.payload().get("budget")).orElse(null);
        if (budget instanceof Map<?, ?> map) {
            Object value = map.get(key);
            return value instanceof Number number ? Math.max(0, number.doubleValue()) : 0;
        }
        return 0;
    }

    private long count(List<AgentEvent> events, AgentEventType type) {
        return events.stream().filter(event -> event.type() == type).count();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        try {
            return Math.max(0, Long.parseLong(String.valueOf(value)));
        }
        catch (RuntimeException ignored) {
            return 0;
        }
    }

    private boolean booleanPayload(AgentEvent event, String key) {
        Object value = event.payload().get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringPayload(AgentEvent event, String key) {
        Object value = event.payload().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
