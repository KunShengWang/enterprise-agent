package com.agent.platform.workbench.persistence;

import com.agent.platform.workbench.model.WorkCommandExecutionStatus;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;

import java.util.Map;

public record WorkCommandCompletion(
        WorkCommandExecutionStatus status,
        String resultCode,
        boolean underlyingExecutionChanged,
        String underlyingRunId,
        String message,
        WorkControlState controlState,
        WorkExecutionState executionState,
        WorkOutcome outcome,
        WorkEventType eventType,
        String phase,
        Map<String, Object> eventPayload
) {
    public WorkCommandCompletion {
        eventPayload = eventPayload == null ? Map.of() : Map.copyOf(eventPayload);
    }
}
