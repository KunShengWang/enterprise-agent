package com.agent.platform.llm;

import com.agent.platform.prompt.PromptRequest;
import reactor.core.publisher.Flux;

import java.util.Optional;

public interface LlmService {

    String complete(PromptRequest promptRequest);

    Flux<String> stream(PromptRequest promptRequest);

    default Optional<LlmUsage> lastUsage() {
        return Optional.empty();
    }
}
