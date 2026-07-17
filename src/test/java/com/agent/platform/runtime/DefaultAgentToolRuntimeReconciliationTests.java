package com.agent.platform.runtime;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultAgentToolRuntimeReconciliationTests {

    @Test
    void manualReviewMetadataMustNotBePersistedAsOrdinaryFailure() {
        GuardrailService guardrail = mock(GuardrailService.class);
        ToolExecutionStore store = mock(ToolExecutionStore.class);
        AgentCapabilityExecutor executor = mock(AgentCapabilityExecutor.class);
        when(guardrail.checkToolCall(any(), any(), any()))
                .thenReturn(GuardrailDecision.allow(GuardrailStage.TOOL, "allowed"));
        when(store.claim(anyString(), any())).thenReturn(ToolExecutionClaim.acquired());
        when(executor.execute(any())).thenReturn(new ToolCallResult(
                "floworder_recovery_execute", false, "", "outcome unknown",
                Map.of("retryable", false, "manualReview", true)
        ));
        DefaultAgentToolRuntime runtime = new DefaultAgentToolRuntime(
                guardrail, mock(com.agent.platform.approval.ApprovalService.class), store,
                executor, new AgentProperties(), List.of(), List.of()
        );

        AgentToolRuntimeResult result = runtime.execute(
                "run-1", "session-1", "user-1", Map.of(),
                new AgentToolCall("tool-1", "floworder_recovery_execute", Map.of(), "execute"),
                new ToolDefinition("floworder_recovery_execute", "execute", "{}", ToolRiskLevel.LOW, Map.of())
        );

        assertEquals(AgentToolExecutionStatus.MANUAL_REVIEW, result.status());
        verify(store).markManualReview("tool-1", "outcome unknown");
        verify(store, never()).markFailed(anyString(), any());
    }

    @Test
    void uncertainResolverMustPersistProvenResultForRuntimeRestart() {
        ToolExecutionStore store = mock(ToolExecutionStore.class);
        ToolCallRequest request = new ToolCallRequest(
                "floworder_recovery_execute", "tool-2", Map.of("proposalId", "prop-1")
        );
        ToolExecutionRecord running = ToolExecutionRecord.running("run-2", request);
        ToolCallResult resolved = new ToolCallResult(
                request.toolName(), true, "resolved", "", Map.of("reconciled", true)
        );
        ToolExecutionRecord succeeded = running.withResult(ToolExecutionState.SUCCEEDED, resolved, "");
        UncertainToolExecutionResolver resolver = mock(UncertainToolExecutionResolver.class);
        when(resolver.supports(running)).thenReturn(true);
        when(resolver.resolve(running)).thenReturn(resolved);
        when(store.findToolExecution("tool-2")).thenReturn(Optional.of(succeeded));
        DefaultAgentToolRuntime runtime = new DefaultAgentToolRuntime(
                mock(GuardrailService.class),
                mock(com.agent.platform.approval.ApprovalService.class),
                store,
                mock(AgentCapabilityExecutor.class),
                new AgentProperties(),
                List.of(),
                List.of(resolver)
        );

        ToolExecutionRecord recovered = runtime.reconcileUncertain(running);

        assertEquals(ToolExecutionState.SUCCEEDED, recovered.state());
        verify(store).markSucceeded("tool-2", resolved);
        verify(store, never()).markManualReview(anyString(), anyString());
    }
}
