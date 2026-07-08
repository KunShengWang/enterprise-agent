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
import com.agent.platform.stream.AgentStreamEvent;
import com.agent.platform.stream.StreamingAgentExecutor;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.stream.Stream;

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

    public AgentController(AgentExecutor agentExecutor,
                           AgentProperties agentProperties,
                           IntentRouter intentRouter,
                           MemoryService memoryService,
                           StreamingAgentExecutor streamingAgentExecutor,
                           RateLimitService rateLimitService) {
        this.agentExecutor = agentExecutor;
        this.agentProperties = agentProperties;
        this.intentRouter = intentRouter;
        this.memoryService = memoryService;
        this.streamingAgentExecutor = streamingAgentExecutor;
        this.rateLimitService = rateLimitService;
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
        RateLimitResult limit = rateLimitService.acquire(rateLimitKey(request));
        if (!limit.allowed()) {
            return Mono.just(ApiResponse.failure(com.agent.platform.common.ErrorCode.TOO_MANY_REQUESTS,
                    "请求过于频繁，请稍后重试。limit=" + limit.limit() + "/minute"));
        }
        return Mono.fromSupplier(() -> ApiResponse.success(agentExecutor.execute(request)))
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
        return Mono.fromSupplier(() -> agentExecutor.execute(request))
                .subscribeOn(Schedulers.boundedElastic())
                // 把完整执行结果拆成 SSE 片段，方便前端观察步骤和最终回答。
                .flatMapMany(response -> Flux.fromStream(Stream.concat(
                        response.steps().stream().map(step -> "step: " + step.name() + " [" + step.status() + "] " + step.summary()),
                        Stream.of("answer: " + response.answer(), "traceId: " + response.trace().traceId())
                )));
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
