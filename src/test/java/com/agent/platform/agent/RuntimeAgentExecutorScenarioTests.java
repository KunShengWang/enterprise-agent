package com.agent.platform.agent;

import com.agent.platform.ordercare.config.AgentScenarioProfileResolver;
import com.agent.platform.runtime.AgentEventListener;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentRunLimits;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentStopReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentExecutorScenarioTests {

    @Test
    void resolvesTrustedProfileBeforeEnteringSharedRuntime() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentScenarioProfileResolver resolver = mock(AgentScenarioProfileResolver.class);
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "ordercare-floworder-v1",
                "prompt",
                Set.of("floworder_case_inspect"),
                new AgentRunLimits(4, 3, 2, 8_000, 1_000, 0.1, 30_000),
                false
        );
        AgentRequest request = new AgentRequest(
                "session-1",
                "user-1",
                "诊断 request-1",
                Map.of(),
                "ordercare-floworder-v1"
        );
        when(resolver.resolve("ordercare-floworder-v1")).thenReturn(Optional.of(profile));
        when(runtime.run(eq(request), eq(profile), eq(AgentEventListener.NOOP)))
                .thenReturn(new AgentRuntimeResult(
                        "run-1", "session-1", AgentRunState.COMPLETED, AgentStopReason.COMPLETED,
                        "diagnosed", "", null, List.of()
                ));

        AgentResponse response = new RuntimeAgentExecutor(runtime, resolver).execute(request);

        assertEquals("diagnosed", response.answer());
        verify(runtime).run(eq(request), eq(profile), eq(AgentEventListener.NOOP));
    }
}
