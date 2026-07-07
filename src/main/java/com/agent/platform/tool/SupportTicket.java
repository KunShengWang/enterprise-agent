package com.agent.platform.tool;

import java.time.Instant;

public record SupportTicket(
        String ticketId,
        String title,
        String priority,
        String status,
        String assignee,
        Instant createdAt,
        Instant updatedAt
) {
}
