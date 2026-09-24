package com.agent.platform.agent;

import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.config.InternalTestProfileFactory;
import com.agent.platform.config.AgentScenarioProfileResolver;
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

    @ParameterizedTest
    @ValueSource(strings = { "internal-test-v1", "procurement-sourcing-rfq-v1" })
    void migratedResolverPassesUnchangedInternalAndProcurementProfilesToRuntime(String scenarioId) {
        var resolver = new AgentScenarioProfileResolver(List.of(
                new InternalTestProfileFactory(),
                new ProcurementSourcingExecutionProfileFactory()));
        var runtime = mock(AgentRuntime.class);
        var profile = resolver.resolve(scenarioId).orElseThrow();
        var request = new AgentRequest("session", "user", "question", Map.of(), scenarioId);
        when(runtime.run(request, profile, AgentEventListener.NOOP)).thenReturn(new AgentRuntimeResult(
                "run", "session", AgentRunState.COMPLETED, AgentStopReason.COMPLETED,
                "done", "", null, List.of()));

        assertEquals("done", new RuntimeAgentExecutor(runtime, resolver).execute(request).answer());
        verify(runtime).run(request, profile, AgentEventListener.NOOP);
    }

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
