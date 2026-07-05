package com.agent.platform.web;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.AgentResponse;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.config.AgentProperties;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

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
}
