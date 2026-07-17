package com.agent.platform.ordercare.tool;

import com.agent.platform.ordercare.application.OrderCareCaseInspector;
import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderCareToolHandlerTests {

    @Test
    void returnsStructuredAuthoritativeCaseFacts() {
        OrderCareCaseInspector inspector = mock(OrderCareCaseInspector.class);
        when(inspector.inspect("REQUEST_ID", "request-1", "run-1"))
                .thenReturn(snapshot("REPLAY_CANDIDATE", true));
        OrderCareToolHandler handler = new OrderCareToolHandler(inspector, new ObjectMapper());

        ToolCallResult result = handler.execute(new ToolCallRequest(
                OrderCareToolCatalog.CASE_INSPECT,
                "run-1",
                Map.of("identifierType", "REQUEST_ID", "identifierValue", "request-1")
        ));

        assertTrue(result.success());
        assertTrue(result.content().contains("REPLAY_CANDIDATE"));
        assertEquals("floworder:request:request-1", result.metadata().get("caseKey"));
        assertEquals(true, result.metadata().get("recoveryEligible"));
    }

    @Test
    void preservesDependencyFailureClassificationForRuntimeRetryPolicy() {
        OrderCareCaseInspector inspector = mock(OrderCareCaseInspector.class);
        when(inspector.inspect("REQUEST_ID", "request-1", "run-1"))
                .thenThrow(new FlowOrderApiException("temporarily unavailable", 503, true));
        OrderCareToolHandler handler = new OrderCareToolHandler(inspector, new ObjectMapper());

        ToolCallResult result = handler.execute(new ToolCallRequest(
                OrderCareToolCatalog.CASE_INSPECT,
                "run-1",
                Map.of("identifierType", "REQUEST_ID", "identifierValue", "request-1")
        ));

        assertFalse(result.success());
        assertEquals(503, result.metadata().get("statusCode"));
        assertEquals(true, result.metadata().get("retryable"));
    }

    private OrderCareCaseSnapshot snapshot(String diagnosisCode, boolean eligible) {
        return new OrderCareCaseSnapshot(
                "floworder-recovery-case-v1",
                "floworder:request:request-1",
                "REQUEST_ID",
                "request-1",
                "request-1",
                true,
                diagnosisCode,
                true,
                eligible,
                "2026-07-17T00:00:00",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of("ORDER_FOUND"),
                List.of()
        );
    }
}
