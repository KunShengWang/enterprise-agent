package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkCommandExecutionStatus;
import com.agent.platform.workbench.model.WorkCommandType;

public record WorkCommandResult(
        boolean success,
        String code,
        String message,
        String commandRequestId,
        String inputId,
        WorkCommandType command,
        String executionTarget,
        String workItemId,
        boolean underlyingExecutionChanged,
        String underlyingRunId,
        WorkCommandExecutionStatus executionStatus,
        AgentWorkItem workItem
) {
}
