package com.agent.platform.approval;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import com.agent.platform.tool.ToolCallRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcApprovalStoreTests {

    @Test
    void transitionUsesConditionalStatusUpdateInsteadOfUnconditionalSave() {
        JdbcAgentStoreSupport support = mock(JdbcAgentStoreSupport.class);
        JdbcApprovalStore store = new JdbcApprovalStore(support);
        Instant now = Instant.now();
        ApprovalRecord decided = new ApprovalRecord(
                "approval-1", "run-1", "session-1",
                new ToolCallRequest("ticket_close", "execution-1", Map.of()),
                "high risk", ApprovalStatus.APPROVED, "reviewer", "approved",
                now.minusSeconds(10), now.plusSeconds(60), now
        );
        when(support.updateIfJsonFieldEqualsAndInstantAfter(
                "approval", "approval-1", "status", "REQUESTED", "expiresAt", now, decided, now
        )).thenReturn(true);

        boolean transitioned = store.decideIfRequestedAndNotExpired("approval-1", decided, now);

        assertTrue(transitioned);
        verify(support).updateIfJsonFieldEqualsAndInstantAfter(
                "approval", "approval-1", "status", "REQUESTED", "expiresAt", now, decided, now
        );
    }

    @Test
    void expiryTransitionRequiresRequestedStatusAndElapsedExpiry() {
        JdbcAgentStoreSupport support = mock(JdbcAgentStoreSupport.class);
        JdbcApprovalStore store = new JdbcApprovalStore(support);
        Instant now = Instant.now();
        ApprovalRecord expired = new ApprovalRecord(
                "approval-1", "run-1", "session-1",
                new ToolCallRequest("ticket_close", "execution-1", Map.of()),
                "high risk", ApprovalStatus.EXPIRED, "system", "approval expired",
                now.minusSeconds(120), now.minusSeconds(60), now
        );
        when(support.updateIfJsonFieldEqualsAndInstantAtOrBefore(
                "approval", "approval-1", "status", "REQUESTED", "expiresAt", now, expired, now
        )).thenReturn(true);

        boolean transitioned = store.expireIfRequested("approval-1", expired, now);

        assertTrue(transitioned);
        verify(support).updateIfJsonFieldEqualsAndInstantAtOrBefore(
                "approval", "approval-1", "status", "REQUESTED", "expiresAt", now, expired, now
        );
    }
}
