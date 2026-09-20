package com.agent.platform.config;

import com.agent.platform.runtime.AgentExecutionProfile;

/** Server-registered scenario profiles; user metadata cannot register a factory. */
public interface AgentScenarioProfileFactory {
    String scenarioId();
    AgentExecutionProfile createProfile();
}
