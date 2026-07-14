package com.agent.platform.web;

import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.guardrail.GuardrailAuditRecorder;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.tool.ToolCallRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardrailControllerTests {

    @Test
    void approvalQueriesUseServiceManagedLifecycle() {
        ApprovalService approvalService = mock(ApprovalService.class);
        GuardrailController controller = new GuardrailController(
                mock(GuardrailService.class),
                mock(GuardrailAuditRecorder.class),
                approvalService
        );
        Instant now = Instant.now();
        ApprovalRecord expired = new ApprovalRecord(
                "approval-1", "run-1", "session-1",
                new ToolCallRequest("ticket_close", "execution-1", Map.of()),
                "high risk", ApprovalStatus.EXPIRED, "system", "approval expired",
                now.minusSeconds(120), now.minusSeconds(60), now
        );
        when(approvalService.find("approval-1")).thenReturn(Optional.of(expired));
        when(approvalService.recent(10)).thenReturn(List.of(expired));

        ApiResponse<ApprovalRecord> single = controller.approval("approval-1").block();
        ApiResponse<List<ApprovalRecord>> recent = controller.approvals(10).block();

        assertNotNull(single);
        assertNotNull(recent);
        assertEquals(ApprovalStatus.EXPIRED, single.data().status());
        assertEquals(ApprovalStatus.EXPIRED, recent.data().get(0).status());
        verify(approvalService).find("approval-1");
        verify(approvalService).recent(10);
    }
}
