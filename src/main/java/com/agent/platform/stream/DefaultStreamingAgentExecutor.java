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
        return Flux.<AgentStreamEvent>create(sink -> {
                    AtomicReference<String> runId = new AtomicReference<>();
                    AtomicBoolean cancelled = new AtomicBoolean(false);
                    Disposable task = Schedulers.boundedElastic().schedule(() -> {
                        try {
                            runtime.run(request, event -> {
                                runId.compareAndSet(null, event.runId());
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
                    });
                    sink.onCancel(() -> {
                        cancelled.set(true);
                        String activeRunId = runId.get();
                        if (activeRunId != null && !activeRunId.isBlank()) {
                            runtime.cancel(activeRunId);
                        }
                        task.dispose();
                    });
                    sink.onDispose(task::dispose);
                }, FluxSinkOverflowStrategy.buffer())
                .onBackpressureBuffer(
                        Math.max(16, properties.getStreamBackpressureBufferSize()),
                        dropped -> {
                        },
                        BufferOverflowStrategy.DROP_OLDEST
                );
    }

    private AgentStreamEvent toStreamEvent(AgentEvent event) {
        return new AgentStreamEvent(
                event.eventId(),
                event.runId(),
                event.sessionId(),
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
                "transport_error",
                "Agent Runtime 事件流异常终止。",
                Instant.now(),
                Map.of("errorType", exception.getClass().getSimpleName())
        );
    }

    /**
     * Reactor 3.8 的 Flux.create 仍通过 OverflowStrategy 选择生产侧策略，封装在这里
     * 避免 Runtime 感知 Reactor 类型。
     */
    private static final class FluxSinkOverflowStrategy {
        private static reactor.core.publisher.FluxSink.OverflowStrategy buffer() {
            return reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER;
        }
    }
}
