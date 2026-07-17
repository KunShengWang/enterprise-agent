package com.agent.platform.ordercare.config;

import com.agent.platform.ordercare.tool.OrderCareToolCatalog;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.DefaultAgentCapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderCareExecutionProfileFactoryTests {

    @Test
    void m1ProfileIsReadOnlyAndDisablesLongTermMemory() {
        OrderCareExecutionProfileFactory factory = new OrderCareExecutionProfileFactory();

        AgentExecutionProfile profile = factory.createM1Profile();

        assertEquals(OrderCareExecutionProfileFactory.PROFILE_NAME, profile.name());
        assertEquals(Set.of(
                OrderCareToolCatalog.CASE_INSPECT,
                DefaultAgentCapabilityRegistry.KNOWLEDGE_SEARCH
        ), profile.allowedCapabilities());
        assertFalse(profile.longTermMemoryEnabled());
        assertEquals(4, profile.limits().maxToolCalls());
        assertTrue(profile.systemPrompt().contains("必须调用 knowledge_search"));
        assertTrue(profile.systemPrompt().contains("预演和人工审批"));
    }

    @Test
    void resolverAcceptsOnlyServerKnownScenarioIds() {
        AgentScenarioProfileResolver resolver = new AgentScenarioProfileResolver(
                new OrderCareExecutionProfileFactory()
        );

        assertEquals(OrderCareExecutionProfileFactory.PROFILE_NAME,
                resolver.resolve(AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1)
                        .orElseThrow()
                        .name());
        assertFalse(resolver.resolve("").isPresent());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("user-defined-profile"));
    }
}
