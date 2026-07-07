package com.agent.platform.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalToolExecutorTests {

    @Test
    void shouldCreateAndQueryTicketWithState() {
        LocalToolExecutor executor = new LocalToolExecutor();

        ToolCallResult createResult = executor.execute(new ToolCallRequest(
                "ticket_create",
                UUID.randomUUID().toString(),
                Map.of("title", "支付失败", "priority", "P1")
        ));

        assertThat(createResult.success()).isTrue();
        String ticketId = String.valueOf(createResult.metadata().get("ticketId"));

        ToolCallResult statusResult = executor.execute(new ToolCallRequest(
                "ticket_status",
                UUID.randomUUID().toString(),
                Map.of("ticketId", ticketId)
        ));

        assertThat(statusResult.success()).isTrue();
        assertThat(statusResult.content()).contains(ticketId, "支付失败", "P1");
    }

    @Test
    void shouldRejectInvalidPriorityBySchema() {
        LocalToolExecutor executor = new LocalToolExecutor();

        ToolCallResult result = executor.execute(new ToolCallRequest(
                "ticket_priority_update",
                UUID.randomUUID().toString(),
                Map.of("ticketId", "T1001", "priority", "P9")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("priority");
    }
}
