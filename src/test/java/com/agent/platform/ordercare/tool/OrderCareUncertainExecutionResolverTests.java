package com.agent.platform.ordercare.tool;

import com.agent.platform.ordercare.application.OrderCareProposalBinding;
import com.agent.platform.ordercare.application.OrderCareProposalBindingStore;
import com.agent.platform.ordercare.application.RecoveryOutcomeReconciler;
import com.agent.platform.ordercare.model.OrderCareRecoveryReconciliationResult;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.tool.ToolCallRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static com.agent.platform.ordercare.OrderCareTestFixtures.proposal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderCareUncertainExecutionResolverTests {

    @Test
    void crashRecoveryMustRestoreResultWithOriginalCorrelationIds() {
        var preview = proposal("APPROVED", "EXECUTING", "NOT_CONVERGED", false);
        OrderCareProposalBindingStore bindings = mock(OrderCareProposalBindingStore.class);
        RecoveryOutcomeReconciler reconciler = mock(RecoveryOutcomeReconciler.class);
        ToolCallRequest request = new ToolCallRequest(
                OrderCareToolCatalog.RECOVERY_EXECUTE,
                "tool-exec-1",
                Map.ofEntries(
                        Map.entry("proposalId", preview.proposalId()),
                        Map.entry("proposalVersion", preview.proposalVersion()),
                        Map.entry("stateFingerprint", preview.stateFingerprint()),
                        Map.entry("effectsDigest", preview.effectsDigest()),
                        Map.entry("warningsDigest", preview.warningsDigest()),
                        Map.entry("previewDigest", preview.previewDigest()),
                        Map.entry("approvalId", "approval-1"),
                        Map.entry("approvedBy", "reviewer-1"),
                        Map.entry("approvalComment", "approved")
                )
        );
        ToolExecutionRecord execution = ToolExecutionRecord.running("run-1", request);
        when(bindings.requireForRun(preview.proposalId(), "run-1"))
                .thenReturn(new OrderCareProposalBinding(
                        preview.proposalId(), preview.actionRequestId(), preview.caseKey(),
                        "preview-tool", "run-1", preview, Instant.now()
                ));
        when(reconciler.reconcile(any(), any(), eq("tool-exec-1"), eq("tool-exec-1"), eq(true)))
                .thenReturn(new OrderCareRecoveryReconciliationResult(
                        "RESOLVED", 2, true, false, null, null
                ));
        OrderCareUncertainExecutionResolver resolver = new OrderCareUncertainExecutionResolver(
                bindings, reconciler, new ObjectMapper()
        );

        var result = resolver.resolve(execution);

        assertTrue(result.success());
        assertEquals(true, result.metadata().get("recoveredAfterCrash"));
        assertEquals(preview.proposalId(), result.metadata().get("proposalId"));
        assertEquals(preview.actionRequestId(), result.metadata().get("actionRequestId"));
        assertEquals("approval-1", result.metadata().get("approvalId"));
    }
}
