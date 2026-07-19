package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.runtime.AgentExecutionProfile;
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
            assertTrue(profile.systemPrompt().contains("exactly once"), role.name());
            assertTrue(profile.systemPrompt().contains("never call any capability again"), role.name());
        }
    }
}
