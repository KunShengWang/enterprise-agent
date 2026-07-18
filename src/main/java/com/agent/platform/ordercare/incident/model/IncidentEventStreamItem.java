package com.agent.platform.ordercare.incident.model;

import java.time.Instant;

public record IncidentEventStreamItem(
        String type,
        long cursor,
        TaskEventRecord event,
        Instant emittedAt
) {
    public static IncidentEventStreamItem event(TaskEventRecord event) {
        return new IncidentEventStreamItem("EVENT", event.eventSequence(), event, Instant.now());
    }

    public static IncidentEventStreamItem heartbeat(long cursor) {
        return new IncidentEventStreamItem("HEARTBEAT", cursor, null, Instant.now());
    }
}
