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
        when(support.updateIfJsonFieldEquals(
                "approval", "approval-1", "status", "REQUESTED", decided, now
        )).thenReturn(true);

        boolean transitioned = store.transition("approval-1", ApprovalStatus.REQUESTED, decided);

        assertTrue(transitioned);
        verify(support).updateIfJsonFieldEquals(
                "approval", "approval-1", "status", "REQUESTED", decided, now
        );
    }
}
