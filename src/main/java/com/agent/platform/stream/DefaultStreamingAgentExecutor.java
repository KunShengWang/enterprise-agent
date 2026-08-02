package com.agent.platform.stream;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.ordercare.config.AgentScenarioProfileResolver;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventListener;
import com.agent.platform.runtime.AgentRuntime;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.function.Consumer;

/**
 * AgentRuntime 的 SSE 事件适配器，不包含任何独立业务执行逻辑。
 */
@Service
public class DefaultStreamingAgentExecutor implements StreamingAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultStreamingAgentExecutor.class);

    private final AgentRuntime runtime;
    private final AgentProperties properties;

    private final AgentScenarioProfileResolver scenarioProfileResolver;

    @Autowired
    public DefaultStreamingAgentExecutor(AgentRuntime runtime,
                                         AgentProperties properties,
                                         AgentScenarioProfileResolver scenarioProfileResolver) {
        this.runtime = runtime;
        this.properties = properties;
        this.scenarioProfileResolver = scenarioProfileResolver;
    }

    DefaultStreamingAgentExecutor(AgentRuntime runtime, AgentProperties properties) {
        this(runtime, properties, null);
    }

    @Override
    public Flux<AgentStreamEvent> stream(AgentRequest request) {
        return streamExecution(
                "",
                sessionId(request),
                listener -> run(request, listener)
        );
    }

    private void run(AgentRequest request, AgentEventListener listener) {
        if (scenarioProfileResolver == null) {
            runtime.run(request, listener);
            return;
        }
        scenarioProfileResolver.resolve(request.scenarioId())
                .ifPresentOrElse(
                        // Optional 有值时执行
                        profile -> runtime.run(request, profile, listener),
                        // Optional 为空时执行
                        () -> runtime.run(request, listener)
                );
    }

    /**
     * agent 执行恢复，流式返回。
     */
    @Override
    public Flux<AgentStreamEvent> resume(String runId) {
        if (runId == null || runId.isBlank()) {
            return Flux.error(new IllegalArgumentException("runId must not be blank"));
        }
        String normalizedRunId = runId.trim();
        return streamExecution(
                normalizedRunId,
                "",
                listener -> runtime.resume(normalizedRunId, listener)
        );
    }

    private Flux<AgentStreamEvent> streamExecution(String initialRunId,
                                                    String initialSessionId,
                                                    Consumer<AgentEventListener> invocation) {
        // 用 AtomicReference 包住因为 lambda 里不能修改外部局部变量，而 AtomicReference 本身引用不变，只改里面存的值
        AtomicReference<String> runId = new AtomicReference<>(initialRunId);
        AtomicReference<String> sessionId = new AtomicReference<>(initialSessionId);
        AtomicLong lastSequence = new AtomicLong(0);
        Flux<AgentStreamEvent> source = Flux.<AgentStreamEvent>create(sink -> {
                    AtomicBoolean cancelled = new AtomicBoolean(false);
                    long heartbeatSeconds = Math.max(1, properties.getStreamHeartbeatSeconds());
                    // 心跳事件，告诉前端该 SSE 还活着
                    Disposable heartbeat = Schedulers.parallel().schedulePeriodically(() -> {
                        // 心跳停止检查，客户端断了就不发了
                        if (!sink.isCancelled()) {
                            sink.next(heartbeatEvent(runId.get(), sessionId.get(), lastSequence.get()));
                        }
                    }, heartbeatSeconds, heartbeatSeconds, java.util.concurrent.TimeUnit.SECONDS);
                    Disposable task = Schedulers.boundedElastic().schedule(() -> {
                        try {
                            // 整个 agent 开始执行
                            invocation.accept(event -> {
                                if (event.runId() != null && !event.runId().isBlank()) {
                                    runId.set(event.runId());
                                }
                                if (event.sessionId() != null && !event.sessionId().isBlank()) {
                                    sessionId.set(event.sessionId());
                                }
                                lastSequence.accumulateAndGet(event.sequence(), Math::max);
                                // SSE 断开，agent 暂停执行
                                if (cancelled.get()) {
                                    // agent 暂停，数据库持久化暂停标志，AgentRunBudget 标志暂停
                                    runtime.pause(event.runId());
                                }
                                // 监听器转发检查，客户端断了就不推了
                                if (!sink.isCancelled()) {
                                    sink.next(toStreamEvent(event));// 往前端推送 agent 执行事件
                                }
                            });
                            // 执行完成检查，客户端断了就别 complete 了
                            if (!sink.isCancelled()) {
                                sink.complete();// ← Agent 执行完了，SSE 流正常结束
                            }
                        }
                        catch (RuntimeException exception) {
                            // 代理运行时 SSE 执行意外终止
                            log.warn("Agent Runtime SSE execution terminated unexpectedly: runId={}, sessionId={}, lastSequence={}",
                                    runId.get(), sessionId.get(), lastSequence.get(), exception);
                            if (!sink.isCancelled()) {
                                sink.next(errorEvent(
                                        runId.get(),
                                        sessionId.get(),
                                        lastSequence.get(),
                                        exception
                                ));
                                sink.complete();// SSE 流正常结束，关上数据流的水龙头，也就是关闭 sink 数据发射器
                            }
                        }
                        finally {
                            heartbeat.dispose();// 关掉定时心跳
                        }
                    });
                    // 客户端主动断连只请求暂停；永久取消仍由显式 cancel API 完成。
                    // 浏览器: 关闭 / 刷新 / 网络断开 -> HTTP 连接断开 -> WebFlux 检测到 -> sink.onCancel() 触发
                    sink.onCancel(() -> {
                        cancelled.set(true);// 标记客户端已中断接收
                        String activeRunId = runId.get();
                        if (activeRunId != null && !activeRunId.isBlank()) {
                            runtime.pause(activeRunId);
                        }
                        heartbeat.dispose();// 关心跳
                        task.dispose();// 关执行任务
                    });
                    // 任务正常结束（不杀 Agent）
                    // sink.complete() -> 触发 reactive 流完成信号 -> WebFlux 收到 → 关闭 HTTP response body -> Netty 发 FIN → 通知 TCP 关闭 -> 触发 sink.onDispose() 回调
                    sink.onDispose(() -> {
                        heartbeat.dispose();// 关掉定时心跳
                        task.dispose();// 关执行任务
                    });
                }, reactor.core.publisher.FluxSink.OverflowStrategy.IGNORE);
        // 两个防御策略
        return source
                // ① 背压缓冲：Agent 生产太快、客户端消化来不及 → 用缓冲区兜底
                .onBackpressureBuffer(
                        Math.max(16, properties.getStreamBackpressureBufferSize()),
                        BufferOverflowStrategy.ERROR // ERROR 策略 → 抛异常 → 触发 ②
                )
                // ② 异常兜底：中间任何异常 → 优雅降级为一条 gap 事件，不 crash 整个连接
                .onErrorResume(error -> Flux.just(
                        gapEvent(runId.get(), sessionId.get(), lastSequence.get(), error)
                ));
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

    private AgentStreamEvent errorEvent(String runId,
                                        String sessionId,
                                        long sequence,
                                        RuntimeException exception) {
        return new AgentStreamEvent(
                UUID.randomUUID().toString(),
                normalize(runId),
                normalize(sessionId),
                sequence,
                "transport_error",
                "Agent Runtime 事件流异常终止。",
                Instant.now(),
                Map.of(
                        "persisted", false,
                        "errorType", exception.getClass().getSimpleName()
                )
        );
    }

    private AgentStreamEvent heartbeatEvent(String runId, String sessionId, long sequence) {
        return new AgentStreamEvent(
                UUID.randomUUID().toString(),
                normalize(runId),
                normalize(sessionId),
                sequence,
                "heartbeat",
                "keep-alive",
                Instant.now(),
                Map.of("persisted", false, "lastPersistedSequence", sequence)
        );
    }

    private AgentStreamEvent gapEvent(String runId,
                                      String sessionId,
                                      long sequence,
                                      Throwable error) {
        return new AgentStreamEvent(
                UUID.randomUUID().toString(),
                normalize(runId),
                normalize(sessionId),
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

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
