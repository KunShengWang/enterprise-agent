package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.ordercare.incident.tool.IncidentToolCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentExecutionProfileFactoryTests {

    private final IncidentExecutionProfileFactory factory = new IncidentExecutionProfileFactory();

    @Test
    void specialistExposesOneCapabilityAndAllowsExactlyOneToolCall() {
        for (IncidentAgentRole role : IncidentAgentRole.values()) {
            AgentExecutionProfile profile = factory.specialist(role);

            assertEquals(1, profile.allowedCapabilities().size(), role.name());
            assertEquals(1, profile.limits().maxToolCalls(), role.name());
            assertTrue(profile.systemPrompt().contains("必须且只能调用所提供的唯一能力一次"), role.name());
            assertTrue(profile.systemPrompt().contains("绝不能再次调用任何能力"), role.name());
        }
    }

    @Test
    void commanderExposesBoundedSubAgentToolsAndGatesMqByTrustedScope() {
        AgentExecutionProfile withoutMq = factory.commanderWithSubAgents(false);
        AgentExecutionProfile withMq = factory.commanderWithSubAgents(true);

        assertTrue(withoutMq.allows(IncidentToolCatalog.DELEGATE_ORDER_ANALYST));
        assertTrue(withoutMq.allows(IncidentToolCatalog.DELEGATE_INVENTORY_ANALYST));
        assertTrue(withoutMq.allows(IncidentToolCatalog.REVIEW_INCIDENT_EVIDENCE));
        assertEquals(false, withoutMq.allows(IncidentToolCatalog.DELEGATE_MQ_ANALYST));
        assertTrue(withMq.allows(IncidentToolCatalog.DELEGATE_MQ_ANALYST));
        assertEquals(withMq.allowedCapabilities().size(), withMq.limits().maxToolCalls());
        assertTrue(withMq.systemPrompt().contains("REVIEW_READY"));
    }

    @Test
    void commanderToolsDeclareNonOverlappingRuntimePhases() {
        var definitions = new IncidentToolCatalog().definitions();
        var order = definitions.stream()
                .filter(item -> IncidentToolCatalog.DELEGATE_ORDER_ANALYST.equals(item.name()))
                .findFirst().orElseThrow();
        var reviewer = definitions.stream()
                .filter(item -> IncidentToolCatalog.REVIEW_INCIDENT_EVIDENCE.equals(item.name()))
                .findFirst().orElseThrow();

        assertEquals(true, order.metadata().get("initialOnly"));
        assertEquals("REVIEW_READY", reviewer.metadata().get("requiredFollowUpType"));
        assertEquals("REVIEWING", reviewer.metadata().get("stateGate"));
    }

    @Test
    void specialistFactToolsAreSingleUseCapabilities() {
        var definitions = new IncidentToolCatalog().definitions();
        for (String name : java.util.List.of(
                IncidentToolCatalog.ORDER_FACTS,
                IncidentToolCatalog.INVENTORY_FACTS,
                IncidentToolCatalog.MQ_FACTS)) {
            var definition = definitions.stream()
                    .filter(item -> name.equals(item.name()))
                    .findFirst().orElseThrow();
            assertEquals(true, definition.metadata().get("singleUse"), name);
        }
    }
}
