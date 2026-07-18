package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.client.FlowOrderIncidentClient;
import com.agent.platform.ordercare.incident.client.RabbitMqObservationClient;
import com.agent.platform.ordercare.incident.client.RabbitMqObservationException;
import com.agent.platform.ordercare.incident.model.IncidentDeadLetterFacts;
import com.agent.platform.ordercare.incident.model.IncidentFactEnvelope;
import com.agent.platform.ordercare.incident.model.IncidentFactQuery;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultIncidentMqFactsReaderTests {

    @Test
    void keepsDeadLetterFactsWhenBrokerObservationTimesOut() {
        FlowOrderIncidentClient flowOrder = mock(FlowOrderIncidentClient.class);
        RabbitMqObservationClient rabbit = mock(RabbitMqObservationClient.class);
        IncidentFactQuery query = query();
        IncidentFactEnvelope<IncidentDeadLetterFacts> deadLetters = deadLetters();
        when(flowOrder.queryDeadLetterFacts(query, "trace-1")).thenReturn(deadLetters);
        when(rabbit.observeQueues(query.queueNames(), "trace-1"))
                .thenThrow(new RabbitMqObservationException("timeout", true, null));

        var result = new DefaultIncidentMqFactsReader(flowOrder, rabbit).read(query, "trace-1");

        assertSame(deadLetters, result.deadLetterFacts());
        assertTrue(result.partial());
        assertEquals("TIMEOUT", result.brokerObservation().status());
        assertEquals("BROKER_TIMEOUT", result.evidenceGaps().get(0).code());
        verify(flowOrder).queryDeadLetterFacts(query, "trace-1");
        verify(rabbit).observeQueues(query.queueNames(), "trace-1");
    }

    private IncidentFactQuery query() {
        return new IncidentFactQuery(
                "inc-1",
                "snap-1",
                "scope-1",
                List.of("REQ-1"),
                List.of("orders.dlq"),
                500);
    }

    private IncidentFactEnvelope<IncidentDeadLetterFacts> deadLetters() {
        IncidentDeadLetterFacts facts = new IncidentDeadLetterFacts(
                1,
                1,
                1,
                1,
                0,
                0,
                List.of("DEDUCT-1"),
                List.of("REQ-1"),
                List.of(1L),
                List.of(),
                List.of());
        return new IncidentFactEnvelope<>(
                "floworder-incident-facts-v1",
                "floworder-resource-service",
                "incident/dead-letter-facts/inc-1",
                "scope-1",
                OffsetDateTime.now(),
                false,
                List.of(),
                facts);
    }
}
