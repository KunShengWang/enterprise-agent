package com.agent.platform.web;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.multiagent.MultiAgentOrchestrator;
import com.agent.platform.multiagent.MultiAgentRunResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/agent/multi-agent")
public class MultiAgentController {

    private final MultiAgentOrchestrator multiAgentOrchestrator;

    public MultiAgentController(MultiAgentOrchestrator multiAgentOrchestrator) {
        this.multiAgentOrchestrator = multiAgentOrchestrator;
    }

    @PostMapping("/runs")
    public Mono<ApiResponse<MultiAgentRunResponse>> run(@Valid @RequestBody AgentRequest request) {
        return Mono.error(new IllegalArgumentException(
                "public Multi-Agent execution is disabled; use /api/agent/runs for procurement"));
    }
}
