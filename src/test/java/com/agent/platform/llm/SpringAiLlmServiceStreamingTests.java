package com.agent.platform.llm;

import com.agent.platform.config.ResilienceProperties;
import com.agent.platform.prompt.PromptRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiLlmServiceStreamingTests {

    @Test
    void retriesWhenProviderFailsBeforeTheFirstChunk() {
        ChatModel chatModel = mock(ChatModel.class);
        ObjectProvider<ChatModel> provider = provider(chatModel);
        ResilienceProperties properties = properties();
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new IllegalStateException("connect failed")), Flux.empty());
        SpringAiLlmService service = new SpringAiLlmService(provider, properties);

        try {
            List<String> chunks = service.stream(prompt()).collectList().block(Duration.ofSeconds(2));

            assertTrue(chunks.isEmpty());
            verify(chatModel, times(2)).stream(any(Prompt.class));
        }
        finally {
            service.shutdownExecutor();
        }
    }

    @Test
    void doesNotRetryAfterProviderHasEmittedAChunk() {
        ChatModel chatModel = mock(ChatModel.class);
        ObjectProvider<ChatModel> provider = provider(chatModel);
        ResilienceProperties properties = properties();
        ChatResponse partial = mock(ChatResponse.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(partial),
                Flux.error(new IllegalStateException("connection lost after partial response"))
        ));
        SpringAiLlmService service = new SpringAiLlmService(provider, properties);

        try {
            assertThrows(LlmCallException.class,
                    () -> service.stream(prompt()).collectList().block(Duration.ofSeconds(2)));
            verify(chatModel, times(1)).stream(any(Prompt.class));
        }
        finally {
            service.shutdownExecutor();
        }
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatModel> provider(ChatModel chatModel) {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        return provider;
    }

    private ResilienceProperties properties() {
        ResilienceProperties properties = new ResilienceProperties();
        properties.getLlm().setMaxAttempts(3);
        properties.getLlm().setBackoffMillis(0);
        properties.getLlm().setTimeoutMillis(1_000);
        properties.getLlm().setFallbackEnabled(false);
        return properties;
    }

    private PromptRequest prompt() {
        return new PromptRequest("system", "question", List.of(), Map.of());
    }
}
