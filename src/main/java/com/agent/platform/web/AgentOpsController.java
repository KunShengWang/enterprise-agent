package com.agent.platform.web;

import com.agent.platform.agentops.AgentOpsEvidence;
import com.agent.platform.agentops.AgentOpsService;
import com.agent.platform.agentops.AgentOpsSummary;
import com.agent.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/agent/ops")
public class AgentOpsController {

    private final AgentOpsService agentOpsService;

    public AgentOpsController(AgentOpsService agentOpsService) {
        this.agentOpsService = agentOpsService;
    }

    @GetMapping("/summary")
    public Mono<ApiResponse<AgentOpsSummary>> summary(@RequestParam(defaultValue = "100") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(agentOpsService.summary(limit)));
    }

    @GetMapping("/evidence")
    public Mono<ApiResponse<AgentOpsEvidence>> evidence(@RequestParam(defaultValue = "20") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(agentOpsService.evidence(limit)));
    }
}
