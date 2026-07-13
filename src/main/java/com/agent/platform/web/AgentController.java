package com.agent.platform.web;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.AgentResponse;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.resilience.RateLimitResult;
import com.agent.platform.resilience.RateLimitService;
import com.agent.platform.router.IntentRoute;
import com.agent.platform.router.IntentRouter;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntime;
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

    private final IntentRouter intentRouter;

    private final MemoryService memoryService;

    private final StreamingAgentExecutor streamingAgentExecutor;

    private final RateLimitService rateLimitService;

    private final AgentRunStore agentRunStore;
    private final AgentRuntime agentRuntime;

    public AgentController(AgentExecutor agentExecutor,
                           AgentProperties agentProperties,
                           IntentRouter intentRouter,
                           MemoryService memoryService,
                           StreamingAgentExecutor streamingAgentExecutor,
                           RateLimitService rateLimitService,
                           AgentRunStore agentRunStore,
                           AgentRuntime agentRuntime) {
        this.agentExecutor = agentExecutor;
        this.agentProperties = agentProperties;
        this.intentRouter = intentRouter;
        this.memoryService = memoryService;
        this.streamingAgentExecutor = streamingAgentExecutor;
        this.rateLimitService = rateLimitService;
        this.agentRunStore = agentRunStore;
        this.agentRuntime = agentRuntime;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(Map.of(
                "name", "enterprise-agent",
                "stage", "V2.0",
                "mockMode", agentProperties.isMockMode()
        ));
    }

    @PostMapping("/routes/preview")
    public Mono<ApiResponse<Map<String, Object>>> previewRoute(@Valid @RequestBody AgentRequest request) {
        return Mono.fromSupplier(() -> {
                    String conversationId = normalizeConversationId(request.conversationId());
                    IntentRoute route = intentRouter.route(request, memoryService.load(conversationId, request.userId(), request.question()));
                    return ApiResponse.success(Map.of(
                            "conversationId", conversationId,
                            "type", route.type().name(),
                            "reason", route.reason(),
                            "slots", route.slots()
                    ));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/runs")
    public Mono<ApiResponse<AgentResponse>> run(@Valid @RequestBody AgentRequest request) {
        // @Valid 会在进入方法前校验 AgentRequest；这里先按 userId 做入口限流，
        // 避免单个用户在一分钟内创建过多 Agent Run 和模型调用。
        RateLimitResult limit = rateLimitService.acquire(rateLimitKey(request));
        if (!limit.allowed()) {
            return Mono.just(ApiResponse.failure(com.agent.platform.common.ErrorCode.TOO_MANY_REQUESTS,
                    "请求过于频繁，请稍后重试。limit=" + limit.limit() + "/minute"));
        }

        // RuntimeAgentExecutor 和 SSE 适配器共享同一个 AgentRuntime；同步接口只是在完成后
        // 把已持久化事件投影为 AgentResponse。
        return Mono.fromSupplier(() -> ApiResponse.success(agentExecutor.execute(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/runs")
    public Mono<ApiResponse<List<AgentRunRecord>>> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(agentRunStore.recent(limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

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

    @PostMapping("/runs/{runId}/resume")
    public Mono<ApiResponse<AgentResponse>> resumeRun(@PathVariable String runId) {
        return Mono.fromSupplier(() -> ApiResponse.success(agentExecutor.resume(runId)))
                .subscribeOn(Schedulers.boundedElastic());
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
        RateLimitResult limit = rateLimitService.acquire(rateLimitKey(request));
        if (!limit.allowed()) {
            return Flux.just(new AgentStreamEvent(
                    java.util.UUID.randomUUID().toString(),
                    "",
                    normalizeConversationId(request.conversationId()),
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
