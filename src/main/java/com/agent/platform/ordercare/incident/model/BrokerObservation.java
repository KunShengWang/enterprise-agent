package com.agent.platform.ordercare.incident.model;

import java.time.OffsetDateTime;
import java.util.List;

public record BrokerObservation(
        String status,
        OffsetDateTime observedAt,
        List<QueueRuntimeFact> queues,
        List<String> runtimeSignals,
        String errorCode
) {
    public BrokerObservation {
        queues = queues == null ? List.of() : List.copyOf(queues);
        runtimeSignals = runtimeSignals == null ? List.of() : List.copyOf(runtimeSignals);
    }

    public static BrokerObservation timeout() {
        return new BrokerObservation("TIMEOUT", OffsetDateTime.now(), List.of(), List.of(), "BROKER_TIMEOUT");
    }

    public static BrokerObservation unavailable() {
        return new BrokerObservation(
                "UNAVAILABLE",
                OffsetDateTime.now(),
                List.of(),
                List.of(),
                "BROKER_UNAVAILABLE");
    }

    public record QueueRuntimeFact(
            String queueName,
            Integer messagesReady,
            Integer messagesUnacknowledged,
            Integer consumerCount,
            String state
    ) {
    }
}
