package com.agent.platform.workbench.application;

import com.agent.platform.runtime.AgentRunRecord;

public record AgentRunCommandResult(
        boolean accepted,
        boolean underlyingExecutionChanged,
        String code,
        String message,
        AgentRunRecord before,
        AgentRunRecord after
) {
}
