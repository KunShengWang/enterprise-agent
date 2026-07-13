package com.agent.platform.runtime;

/**
 * 同一会话已有 Agent Loop 持有执行租约。
 */
public class AgentSessionBusyException extends RuntimeException {

    public AgentSessionBusyException(String sessionId) {
        super("agent session is already running: " + sessionId);
    }
}
