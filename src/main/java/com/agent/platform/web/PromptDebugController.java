package com.agent.platform.web;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.prompt.PromptDebugResponse;
import com.agent.platform.prompt.PromptDebugService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/agent/debug")
public class PromptDebugController {

    private final PromptDebugService promptDebugService;

    public PromptDebugController(PromptDebugService promptDebugService) {
        this.promptDebugService = promptDebugService;
    }

    @PostMapping("/prompt")
    public Mono<ApiResponse<PromptDebugResponse>> debugPrompt(@Valid @RequestBody AgentRequest request) {
        return Mono.fromSupplier(() -> ApiResponse.success(promptDebugService.debug(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
