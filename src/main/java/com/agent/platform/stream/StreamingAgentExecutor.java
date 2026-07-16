package com.agent.platform.stream;

import com.agent.platform.agent.AgentRequest;
import reactor.core.publisher.Flux;

public interface StreamingAgentExecutor {

    Flux<AgentStreamEvent> stream(AgentRequest request);

    Flux<AgentStreamEvent> resume(String runId);
}
