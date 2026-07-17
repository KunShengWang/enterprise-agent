package com.agent.platform.ordercare.tool;

import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.ordercare.application.OrderCareProposalBinding;
import com.agent.platform.ordercare.application.OrderCareProposalBindingStore;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.tool.ToolCallRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static com.agent.platform.ordercare.OrderCareTestFixtures.proposal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderCareApprovalRequestPreparerTests {

    @Test
    void replacesModelArgumentsWithServerOwnedImmutablePreviewSnapshot() {
        OrderCareProposalBindingStore bindingStore = mock(OrderCareProposalBindingStore.class);
        FlowOrderClient client = mock(FlowOrderClient.class);
        OrderCareRecoveryProposal proposal = proposal("ACTIVE", "NOT_STARTED", "NOT_CONVERGED", true);
        OrderCareProposalBinding binding = new OrderCareProposalBinding(
                proposal.proposalId(), proposal.actionRequestId(), proposal.caseKey(),
                "preview-tool-1", "run-1", proposal, Instant.now()
        );
        when(bindingStore.requireForRun(proposal.proposalId(), "run-1")).thenReturn(binding);
        when(client.getProposal(proposal.proposalId(), "execute-tool-1")).thenReturn(proposal);
        OrderCareApprovalRequestPreparer preparer = new OrderCareApprovalRequestPreparer(bindingStore, client);
        ToolCallRequest modelRequest = new ToolCallRequest(
                OrderCareToolCatalog.RECOVERY_EXECUTE,
                "execute-tool-1",
                Map.of("proposalId", proposal.proposalId(), "stateFingerprint", "model-tampered")
        );

        ToolCallRequest prepared = preparer.prepare(
                "approval-1",
                modelRequest,
                new ToolPolicyContext("run-1", "session-1", "user-1", "tenant-1", Set.of(), Map.of())
        );

        assertEquals(proposal.stateFingerprint(), prepared.arguments().get("stateFingerprint"));
        assertEquals(proposal.previewDigest(), prepared.arguments().get("previewDigest"));
        assertEquals(proposal.effects(), prepared.arguments().get("effects"));
        assertEquals("preview-tool-1", prepared.arguments().get("immutablePreviewSnapshotRef"));
        assertEquals("approval-1", prepared.arguments().get("approvalId"));
        assertFalse(prepared.arguments().containsKey("force"));
    }
}
