package com.agent.platform.config;

import com.agent.platform.runtime.AgentExecutionProfile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentScenarioProfileResolver {
    public static final String ORDERCARE_FLOWORDER_V1 = "ordercare-floworder-v1";
    public static final String GENERAL_AGENT_V1 = "general-agent-v1";
    public static final String PROCUREMENT_SOURCING_READONLY_V1 = "procurement-sourcing-rfq-v1";

    private final Map<String, AgentScenarioProfileFactory> factories;

    public AgentScenarioProfileResolver(List<AgentScenarioProfileFactory> factories) {
        Map<String, AgentScenarioProfileFactory> indexed = new LinkedHashMap<>();
        for (AgentScenarioProfileFactory factory : factories) {
            String id = factory.scenarioId();
            if (com.agent.platform.common.BusinessRetirementPolicy.retiredScenario(id)) continue;
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("agent scenarioId must not be blank");
            }
            if (indexed.putIfAbsent(id.trim(), factory) != null) {
                throw new IllegalArgumentException("duplicate agent scenarioId: " + id);
            }
        }
        this.factories = Map.copyOf(indexed);
    }

    public Optional<AgentExecutionProfile> resolve(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) return Optional.empty();
        com.agent.platform.common.BusinessRetirementPolicy.requireScenario(scenarioId);
        AgentScenarioProfileFactory factory = factories.get(scenarioId.trim());
        if (factory == null) {
            throw new IllegalArgumentException("unsupported agent scenarioId: " + scenarioId);
        }
        return Optional.of(factory.createProfile());
    }
}
