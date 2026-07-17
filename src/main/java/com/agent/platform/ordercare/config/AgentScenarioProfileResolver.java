package com.agent.platform.ordercare.config;

import com.agent.platform.runtime.AgentExecutionProfile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AgentScenarioProfileResolver {

    public static final String ORDERCARE_FLOWORDER_V1 = "ordercare-floworder-v1";

    private final OrderCareExecutionProfileFactory orderCareProfileFactory;

    public AgentScenarioProfileResolver(OrderCareExecutionProfileFactory orderCareProfileFactory) {
        this.orderCareProfileFactory = orderCareProfileFactory;
    }

    public Optional<AgentExecutionProfile> resolve(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return Optional.empty();
        }
        if (ORDERCARE_FLOWORDER_V1.equals(scenarioId.trim())) {
            return Optional.of(orderCareProfileFactory.createProfile());
        }
        throw new IllegalArgumentException("unsupported agent scenarioId: " + scenarioId);
    }
}
