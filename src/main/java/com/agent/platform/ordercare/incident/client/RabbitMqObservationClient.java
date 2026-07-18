package com.agent.platform.ordercare.incident.client;

import com.agent.platform.ordercare.incident.model.BrokerObservation;

import java.util.List;

public interface RabbitMqObservationClient {

    BrokerObservation observeQueues(List<String> queueNames, String traceId);
}
