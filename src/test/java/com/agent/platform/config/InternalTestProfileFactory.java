package com.agent.platform.config;

import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentRunLimits;
import java.util.Set;

/** Test fixture for public Runtime extensibility; never a production business registration. */
public class InternalTestProfileFactory implements AgentScenarioProfileFactory {
    public String scenarioId() { return "internal-test-v1"; }
    public AgentExecutionProfile createProfile() {
        return new AgentExecutionProfile(scenarioId(), "Internal test", Set.of("knowledge_search"),
                new AgentRunLimits(4, 3, 2, 8000, 1000, 0.1, 30000), false);
    }
}
