package com.agent.platform.runtime;

@FunctionalInterface
public interface AgentEventListener {

    AgentEventListener NOOP = event -> {
    };

    void onEvent(AgentEvent event);
}
