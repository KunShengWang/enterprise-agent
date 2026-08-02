package com.agent.platform.web;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.AgentResponse;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.resilience.RateLimitResult;
import com.agent.platform.resilience.RateLimitService;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentMessageType;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.stream.AgentStreamEvent;
import com.agent.platform.stream.StreamingAgentExecutor;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final String DEFAULT_CONVERSATION_ID = "default-conversation";

    private final AgentExecutor agentExecutor;

    private final AgentProperties agentProperties;

    private final StreamingAgentExecutor streamingAgentExecutor;

    private final RateLimitService rateLimitService;

    private final AgentRunStore agentRunStore;
    private final AgentRuntime agentRuntime;
    private final AgentTimelineStore timelineStore;

    public AgentController(AgentExecutor agentExecutor,
                           AgentProperties agentProperties,
                           StreamingAgentExecutor streamingAgentExecutor,
                           RateLimitService rateLimitService,
                           AgentRunStore agentRunStore,
                           AgentRuntime agentRuntime,
                           AgentTimelineStore timelineStore) {
        this.agentExecutor = agentExecutor;
        this.agentProperties = agentProperties;
        this.streamingAgentExecutor = streamingAgentExecutor;
        this.rateLimitService = rateLimitService;
        this.agentRunStore = agentRunStore;
        this.agentRuntime = agentRuntime;
        this.timelineStore = timelineStore;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(Map.of(
                "name", "enterprise-agent",
                "stage", "V2.0",
                "mockMode", agentProperties.isMockMode()
        ));
    }

    /**
     * 方法 A：客户端要 JSON
     * 完成后返回 AgentResponse 的兼容接口；运行台应请求同一路径的 text/event-stream 表示。
     */
    @PostMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ApiResponse<AgentResponse>> run(@Valid @RequestBody AgentRequest request) {
        // @Valid 会在进入方法前校验 AgentRequest；这里先按 userId 做入口限流，避免单个用户在一分钟内创建过多 Agent Run 和模型调用。
        RateLimitResult limit = rateLimitService.acquire(rateLimitKey(request));
        if (!limit.allowed()) {
            return Mono.just(ApiResponse.failure(com.agent.platform.common.ErrorCode.TOO_MANY_REQUESTS,
                    "请求过于频繁，请稍后重试。limit=" + limit.limit() + "/minute"));
        }

        // RuntimeAgentExecutor 和 SSE 适配器共享同一个 AgentRuntime；同步接口只是在完成后把已持久化事件投影为 AgentResponse。
        return Mono.fromSupplier(() -> ApiResponse.success(agentExecutor.execute(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 方法 B：客户端要 SSE 流
     * 根据 Accept 头在同一资源路径提供结构化 SSE；其中 MODEL_DELTA 是模型正文的真实增量。
     */
    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentStreamEvent> runEvents(@Valid @RequestBody AgentRequest request) {
        return streamEventsInternal(request);
    }

    /**
     * 查询最近的 Run，返回形式是 AgentRunRecord
     */
    @GetMapping("/runs")
    public Mono<ApiResponse<List<AgentRunRecord>>> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(agentRunStore.recent(limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询指定 Run 的权威状态
     */
    @GetMapping("/runs/{runId}")
    public Mono<ApiResponse<AgentRunRecord>> findRun(@PathVariable String runId) {
        return Mono.fromSupplier(() -> agentRunStore.find(runId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.failure(
                        com.agent.platform.common.ErrorCode.NOT_FOUND,
                        "agent run not found: " + runId
                )))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 拉取某次 agent 执行指定序列之后的有限事件
     */
    @GetMapping("/runs/{runId}/events")
    public Mono<ApiResponse<List<AgentEvent>>> runEvents(@PathVariable String runId,
                                                         @RequestParam(defaultValue = "-1") long afterSequence,
                                                         @RequestParam(defaultValue = "500") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(
                        timelineStore.loadEventsAfter(runId, afterSequence, Math.max(1, Math.min(limit, 10_000)))
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Mono<ApiResponse<List<ConversationMessageView>>> conversationMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "200") int limit) {
        return Mono.fromSupplier(() -> {
                    int visibleLimit = Math.max(1, Math.min(limit, 500));
                    int timelineLimit = Math.min(10_000, visibleLimit * 10);
                    List<ConversationMessageView> visibleMessages = timelineStore
                            .loadMessages(normalizeConversationId(conversationId), timelineLimit)
                            .stream()
                            .filter(message -> message.type() == AgentMessageType.USER
                                    || message.type() == AgentMessageType.ASSISTANT_TEXT)
                            .map(message -> new ConversationMessageView(
                                    message.messageId(),
                                    message.runId(),
                                    message.sequence(),
                                    message.type() == AgentMessageType.USER ? "USER" : "ASSISTANT",
                                    message.content(),
                                    message.createdAt()
                            ))
                            .toList();
                    int fromIndex = Math.max(0, visibleMessages.size() - visibleLimit);
                    return ApiResponse.success(List.copyOf(visibleMessages.subList(fromIndex, visibleMessages.size())));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * agent 执行恢复，非流式返回，返回 JSON。返回的结果能看到 agent 的完整执行步骤AgentStep和追踪记录TraceEvent
     */
    @PostMapping("/runs/{runId}/resume")
    public Mono<ApiResponse<AgentResponse>> resumeRun(@PathVariable String runId) {
        return Mono.fromSupplier(() -> ApiResponse.success(agentExecutor.resume(runId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * agent 执行恢复，流式返回。
     */
    @PostMapping(value = "/runs/{runId}/resume/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentStreamEvent> resumeRunEvents(@PathVariable String runId) {
        return streamingAgentExecutor.resume(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Mono<ApiResponse<Map<String, Object>>> cancelRun(@PathVariable String runId) {
        return Mono.fromSupplier(() -> {
                    boolean requested = agentRuntime.cancel(runId);
                    if (!requested) {
                        return ApiResponse.<Map<String, Object>>failure(
                                com.agent.platform.common.ErrorCode.NOT_FOUND,
                                "agent run control not found: " + runId
                        );
                    }
                    return ApiResponse.<Map<String, Object>>success(
                            Map.<String, Object>of("runId", runId, "cancellationRequested", true)
                    );
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/runs/{runId}/pause")
    public Mono<ApiResponse<Map<String, Object>>> pauseRun(@PathVariable String runId) {
        return Mono.fromSupplier(() -> {
                    boolean requested = agentRuntime.pause(runId);
                    if (!requested) {
                        return ApiResponse.<Map<String, Object>>failure(
                                com.agent.platform.common.ErrorCode.NOT_FOUND,
                                "agent run is not pausable: " + runId
                        );
                    }
                    return ApiResponse.<Map<String, Object>>success(
                            Map.<String, Object>of("runId", runId, "pauseRequested", true)
                    );
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * WebFlux 接收到请求
     * -> 切到 boundedElastic 工作线程
     * -> 执行同步 AgentExecutor
     * -> 内部调用 chatModel.call()
     * -> 返回结果
     */
    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody AgentRequest request) {
        RateLimitResult limit = rateLimitService.acquire(rateLimitKey(request));
        if (!limit.allowed()) {
            return Flux.just("error: 请求过于频繁，请稍后重试。limit=" + limit.limit() + "/minute");
        }
        return streamingAgentExecutor.stream(request)
                .map(event -> event.type() + ": " + event.content());
    }

    @PostMapping(value = "/runs/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentStreamEvent> streamEvents(@Valid @RequestBody AgentRequest request) {
        return streamEventsInternal(request);
    }

    private Flux<AgentStreamEvent> streamEventsInternal(AgentRequest request) {
        RateLimitResult limit = rateLimitService.acquire(rateLimitKey(request));
        if (!limit.allowed()) {
            return Flux.just(new AgentStreamEvent(
                    java.util.UUID.randomUUID().toString(),
                    "",
                    normalizeConversationId(request.conversationId()),
                    0,
                    "error",
                    "请求过于频繁，请稍后重试。limit=" + limit.limit() + "/minute",
                    java.time.Instant.now(),
                    Map.of("rateLimitKey", limit.key(), "resetEpochMillis", limit.resetEpochMillis())
            ));
        }
        return streamingAgentExecutor.stream(request);
    }

    private String rateLimitKey(AgentRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            return "anonymous";
        }
        return request.userId().trim();
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return DEFAULT_CONVERSATION_ID;
        }
        return conversationId.trim();
    }
}
