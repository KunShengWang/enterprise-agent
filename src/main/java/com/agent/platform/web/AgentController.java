package com.agent.platform.web;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.AgentResponse;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.config.AgentProperties;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentExecutor agentExecutor;

    private final AgentProperties agentProperties;

    public AgentController(AgentExecutor agentExecutor, AgentProperties agentProperties) {
        this.agentExecutor = agentExecutor;
        this.agentProperties = agentProperties;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(Map.of(
                "name", "enterprise-agent",
                "stage", "V0",
                "mockMode", agentProperties.isMockMode()
        ));
    }

    @PostMapping("/runs")
    public Mono<ApiResponse<AgentResponse>> run(@Valid @RequestBody AgentRequest request) {
        return Mono.fromSupplier(() -> ApiResponse.success(agentExecutor.execute(request)));
    }

    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody AgentRequest request) {
        return Mono.fromSupplier(() -> agentExecutor.execute(request))
                // 把 stream 转为 Flux
                .flatMapMany(response -> Flux.fromStream(Stream.concat(// 把两个流首尾拼接
                        response.steps().stream().map(step -> "step: " + step.name() + " [" + step.status() + "] " + step.summary()),
                        Stream.of("answer: " + response.answer(), "traceId: " + response.trace().traceId())
                )));
    }
}
