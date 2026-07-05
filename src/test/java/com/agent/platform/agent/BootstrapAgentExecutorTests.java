package com.agent.platform.agent;

import com.agent.platform.trace.InMemoryTraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapAgentExecutorTests {

    @Test
    void executeShouldReturnV0SkeletonResponseWithTrace() {
        BootstrapAgentExecutor executor = new BootstrapAgentExecutor(new InMemoryTraceRecorder());

        AgentResponse response = executor.execute(new AgentRequest(
                "conversation-1",
                "user-1",
                "query ticket T1001",
                Map.of()
        ));

        assertThat(response.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(response.answer()).contains("V0 agent skeleton is ready");
        assertThat(response.steps()).extracting(AgentStep::name)
                .contains("memory.load", "guardrail.input", "intent.route", "rag.or.tool", "llm.call", "eval.record");
        assertThat(response.trace().traceId()).isNotBlank();
        assertThat(response.trace().events()).isNotEmpty();
    }
}
