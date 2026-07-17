package com.agent.platform.runtime;

import com.agent.platform.approval.ApprovalRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAgentToolRuntimeApprovalBindingTests {

    @Test
    void persistsPreparedServerSnapshotAsTheOriginalApprovedToolCall() {
        GuardrailService guardrail = mock(GuardrailService.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ToolExecutionStore toolStore = mock(ToolExecutionStore.class);
        AgentCapabilityExecutor capabilityExecutor = mock(AgentCapabilityExecutor.class);
        when(guardrail.checkToolCall(any(), any(), any())).thenReturn(
                GuardrailDecision.requireApproval(GuardrailStage.TOOL, "high risk")
        );
        ApprovalToolCallRequestPreparer preparer = new ApprovalToolCallRequestPreparer() {
            @Override
            public boolean supports(String toolName) {
                return "floworder_recovery_execute".equals(toolName);
            }

            @Override
            public ToolCallRequest prepare(String approvalId,
                                           ToolCallRequest request,
                                           ToolPolicyContext context) {
                Map<String, Object> trusted = new LinkedHashMap<>();
                trusted.put("proposalId", request.arguments().get("proposalId"));
                trusted.put("stateFingerprint", "server-fingerprint");
                trusted.put("previewDigest", "server-preview-digest");
                trusted.put("approvalId", approvalId);
                return new ToolCallRequest(request.toolName(), request.requestId(), trusted);
            }
        };
        DefaultAgentToolRuntime runtime = new DefaultAgentToolRuntime(
                guardrail,
                approvalService,
                toolStore,
                capabilityExecutor,
                new AgentProperties(),
                List.of(preparer)
        );
        AgentToolCall call = new AgentToolCall(
                "tool-call-1",
                "floworder_recovery_execute",
                Map.of("proposalId", "prop-1", "stateFingerprint", "model-tampered"),
                "execute"
        );
        ToolDefinition definition = new ToolDefinition(
                "floworder_recovery_execute", "execute", "{}", ToolRiskLevel.HIGH, Map.of()
        );

        AgentToolRuntimeResult result = runtime.execute(
                "run-1", "session-1", "user-1", Map.of(), call, definition
        );

        assertEquals(AgentToolExecutionStatus.WAITING_APPROVAL, result.status());
        assertEquals("server-fingerprint", result.request().arguments().get("stateFingerprint"));
        assertFalse(result.request().arguments().containsValue("model-tampered"));
        ArgumentCaptor<ApprovalRequest> approval = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(approvalService).requestApproval(approval.capture());
        assertEquals(result.request(), approval.getValue().toolCallRequest());
        assertEquals(result.approvalId(), approval.getValue().toolCallRequest().arguments().get("approvalId"));
    }
}
