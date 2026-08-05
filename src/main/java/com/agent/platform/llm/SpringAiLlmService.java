package com.agent.platform.llm;

import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.config.ResilienceProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(prefix = "enterprise-agent", name = "mock-mode", havingValue = "false", matchIfMissing = true)
public class SpringAiLlmService implements LlmService, NativeChatModelClient {

    private static final String MISSING_CHAT_MODEL_MESSAGE = """
            未找到真实 ChatModel Bean，无法调用真实模型。
            请检查：
            1. IDEA 是否已经重新加载 Maven，确保 spring-ai-starter-model-deepseek 进入运行时 classpath；
            2. Run Configuration 是否配置了环境变量 DEEPSEEK_API_KEY；
            3. application.yaml 是否包含 spring.ai.model.chat=deepseek；
            4. enterprise-agent.mock-mode 是否为 false。
            """.strip();

    private final ObjectProvider<ChatModel> chatModelProvider;

    private final ResilienceProperties resilienceProperties;

    private final ThreadLocal<LlmUsage> lastUsage = new ThreadLocal<>();
    private final ThreadLocal<String> lastFinishReason = new ThreadLocal<>();

    private final ThreadPoolExecutor callExecutor;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilEpochMillis = new AtomicLong();

    public SpringAiLlmService(ObjectProvider<ChatModel> chatModelProvider,
                              ResilienceProperties resilienceProperties) {
        this.chatModelProvider = chatModelProvider;
        this.resilienceProperties = resilienceProperties;
        int threads = Math.max(1, resilienceProperties.getLlm().getExecutorThreads());
        int queueCapacity = Math.max(1, resilienceProperties.getLlm().getExecutorQueueCapacity());
        AtomicInteger threadSequence = new AtomicInteger();
        this.callExecutor = new ThreadPoolExecutor(
                threads,
                threads,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "agent-llm-" + threadSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.callExecutor.allowCoreThreadTimeOut(true);
    }

    @Override
    public String complete(PromptRequest promptRequest) {
        ChatResponse response;
        try {
            // 调用 LLM 并在 LLM 调用失败时进行有限次重试
            response = callWithRetry(toSpringPrompt(promptRequest));
            // 从 LLM 返回信息中提取 token 调用额度并保存
            lastUsage.set(extractUsage(response));
            captureFinishReason(response);
        }
        catch (RuntimeException exception) {
            // 上下文溢出必须交给 Agent Runtime 压缩后重试，不能降级成一条伪最终回答。
            if (isContextOverflow(exception)) {
                throw contextOverflowException(exception);
            }
            if (isInterruptedFailure(exception)) {
                throw toLlmCallException(exception);
            }
            if (resilienceProperties.getLlm().isFallbackEnabled()) {
                lastUsage.set(new LlmUsage(0, 0, 0, 0, 0, "", "fallback"));
                return resilienceProperties.getLlm().getFallbackMessage();
            }
            throw toLlmCallException(exception);
        }
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    @Override
    public Flux<String> stream(PromptRequest promptRequest) {
        return streamWithRetry(toSpringPrompt(promptRequest), 1)
                .doOnSubscribe(ignored -> lastFinishReason.remove())
                .doOnNext(response -> {
                    lastUsage.set(extractUsage(response));
                    captureFinishReason(response);
                })
                .map(response -> {
                    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                        return "";
                    }
                    return response.getResult().getOutput().getText();
                })
                .filter(text -> text != null && !text.isBlank())
                .onErrorResume(error -> {
                    if (isContextOverflow(error)) {
                        return Flux.error(contextOverflowException(error));
                    }
                    if (resilienceProperties.getLlm().isFallbackEnabled()) {
                        lastUsage.set(new LlmUsage(0, 0, 0, 0, 0, "", "fallback"));
                        return Flux.just(resilienceProperties.getLlm().getFallbackMessage());
                    }
                    return Flux.error(toLlmCallException(error));
                });
    }

    @Override
    public ChatResponse completeNative(Prompt prompt) {
        try {
            ChatResponse response = callWithRetry(prompt);
            lastUsage.set(extractUsage(response));
            captureFinishReason(response);
            return response;
        }
        catch (RuntimeException exception) {
            if (isContextOverflow(exception)) {
                throw contextOverflowException(exception);
            }
            throw toLlmCallException(exception);
        }
    }

    @Override
    public Flux<ChatResponse> streamNative(Prompt prompt) {
        return streamWithRetry(prompt, 1)
                .doOnSubscribe(ignored -> {
                    lastUsage.remove();
                    lastFinishReason.remove();
                })
                .doOnNext(response -> {
                    lastUsage.set(extractUsage(response));
                    captureFinishReason(response);
                })
                .onErrorMap(error -> isContextOverflow(error)
                        ? contextOverflowException(error)
                        : toLlmCallException(error));
    }

    /**
     * 流式调用只允许在 Provider 尚未返回任何 chunk 时重试。
     * 已经向下游发送部分内容后重新订阅会造成答案和 ToolCall JSON 重复，因此必须直接失败。
     */
    private Flux<ChatResponse> streamWithRetry(Prompt prompt, int attempt) {
        return Flux.defer(() -> {
            assertCircuitClosed();
            AtomicBoolean emitted = new AtomicBoolean(false);
            return requireChatModel().stream(prompt)
                    .timeout(Duration.ofMillis(Math.max(1000, resilienceProperties.getLlm().getTimeoutMillis())))
                    .doOnNext(ignored -> emitted.set(true))
                    .doOnComplete(this::resetCircuit)
                    .onErrorResume(error -> {
                        int maxAttempts = Math.max(1, resilienceProperties.getLlm().getMaxAttempts());
                        if (isContextOverflow(error)) {
                            return Flux.error(error);
                        }
                        if (emitted.get() || attempt >= maxAttempts || isNonRetryable(error)) {
                            recordCircuitFailure();
                            return Flux.error(error);
                        }
                        long backoffMillis = Math.max(0, resilienceProperties.getLlm().getBackoffMillis()) * attempt;
                        Flux<ChatResponse> retry = streamWithRetry(prompt, attempt + 1);
                        return backoffMillis <= 0
                                ? retry
                                : Mono.delay(Duration.ofMillis(backoffMillis)).thenMany(retry);
                    });
        });
    }

    private void resetCircuit() {
        consecutiveFailures.set(0);
        circuitOpenUntilEpochMillis.set(0);
    }

    @Override
    public Optional<LlmUsage> lastUsage() {
        return Optional.ofNullable(lastUsage.get());
    }

    @Override
    public Optional<String> lastFinishReason() {
        return Optional.ofNullable(lastFinishReason.get());
    }

    private void captureFinishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) return;
        String reason = response.getResult().getMetadata().getFinishReason();
        if (reason != null && !reason.isBlank()) lastFinishReason.set(reason.trim());
    }

    private ChatModel requireChatModel() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new LlmCallException(
                    "MODEL_NOT_CONFIGURED",
                    "模型服务未完成配置，请检查 DeepSeek API Key、Spring AI 依赖和模型配置。",
                    new IllegalStateException(MISSING_CHAT_MODEL_MESSAGE)
            );
        }
        return chatModel;
    }

    private LlmCallException toLlmCallException(Throwable exception) {
        if (exception instanceof LlmCallException llmCallException) {
            return llmCallException;
        }
        return new LlmCallException(
                "MODEL_CALL_FAILED",
                "模型服务调用失败，请稍后重试或检查模型服务网络与配置。",
                exception
        );
    }

    /**
     * 构建 Spring 需要的 Prompt
     */
    private Prompt toSpringPrompt(PromptRequest promptRequest) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        if (promptRequest.systemPrompt() != null && !promptRequest.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(promptRequest.systemPrompt()));
        }
        messages.add(new UserMessage(buildUserContent(promptRequest)));
        return new Prompt(messages);
    }

    /**
     * 调用 LLM 并在 LLM 调用失败时进行有限次重试
     */
    private ChatResponse callWithRetry(Prompt prompt) {
        // LLM 调用最大重试次数
        int maxAttempts = Math.max(1, resilienceProperties.getLlm().getMaxAttempts());
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 熔断检测器，如果处于熔断阶段，直接抛异常
            assertCircuitClosed();
            Future<ChatResponse> future = null;
            try {
                // 异步调用是因为要使用 future.get() 的超时控制
                future = callExecutor.submit(() -> requireChatModel().call(prompt));
                // 5 秒到了还没返回 → 抛 TimeoutException → 走重试逻辑
                ChatResponse response = future.get(
                        Math.max(1000, resilienceProperties.getLlm().getTimeoutMillis()),
                        TimeUnit.MILLISECONDS
                );
                consecutiveFailures.set(0);
                circuitOpenUntilEpochMillis.set(0);
                return response;
            }
            catch (TimeoutException exception) {
                // 取消异步调用
                cancelFuture(future);
                lastError = new LlmCallException(
                        "MODEL_TIMEOUT",
                        "模型服务调用超时，请稍后重试。",
                        exception
                );
            }
            catch (InterruptedException exception) {
                cancelFuture(future);
                Thread.currentThread().interrupt();
                throw new LlmCallException(
                        "MODEL_CALL_INTERRUPTED",
                        "模型调用已被取消。",
                        exception
                );
            }
            catch (RejectedExecutionException exception) {
                lastError = new LlmCallException(
                        "MODEL_EXECUTOR_SATURATED",
                        "模型调用队列已满，请稍后重试。",
                        exception
                );
            }
            catch (ExecutionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                if (isContextOverflow(cause)) {
                    throw contextOverflowException(cause);
                }
                lastError = cause instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new RuntimeException(cause);
            }
            if (isNonRetryable(lastError) || attempt >= maxAttempts) {
                break;
            }
            sleepBackoff(attempt);
        }
        // 熔断计数器 +1
        recordCircuitFailure();
        throw lastError == null ? new IllegalStateException("LLM call failed") : lastError;
    }

    private LlmCallException contextOverflowException(Throwable cause) {
        return new LlmCallException(
                "CONTEXT_OVERFLOW",
                "模型上下文超过窗口限制，需要压缩后重试。",
                cause
        );
    }

    private boolean isContextOverflow(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof LlmCallException llmCallException
                    && "CONTEXT_OVERFLOW".equals(llmCallException.errorType())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("context_length_exceeded")
                        || normalized.contains("context window exceeded")
                        || normalized.contains("maximum context length")
                        || normalized.contains("prompt is too long")
                        || normalized.contains("too many tokens")
                        || (normalized.contains("context") && normalized.contains("token limit"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBackoff(int attempt) {
        long base = Math.max(0, resilienceProperties.getLlm().getBackoffMillis());
        if (base <= 0) {
            return;
        }
        try {
            Thread.sleep(base * attempt);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmCallException(
                    "MODEL_CALL_INTERRUPTED",
                    "模型重试等待已被取消。",
                    exception
            );
        }
    }

    /**
     * 熔断检测器，如果处于熔断阶段，直接抛异常
     */
    private void assertCircuitClosed() {
        // 熔断截止时间
        long openUntil = circuitOpenUntilEpochMillis.get();
        if (openUntil > System.currentTimeMillis()) {
            // 熔断中，直接抛异常，不走网络
            throw new LlmCallException(
                    "MODEL_CIRCUIT_OPEN",
                    "模型服务熔断器处于打开状态，请稍后重试。",
                    null
            );
        }
        if (openUntil > 0) {
            // 时间到了，清除标记，允许重试（半开态）
            circuitOpenUntilEpochMillis.compareAndSet(openUntil, 0);
        }
    }

    private void recordCircuitFailure() {
        // 失败阈值
        int threshold = Math.max(1, resilienceProperties.getLlm().getCircuitBreakerFailureThreshold());
        if (consecutiveFailures.incrementAndGet() >= threshold) {
            // 连续失败 N 次 → 开熔断，比如持续 30 秒
            long openMillis = Math.max(1_000, resilienceProperties.getLlm().getCircuitBreakerOpenMillis());
            circuitOpenUntilEpochMillis.set(System.currentTimeMillis() + openMillis);
            consecutiveFailures.set(0);
        }
    }

    private boolean isNonRetryable(Throwable failure) {
        if (failure instanceof LlmCallException llmCallException) {
            return "MODEL_NOT_CONFIGURED".equals(llmCallException.errorType())
                    || "MODEL_CALL_INTERRUPTED".equals(llmCallException.errorType())
                    || "MODEL_EXECUTOR_SATURATED".equals(llmCallException.errorType())
                    || "MODEL_CIRCUIT_OPEN".equals(llmCallException.errorType());
        }
        String message = failure == null || failure.getMessage() == null
                ? ""
                : failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("unauthorized")
                || message.contains("invalid api key")
                || message.contains("authentication")
                || message.contains("status code 400")
                || message.contains("bad request");
    }

    private boolean isInterruptedFailure(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof InterruptedException
                    || current instanceof LlmCallException llmCallException
                    && "MODEL_CALL_INTERRUPTED".equals(llmCallException.errorType())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void cancelFuture(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        callExecutor.shutdownNow();
    }

    /**
     * 从 LLM 返回信息中提取 token 调用额度
     */
    private LlmUsage extractUsage(ChatResponse response) {
        if (response == null) {
            return new LlmUsage(0, 0, 0, 0, 0, "", "spring-ai-empty");
        }
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null) {
            return new LlmUsage(0, 0, 0, 0, 0, metadata == null ? "" : metadata.getModel(), "spring-ai-no-usage");
        }
        long promptTokens = numberValue(usage.getPromptTokens());
        long completionTokens = numberValue(usage.getCompletionTokens());
        long totalTokens = numberValue(usage.getTotalTokens());
        return new LlmUsage(
                promptTokens,
                completionTokens,
                totalTokens,
                longValue(usage.getCacheReadInputTokens()),
                longValue(usage.getCacheWriteInputTokens()),
                metadata.getModel() == null ? "" : metadata.getModel(),
                "provider"
        );
    }

    private long numberValue(Number value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }

    private long longValue(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String buildUserContent(PromptRequest promptRequest) {
        StringBuilder builder = new StringBuilder();
        builder.append(promptRequest.userPrompt() == null ? "" : promptRequest.userPrompt());
        if (!promptRequest.contextBlocks().isEmpty()) {
            builder.append("\n\n可参考上下文：\n");
            for (int index = 0; index < promptRequest.contextBlocks().size(); index++) {
                builder.append(index + 1)
                        .append(". ")
                        .append(promptRequest.contextBlocks().get(index))
                        .append('\n');
            }
        }
        return builder.toString();
    }
}
