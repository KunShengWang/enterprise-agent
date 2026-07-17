package com.agent.platform.ordercare.application;

import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.model.*;
import org.junit.jupiter.api.Test;

import static com.agent.platform.ordercare.OrderCareTestFixtures.proposal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecoveryOutcomeReconcilerTests {

    @Test
    void responseLostAfterSubmissionMustOnlyQueryOriginalAction() {
        FlowOrderClient client = mock(FlowOrderClient.class);
        RecoveryConvergenceChecker checker = mock(RecoveryConvergenceChecker.class);
        OrderCareRecoveryProposal preview = proposal("ACTIVE", "NOT_STARTED", "NOT_CONVERGED", true);
        when(client.getAction(preview.actionRequestId(), "trace-1"))
                .thenReturn(action(preview, "SUBMITTED", "RESOLVED", "RESOLVED"));
        when(checker.await(preview.proposalId(), "trace-1")).thenReturn(converged(preview));

        var result = reconciler(client, checker, 3).reconcile(
                preview, command(preview), "tool-1", "trace-1", true
        );

        assertEquals("RESOLVED", result.status());
        assertFalse(result.executeReissuedWithSameId());
        verify(client, never()).executeProposal(any(), anyString());
        verify(client, never()).reconcileAction(anyString(), any(), anyString());
    }

    @Test
    void notStartedMustReissueExactApprovedCommandOnlyOnce() {
        FlowOrderClient client = mock(FlowOrderClient.class);
        RecoveryConvergenceChecker checker = mock(RecoveryConvergenceChecker.class);
        OrderCareRecoveryProposal preview = proposal("ACTIVE", "NOT_STARTED", "NOT_CONVERGED", true);
        when(client.getAction(preview.actionRequestId(), "trace-2"))
                .thenReturn(action(preview, "NOT_STARTED", "NOT_CONVERGED", "NOT_STARTED"))
                .thenReturn(action(preview, "SUBMITTED", "RESOLVED", "RESOLVED"));
        when(client.executeProposal(any(), eq("trace-2"))).thenThrow(new FlowOrderApiException(
                "second response also lost", 0, false, true, null
        ));
        when(checker.await(preview.proposalId(), "trace-2")).thenReturn(converged(preview));
        OrderCareProposalExecuteCommand command = command(preview);

        var result = reconciler(client, checker, 3).reconcile(
                preview, command, "tool-2", "trace-2", true
        );

        assertEquals("RESOLVED", result.status());
        assertTrue(result.executeReissuedWithSameId());
        verify(client, times(1)).executeProposal(same(command), eq("trace-2"));
        assertEquals(preview.actionRequestId(), result.action().actionRequestId());
    }

    @Test
    void expiredExecutingLeaseMustUseReconciliationEndpoint() {
        FlowOrderClient client = mock(FlowOrderClient.class);
        RecoveryConvergenceChecker checker = mock(RecoveryConvergenceChecker.class);
        OrderCareRecoveryProposal preview = proposal("APPROVED", "EXECUTING", "NOT_CONVERGED", false);
        when(client.getAction(preview.actionRequestId(), "trace-3"))
                .thenReturn(action(preview, "EXECUTING", "NOT_CONVERGED", "WAITING_EXECUTION"));
        when(client.reconcileAction(eq(preview.actionRequestId()), any(), eq("trace-3")))
                .thenReturn(action(preview, "SUBMITTED", "RESOLVED", "RESOLVED"));
        when(checker.await(preview.proposalId(), "trace-3")).thenReturn(converged(preview));

        var result = reconciler(client, checker, 2).reconcile(
                preview, command(preview), "tool-3", "trace-3", true
        );

        assertEquals("RESOLVED", result.status());
        verify(client).reconcileAction(eq(preview.actionRequestId()),
                argThat(value -> "tool-3".equals(value.executionOwner())), eq("trace-3"));
        verify(client, never()).executeProposal(any(), anyString());
    }

    @Test
    void repeatedUnknownMustEndInManualReviewWithoutNewActionId() {
        FlowOrderClient client = mock(FlowOrderClient.class);
        RecoveryConvergenceChecker checker = mock(RecoveryConvergenceChecker.class);
        OrderCareRecoveryProposal preview = proposal("APPROVED", "EXECUTING", "NOT_CONVERGED", false);
        when(client.getAction(preview.actionRequestId(), "trace-4"))
                .thenReturn(action(preview, "EXECUTING", "NOT_CONVERGED", "WAITING_EXECUTION"));
        when(client.reconcileAction(eq(preview.actionRequestId()), any(), eq("trace-4")))
                .thenThrow(new FlowOrderApiException("lost", 0, false, true, null));

        var result = reconciler(client, checker, 2).reconcile(
                preview, command(preview), "tool-4", "trace-4", true
        );

        assertEquals("UNKNOWN", result.status());
        assertFalse(result.executeReissuedWithSameId());
        verify(client, times(2)).reconcileAction(eq(preview.actionRequestId()), any(), eq("trace-4"));
    }

    private RecoveryOutcomeReconciler reconciler(FlowOrderClient client,
                                                  RecoveryConvergenceChecker checker,
                                                  int attempts) {
        OrderCareProperties properties = new OrderCareProperties();
        properties.setReconciliationMaxAttempts(attempts);
        properties.setReconciliationIntervalMillis(0);
        return new RecoveryOutcomeReconciler(client, checker, properties);
    }

    private OrderCareProposalExecuteCommand command(OrderCareRecoveryProposal proposal) {
        return new OrderCareProposalExecuteCommand(
                proposal.proposalId(), proposal.proposalVersion(), proposal.stateFingerprint(),
                proposal.effectsDigest(), proposal.warningsDigest(), proposal.previewDigest(),
                "approval-1", "reviewer-1", "approved", "tool-exec-1"
        );
    }

    private OrderCareRecoveryAction action(OrderCareRecoveryProposal proposal,
                                           String actionStatus,
                                           String caseOutcome,
                                           String reconciliationStatus) {
        return new OrderCareRecoveryAction(
                "floworder-recovery-action-v1", proposal.proposalId(), proposal.actionRequestId(),
                "REPLAY", "DEAD_LETTER", proposal.targetKey(), actionStatus, caseOutcome,
                reconciliationStatus, "worker", null, null, false, 0,
                "", "", null, null, null
        );
    }

    private OrderCareConvergenceResult converged(OrderCareRecoveryProposal proposal) {
        return new OrderCareConvergenceResult(
                proposal.proposalId(), "RESOLVED", 1, "APPROVED", "SUBMITTED", "RESOLVED",
                true, true, true
        );
    }
}
