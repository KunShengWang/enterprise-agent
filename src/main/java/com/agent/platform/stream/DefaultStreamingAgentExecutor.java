package com.agent.platform.stream;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.llm.LlmService;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.prompt.PromptAssembler;
import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.query.QueryRewriteService;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
import com.agent.platform.router.IntentRoute;
import com.agent.platform.router.IntentRouter;
import com.agent.platform.router.IntentType;
import com.agent.platform.tool.ToolCallPlan;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolExecutor;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolCallPlanner;
import com.agent.platform.trace.TraceContext;
import com.agent.platform.trace.TraceRecorder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DefaultStreamingAgentExecutor implements StreamingAgentExecutor {

    private final TraceRecorder traceRecorder;

    private final AgentProperties agentProperties;

    private final MemoryService memoryService;
    private final GuardrailService guardrailService;
    private final IntentRouter intentRouter;
    private final QueryRewriteService queryRewriteService;
    private final RagService ragService;
    private final ToolRegistry toolRegistry;
    private final ToolCallPlanner toolCallPlanner;
    private final ToolExecutor toolExecutor;
    private final PromptAssembler promptAssembler;
    private final LlmService llmService;

    public DefaultStreamingAgentExecutor(TraceRecorder traceRecorder,
                                         AgentProperties agentProperties,
                                         MemoryService memoryService,
                                         GuardrailService guardrailService,
                                         IntentRouter intentRouter,
                                         QueryRewriteService queryRewriteService,
                                         RagService ragService,
                                         ToolRegistry toolRegistry,
                                         ToolCallPlanner toolCallPlanner,
                                         ToolExecutor toolExecutor,
                                         PromptAssembler promptAssembler,
                                         LlmService llmService) {
        this.traceRecorder = traceRecorder;
        this.agentProperties = agentProperties;
        this.memoryService = memoryService;
        this.guardrailService = guardrailService;
        this.intentRouter = intentRouter;
        this.queryRewriteService = queryRewriteService;
        this.ragService = ragService;
        this.toolRegistry = toolRegistry;
        this.toolCallPlanner = toolCallPlanner;
        this.toolExecutor = toolExecutor;
        this.promptAssembler = promptAssembler;
        this.llmService = llmService;
    }

    @Override
    public Flux<AgentStreamEvent> stream(AgentRequest originalRequest) {
        return Flux.defer(() -> Mono.fromCallable(() -> prepare(originalRequest))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(prepared -> {
                            if (prepared.terminalEvent() != null) {
                                return Flux.concat(Flux.fromIterable(prepared.prefixEvents()), Flux.just(prepared.terminalEvent()));
                            }
                            StringBuilder answer = new StringBuilder();
                            Flux<AgentStreamEvent> prefix = Flux.fromIterable(prepared.prefixEvents());
                            Flux<AgentStreamEvent> modelEvents = llmService.stream(prepared.prompt())
                                    .doOnNext(answer::append)
                                    .map(token -> event(prepared.trace().traceId(), prepared.conversationId(), "llm.token", token, Map.of()))
                                    .concatWith(Mono.fromSupplier(() -> finishModelStream(prepared, answer.toString())))
                                    .onErrorResume(error -> Mono.fromSupplier(() -> failModelStream(prepared, error)));
                            return Flux.concat(prefix, protectStream(prepared, modelEvents))
                                    .onBackpressureBuffer(
                                            streamBufferSize(),
                                            dropped -> {
                                            },
                                            BufferOverflowStrategy.DROP_OLDEST
                                    );
                        }));
    }

    private Flux<AgentStreamEvent> protectStream(PreparedStream prepared, Flux<AgentStreamEvent> modelEvents) {
        return modelEvents.publish(shared -> {
            Flux<AgentStreamEvent> heartbeat = Flux
                    .interval(java.time.Duration.ofSeconds(streamHeartbeatSeconds()))
                    .map(sequence -> event(
                            prepared.trace().traceId(),
                            prepared.conversationId(),
                            "heartbeat",
                            "stream is alive",
                            Map.of("sequence", sequence)
                    ))
                    .takeUntilOther(shared.then());
            return Flux.merge(shared, heartbeat);
        });
    }

    private PreparedStream prepare(AgentRequest originalRequest) {
        String conversationId = originalRequest.conversationId() == null || originalRequest.conversationId().isBlank()
                ? "default-conversation"
                : originalRequest.conversationId();
        TraceContext trace = traceRecorder.start(conversationId, originalRequest.question());
        List<AgentStreamEvent> events = new ArrayList<>();
        events.add(event(trace.traceId(), conversationId, "run.started", "streaming agent started", Map.of()));

        ConversationMemory memory = memoryService.load(conversationId, originalRequest.userId(), originalRequest.question());
        events.add(event(trace.traceId(), conversationId, "memory.loaded",
                "messages=" + memory.messages().size() + ", recalled=" + memory.recalledMemories().size(), Map.of()));

        GuardrailDecision inputDecision = guardrailService.checkInput(originalRequest.question());
        events.add(event(trace.traceId(), conversationId, "guardrail.input", inputDecision.action().name() + ": " + inputDecision.reason(), Map.of()));
        if (inputDecision.action() == GuardrailAction.BLOCK) {
            traceRecorder.markStatus(trace, "BLOCKED", inputDecision.reason());
            traceRecorder.finish(trace);
            AgentStreamEvent terminalEvent = event(trace.traceId(), conversationId, "final",
                    "请求已被安全护栏拦截：" + inputDecision.reason(),
                    Map.of("guardrail", inputDecision.action().name()));
            return new PreparedStream(trace, conversationId, originalRequest, null, events, terminalEvent);
        }
        AgentRequest request = inputDecision.action() == GuardrailAction.REDACT && inputDecision.safeContent() != null
                ? new AgentRequest(originalRequest.conversationId(), originalRequest.userId(), inputDecision.safeContent(), originalRequest.metadata())
                : originalRequest;
        memoryService.append(conversationId, request.userId(), new MemoryMessage("user", request.question(), Instant.now()));

        IntentRoute route = intentRouter.route(request, memory);
        events.add(event(trace.traceId(), conversationId, "route.selected", route.type().name() + ": " + route.reason(), Map.of("slots", route.slots())));

        String rewrittenQuery = queryRewriteService.rewrite(request, memory);
        events.add(event(trace.traceId(), conversationId, "query.rewritten", rewrittenQuery, Map.of()));

        RagResult ragResult = RagResult.empty(rewrittenQuery);
        List<ToolCallResult> toolResults = new ArrayList<>();
        if (route.type() == IntentType.RAG) {
            ragResult = ragService.retrieve(rewrittenQuery, 3);
            events.add(event(trace.traceId(), conversationId, "rag.retrieved", "documents=" + ragResult.documents().size(), Map.of("hit", ragResult.enoughEvidence())));
        }
        else if (route.type() == IntentType.TOOL) {
            List<ToolDefinition> tools = toolRegistry.listTools();
            events.add(event(trace.traceId(), conversationId, "tool.registry", "availableTools=" + tools.size(), Map.of()));
            ToolCallPlan plan = toolCallPlanner.plan(request, memory, route, tools, toolResults);
            events.add(event(trace.traceId(), conversationId, "tool.planned", plan.reason(), Map.of("tool", plan.toolName(), "shouldCall", plan.shouldCallTool())));
            if (plan.shouldCallTool()) {
                ToolCallResult result = toolExecutor.execute(new ToolCallRequest(plan.toolName(), UUID.randomUUID().toString(), plan.arguments()));
                toolResults.add(result);
                events.add(event(trace.traceId(), conversationId, "tool.executed", "tool=" + plan.toolName() + ", success=" + result.success(), Map.of()));
            }
        }
        else {
            events.add(event(trace.traceId(), conversationId, "chat.fallback", "no RAG or tool branch selected", Map.of()));
        }

        PromptRequest prompt = promptAssembler.assemble(request, memory, ragResult, toolResults);
        events.add(event(trace.traceId(), conversationId, "prompt.assembled", "contextBlocks=" + prompt.contextBlocks().size(), Map.of()));
        events.add(event(trace.traceId(), conversationId, "llm.started", "model stream started", Map.of()));
        return new PreparedStream(trace, conversationId, request, prompt, events, null);
    }

    private AgentStreamEvent finishModelStream(PreparedStream prepared, String finalAnswer) {
        GuardrailDecision outputDecision = guardrailService.checkOutput(finalAnswer);
        String safeAnswer = outputDecision.action() == GuardrailAction.REDACT ? outputDecision.safeContent() : finalAnswer;
        memoryService.append(prepared.conversationId(), prepared.request().userId(), new MemoryMessage("assistant", safeAnswer, Instant.now()));
        traceRecorder.markStatus(prepared.trace(), "COMPLETED", "");
        traceRecorder.finish(prepared.trace());
        return event(prepared.trace().traceId(), prepared.conversationId(), "final", safeAnswer, Map.of("guardrail", outputDecision.action().name()));
    }

    private AgentStreamEvent failModelStream(PreparedStream prepared, Throwable error) {
        traceRecorder.markStatus(prepared.trace(), "FAILED", error.getMessage());
        traceRecorder.finish(prepared.trace());
        return event(prepared.trace().traceId(), prepared.conversationId(), "error",
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                Map.of("stage", "llm.stream"));
    }

    private AgentStreamEvent event(String traceId, String conversationId, String type, String content, Map<String, Object> metadata) {
        return new AgentStreamEvent(UUID.randomUUID().toString(), traceId, conversationId, type, content, Instant.now(), metadata);
    }

    private int streamBufferSize() {
        return Math.max(16, agentProperties.getStreamBackpressureBufferSize());
    }

    private int streamHeartbeatSeconds() {
        return Math.max(1, agentProperties.getStreamHeartbeatSeconds());
    }

    private record PreparedStream(
            TraceContext trace,
            String conversationId,
            AgentRequest request,
            PromptRequest prompt,
            List<AgentStreamEvent> prefixEvents,
            AgentStreamEvent terminalEvent
    ) {
    }
}
