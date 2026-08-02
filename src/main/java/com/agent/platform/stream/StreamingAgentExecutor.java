package com.agent.platform.stream;

import com.agent.platform.agent.AgentRequest;
import reactor.core.publisher.Flux;

public interface StreamingAgentExecutor {

    Flux<AgentStreamEvent> stream(AgentRequest request);

    /**
     * agent 执行恢复，流式返回。
     */
    Flux<AgentStreamEvent> resume(String runId);
}
