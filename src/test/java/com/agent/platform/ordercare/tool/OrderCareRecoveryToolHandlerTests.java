package com.agent.platform.ordercare.tool;

import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.ordercare.application.OrderCareProposalBinding;
import com.agent.platform.ordercare.application.OrderCareProposalBindingStore;
import com.agent.platform.ordercare.application.RecoveryConvergenceChecker;
import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.model.OrderCareConvergenceResult;
import com.agent.platform.ordercare.model.OrderCareProposalCreateCommand;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static com.agent.platform.ordercare.OrderCareTestFixtures.proposal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCareRecoveryToolHandlerTests {

    @Test
    void previewGeneratesStableProposalIdAndPersistsRunBinding() {
        FlowOrderClient client = mock(FlowOrderClient.class);
        ToolExecutionStore toolStore = mock(ToolExecutionStore.class);
        OrderCareProposalBindingStore bindingStore = mock(OrderCareProposalBindingStore.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        RecoveryConvergenceChecker checker = mock(RecoveryConvergenceChecker.class);
        ToolCallRequest request = new ToolCallRequest(
                OrderCareToolCatalog.RECOVERY_PREVIEW,
                "preview-tool-1",
                Map.of(
                        "identifierType", "REQUEST_ID",
                        "identifierValue", "req-1",
                        "suggestedReason", "diagnosis supports replay"
                )
        );
        when(toolStore.findToolExecution("preview-tool-1"))
                .thenReturn(Optional.of(ToolExecutionRecord.running("run-1", request)));
        when(client.createProposal(any(), eq("preview-tool-1")))
                .thenReturn(proposal("ACTIVE", "NOT_STARTED", "NOT_CONVERGED", true));
        OrderCareRecoveryToolHandler handler = handler(
                client, toolStore, bindingStore, approvalService, checker
        );

        ToolCallResult result = handler.execute(request);

        assertTrue(result.success());
        assertEquals("ACTIVE", result.metadata().get("proposalStatus"));
        ArgumentCaptor<OrderCareProposalCreateCommand> command =
                ArgumentCaptor.forClass(OrderCareProposalCreateCommand.class);
        verify(client).createProposal(command.capture(), eq("preview-tool-1"));
        assertTrue(command.getValue().proposalId().startsWith("prop-"));
        assertEquals("REPLAY", command.getValue().actionType());
        ArgumentCaptor<OrderCareProposalBinding> binding =
                ArgumentCaptor.forClass(OrderCareProposalBinding.class);
        verify(bindingStore).bind(binding.capture());
        assertEquals("run-1", binding.getValue().runId());
        assertEquals("preview-tool-1", binding.getValue().previewToolExecutionId());
    }

    @Test
    void approvedExecuteUsesHumanIdentityAndReturnsDeterministicConvergence() {
        FlowOrderClient client = mock(FlowOrderClient.class);
        ToolExecutionStore toolStore = mock(ToolExecutionStore.class);
        OrderCareProposalBindingStore bindingStore = mock(OrderCareProposalBindingStore.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        RecoveryConvergenceChecker checker = mock(RecoveryConvergenceChecker.class);
        OrderCareRecoveryProposal preview = proposal("ACTIVE", "NOT_STARTED", "NOT_CONVERGED", true);
        ToolCallRequest request = approvedExecuteRequest(preview);
        when(toolStore.findToolExecution("execute-tool-1"))
                .thenReturn(Optional.of(ToolExecutionRecord.running("run-1", request)));
        when(bindingStore.requireForRun(preview.proposalId(), "run-1"))
                .thenReturn(new OrderCareProposalBinding(
                        preview.proposalId(), preview.actionRequestId(), preview.caseKey(),
                        "preview-tool-1", "run-1", preview, Instant.now()
                ));
        when(approvalService.find("approval-1")).thenReturn(Optional.of(new ApprovalRecord(
                "approval-1", "run-1", "session-1", request, "high risk",
                ApprovalStatus.APPROVED, "reviewer-1", "approved after checking effects",
                Instant.now(), Instant.now()
        )));
        when(client.executeProposal(any(), eq("execute-tool-1")))
                .thenReturn(proposal("APPROVED", "SUBMITTED", "NOT_CONVERGED", false));
        when(checker.await(preview.proposalId(), "execute-tool-1"))
                .thenReturn(new OrderCareConvergenceResult(
                        preview.proposalId(), "RESOLVED", 3, "APPROVED", "SUBMITTED", "RESOLVED",
                        true, true, true
                ));
        OrderCareRecoveryToolHandler handler = handler(
                client, toolStore, bindingStore, approvalService, checker
        );

        ToolCallResult result = handler.execute(request);

        assertTrue(result.success());
        assertEquals("RESOLVED", result.metadata().get("convergenceStatus"));
        assertEquals(false, result.metadata().get("retryable"));
        ArgumentCaptor<OrderCareProposalExecuteCommand> command =
                ArgumentCaptor.forClass(OrderCareProposalExecuteCommand.class);
        verify(client).executeProposal(command.capture(), eq("execute-tool-1"));
        assertEquals("reviewer-1", command.getValue().approvedBy());
        assertEquals("approved after checking effects", command.getValue().approvalComment());
        assertEquals(preview.previewDigest(), command.getValue().previewDigest());
    }

    @Test
    void executeTimeoutIsNeverMarkedRetryable() {
        FlowOrderClient client = mock(FlowOrderClient.class);
        ToolExecutionStore toolStore = mock(ToolExecutionStore.class);
        OrderCareProposalBindingStore bindingStore = mock(OrderCareProposalBindingStore.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        RecoveryConvergenceChecker checker = mock(RecoveryConvergenceChecker.class);
        OrderCareRecoveryProposal preview = proposal("ACTIVE", "NOT_STARTED", "NOT_CONVERGED", true);
        ToolCallRequest request = approvedExecuteRequest(preview);
        when(toolStore.findToolExecution("execute-tool-1"))
                .thenReturn(Optional.of(ToolExecutionRecord.running("run-1", request)));
        when(bindingStore.requireForRun(preview.proposalId(), "run-1"))
                .thenReturn(new OrderCareProposalBinding(
                        preview.proposalId(), preview.actionRequestId(), preview.caseKey(),
                        "preview-tool-1", "run-1", preview, Instant.now()
                ));
        when(approvalService.find("approval-1")).thenReturn(Optional.of(new ApprovalRecord(
                "approval-1", "run-1", "session-1", request, "high risk",
                ApprovalStatus.APPROVED, "reviewer-1", "approved",
                Instant.now(), Instant.now()
        )));
        when(client.executeProposal(any(), eq("execute-tool-1"))).thenThrow(new FlowOrderApiException(
                "response lost", 0, false, true, null
        ));
        OrderCareRecoveryToolHandler handler = handler(
                client, toolStore, bindingStore, approvalService, checker
        );

        ToolCallResult result = handler.execute(request);

        assertFalse(result.success());
        assertEquals(false, result.metadata().get("retryable"));
        assertEquals("UNKNOWN", result.metadata().get("outcome"));
        assertEquals(true, result.metadata().get("manualReview"));
    }

    private OrderCareRecoveryToolHandler handler(FlowOrderClient client,
                                                 ToolExecutionStore toolStore,
                                                 OrderCareProposalBindingStore bindingStore,
                                                 ApprovalService approvalService,
                                                 RecoveryConvergenceChecker checker) {
        return new OrderCareRecoveryToolHandler(
                client, toolStore, bindingStore, approvalService, checker, new ObjectMapper()
        );
    }

    private ToolCallRequest approvedExecuteRequest(OrderCareRecoveryProposal preview) {
        return new ToolCallRequest(
                OrderCareToolCatalog.RECOVERY_EXECUTE,
                "execute-tool-1",
                Map.ofEntries(
                        Map.entry("proposalId", preview.proposalId()),
                        Map.entry("proposalVersion", preview.proposalVersion()),
                        Map.entry("stateFingerprint", preview.stateFingerprint()),
                        Map.entry("effectsDigest", preview.effectsDigest()),
                        Map.entry("warningsDigest", preview.warningsDigest()),
                        Map.entry("previewDigest", preview.previewDigest()),
                        Map.entry("approvalId", "approval-1")
                )
        );
    }
}
