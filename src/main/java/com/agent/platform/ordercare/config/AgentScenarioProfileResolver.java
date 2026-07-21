package com.agent.platform.ordercare.config;

import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.config.GeneralAgentExecutionProfileFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AgentScenarioProfileResolver {

    public static final String ORDERCARE_FLOWORDER_V1 = "ordercare-floworder-v1";
    public static final String GENERAL_AGENT_V1 = GeneralAgentExecutionProfileFactory.PROFILE_NAME;

    private final OrderCareExecutionProfileFactory orderCareProfileFactory;
    private final GeneralAgentExecutionProfileFactory generalProfileFactory;

    public AgentScenarioProfileResolver(OrderCareExecutionProfileFactory orderCareProfileFactory,
                                        GeneralAgentExecutionProfileFactory generalProfileFactory) {
        this.orderCareProfileFactory = orderCareProfileFactory;
        this.generalProfileFactory = generalProfileFactory;
    }

    public Optional<AgentExecutionProfile> resolve(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return Optional.empty();
        }
        if (ORDERCARE_FLOWORDER_V1.equals(scenarioId.trim())) {
            return Optional.of(orderCareProfileFactory.createProfile());
        }
        if (GENERAL_AGENT_V1.equals(scenarioId.trim())) {
            return Optional.of(generalProfileFactory.createProfile());
        }
        throw new IllegalArgumentException("unsupported agent scenarioId: " + scenarioId);
    }
}
