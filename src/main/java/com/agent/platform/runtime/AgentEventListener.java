package com.agent.platform.runtime;

/**
 * 用于流式实时发送事件，每产生一个事件，就会把事件转换为 AgentStreamEvent，然后推送给前端
 */
@FunctionalInterface
public interface AgentEventListener {

    AgentEventListener NOOP = event -> {
    };

    void onEvent(AgentEvent event);
}
