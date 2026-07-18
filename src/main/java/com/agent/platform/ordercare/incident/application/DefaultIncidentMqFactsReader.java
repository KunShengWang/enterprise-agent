package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.client.FlowOrderIncidentClient;
import com.agent.platform.ordercare.incident.client.RabbitMqObservationClient;
import com.agent.platform.ordercare.incident.client.RabbitMqObservationException;
import com.agent.platform.ordercare.incident.model.BrokerObservation;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.IncidentDeadLetterFacts;
import com.agent.platform.ordercare.incident.model.IncidentFactEnvelope;
import com.agent.platform.ordercare.incident.model.IncidentFactQuery;
import com.agent.platform.ordercare.incident.model.IncidentMqFactsResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DefaultIncidentMqFactsReader implements IncidentMqFactsReader {

    private final FlowOrderIncidentClient flowOrderClient;
    private final RabbitMqObservationClient rabbitMqObservationClient;

    public DefaultIncidentMqFactsReader(FlowOrderIncidentClient flowOrderClient,
                                        RabbitMqObservationClient rabbitMqObservationClient) {
        this.flowOrderClient = flowOrderClient;
        this.rabbitMqObservationClient = rabbitMqObservationClient;
    }

    @Override
    public IncidentMqFactsResult read(IncidentFactQuery query, String traceId) {
        IncidentFactEnvelope<IncidentDeadLetterFacts> deadLetterFacts =
                flowOrderClient.queryDeadLetterFacts(query, traceId);
        validateDeadLetterFacts(query, deadLetterFacts);

        try {
            BrokerObservation broker = rabbitMqObservationClient.observeQueues(query.queueNames(), traceId);
            return new IncidentMqFactsResult(deadLetterFacts, broker, false, List.of());
        } catch (RabbitMqObservationException exception) {
            BrokerObservation broker = exception.timeout()
                    ? BrokerObservation.timeout()
                    : BrokerObservation.unavailable();
            EvidenceGap gap = new EvidenceGap(
                    exception.timeout() ? "BROKER_TIMEOUT" : "BROKER_UNAVAILABLE",
                    "rabbitmq-management",
                    "Queue runtime facts are unavailable; persisted dead-letter facts remain valid.");
            return new IncidentMqFactsResult(deadLetterFacts, broker, true, List.of(gap));
        }
    }

    private void validateDeadLetterFacts(IncidentFactQuery query,
                                         IncidentFactEnvelope<IncidentDeadLetterFacts> result) {
        if (result == null || result.facts() == null) {
            throw new IllegalStateException("FlowOrder dead-letter facts are missing");
        }
        if (!query.scopeHash().equals(result.scopeHash())) {
            throw new IllegalStateException("FlowOrder dead-letter facts scopeHash mismatch");
        }
    }
}
