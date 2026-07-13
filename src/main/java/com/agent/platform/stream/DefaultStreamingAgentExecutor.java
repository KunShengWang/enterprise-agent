package com.agent.platform.stream;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentRuntime;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentRuntime 的 SSE 事件适配器，不包含任何独立业务执行逻辑。
 */
@Service
public class DefaultStreamingAgentExecutor implements StreamingAgentExecutor {

    private final AgentRuntime runtime;
    private final AgentProperties properties;

    public DefaultStreamingAgentExecutor(AgentRuntime runtime, AgentProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
    }

    @Override
    public Flux<AgentStreamEvent> stream(AgentRequest request) {
        AtomicReference<String> runId = new AtomicReference<>();
        AtomicLong lastSequence = new AtomicLong(0);
        Flux<AgentStreamEvent> source = Flux.<AgentStreamEvent>create(sink -> {
                    AtomicBoolean cancelled = new AtomicBoolean(false);
                    long heartbeatSeconds = Math.max(1, properties.getStreamHeartbeatSeconds());
                    Disposable heartbeat = Schedulers.parallel().schedulePeriodically(() -> {
                        if (!sink.isCancelled()) {
                            sink.next(heartbeatEvent(request, runId.get(), lastSequence.get()));
                        }
                    }, heartbeatSeconds, heartbeatSeconds, java.util.concurrent.TimeUnit.SECONDS);
                    Disposable task = Schedulers.boundedElastic().schedule(() -> {
                        try {
                            runtime.run(request, event -> {
                                runId.compareAndSet(null, event.runId());
                                lastSequence.accumulateAndGet(event.sequence(), Math::max);
                                if (cancelled.get()) {
                                    runtime.cancel(event.runId());
                                }
                                if (!sink.isCancelled()) {
                                    sink.next(toStreamEvent(event));
                                }
                            });
                            if (!sink.isCancelled()) {
                                sink.complete();
                            }
                        }
                        catch (RuntimeException exception) {
                            if (!sink.isCancelled()) {
                                sink.next(errorEvent(request, exception));
                                sink.complete();
                            }
                        }
                        finally {
                            heartbeat.dispose();
                        }
                    });
                    sink.onCancel(() -> {
                        cancelled.set(true);
                        String activeRunId = runId.get();
                        if (activeRunId != null && !activeRunId.isBlank()) {
                            runtime.cancel(activeRunId);
                        }
                        heartbeat.dispose();
                        task.dispose();
                    });
                    sink.onDispose(() -> {
                        heartbeat.dispose();
                        task.dispose();
                    });
                }, reactor.core.publisher.FluxSink.OverflowStrategy.IGNORE);
        return source
                .onBackpressureBuffer(
                        Math.max(16, properties.getStreamBackpressureBufferSize()),
                        BufferOverflowStrategy.ERROR
                )
                .onErrorResume(error -> Flux.just(gapEvent(request, runId.get(), lastSequence.get(), error)));
    }

    private AgentStreamEvent toStreamEvent(AgentEvent event) {
        return new AgentStreamEvent(
                event.eventId(),
                event.runId(),
                event.sessionId(),
                event.sequence(),
                event.type().name().toLowerCase(java.util.Locale.ROOT),
                event.content(),
                event.createdAt(),
                event.payload()
        );
    }

    private AgentStreamEvent errorEvent(AgentRequest request, RuntimeException exception) {
        String sessionId = request == null || request.conversationId() == null || request.conversationId().isBlank()
                ? "default-conversation"
                : request.conversationId().trim();
        return new AgentStreamEvent(
                UUID.randomUUID().toString(),
                "",
                sessionId,
                0,
                "transport_error",
                "Agent Runtime 事件流异常终止。",
                Instant.now(),
                Map.of("errorType", exception.getClass().getSimpleName())
        );
    }

    private AgentStreamEvent heartbeatEvent(AgentRequest request, String runId, long sequence) {
        return new AgentStreamEvent(
                UUID.randomUUID().toString(),
                runId == null ? "" : runId,
                sessionId(request),
                sequence,
                "heartbeat",
                "keep-alive",
                Instant.now(),
                Map.of("persisted", false, "lastPersistedSequence", sequence)
        );
    }

    private AgentStreamEvent gapEvent(AgentRequest request, String runId, long sequence, Throwable error) {
        return new AgentStreamEvent(
                UUID.randomUUID().toString(),
                runId == null ? "" : runId,
                sessionId(request),
                sequence,
                "stream_gap",
                "SSE 消费速度不足，事件流已终止，请按持久化序号重新加载。",
                Instant.now(),
                Map.of(
                        "persisted", false,
                        "lastPersistedSequence", sequence,
                        "replayRequired", true,
                        "errorType", error.getClass().getSimpleName()
                )
        );
    }

    private String sessionId(AgentRequest request) {
        return request == null || request.conversationId() == null || request.conversationId().isBlank()
                ? "default-conversation"
                : request.conversationId().trim();
    }
}
