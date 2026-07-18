package com.agent.platform.runtime;

@FunctionalInterface
public interface AgentModelDeltaListener {

    AgentModelDeltaListener NOOP = delta -> {
    };

    void onDelta(String delta);
}
