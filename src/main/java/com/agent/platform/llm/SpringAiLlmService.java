package com.agent.platform.llm;

import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.config.ResilienceProperties;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(prefix = "enterprise-agent", name = "mock-mode", havingValue = "false", matchIfMissing = true)
public class SpringAiLlmService implements LlmService {

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

    public SpringAiLlmService(ObjectProvider<ChatModel> chatModelProvider,
                              ResilienceProperties resilienceProperties) {
        this.chatModelProvider = chatModelProvider;
        this.resilienceProperties = resilienceProperties;
    }

    @Override
    public String complete(PromptRequest promptRequest) {
        ChatResponse response;
        try {
            // 调用 LLM 并在 LLM 调用失败时进行有限次重试
            response = callWithRetry(toSpringPrompt(promptRequest));
            // 从 LLM 返回信息中提取 token 调用额度并保存
            lastUsage.set(extractUsage(response));
        }
        catch (RuntimeException exception) {
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
        ChatModel chatModel = requireChatModel();
        return chatModel.stream(toSpringPrompt(promptRequest))
                .timeout(Duration.ofMillis(Math.max(1000, resilienceProperties.getLlm().getTimeoutMillis())))
                .retry(Math.max(0, resilienceProperties.getLlm().getMaxAttempts() - 1))
                .doOnNext(response -> lastUsage.set(extractUsage(response)))
                .map(response -> {
                    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                        return "";
                    }
                    return response.getResult().getOutput().getText();
                })
                .filter(text -> text != null && !text.isBlank())
                .onErrorResume(error -> {
                    if (resilienceProperties.getLlm().isFallbackEnabled()) {
                        lastUsage.set(new LlmUsage(0, 0, 0, 0, 0, "", "fallback"));
                        return Flux.just(resilienceProperties.getLlm().getFallbackMessage());
                    }
                    return Flux.error(toLlmCallException(error));
                });
    }

    @Override
    public Optional<LlmUsage> lastUsage() {
        return Optional.ofNullable(lastUsage.get());
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
        int maxAttempts = Math.max(1, resilienceProperties.getLlm().getMaxAttempts());
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return CompletableFuture
                        .supplyAsync(() -> requireChatModel().call(prompt))
                        .get(Math.max(1000, resilienceProperties.getLlm().getTimeoutMillis()), TimeUnit.MILLISECONDS);
            }
            catch (Exception exception) {
                lastError = exception instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new RuntimeException(exception);
                sleepBackoff(attempt);
            }
        }
        throw lastError == null ? new IllegalStateException("LLM call failed") : lastError;
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
        }
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
