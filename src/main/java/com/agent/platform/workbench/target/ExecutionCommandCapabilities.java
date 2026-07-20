package com.agent.platform.workbench.target;

import com.agent.platform.workbench.model.WorkCommandType;

import java.util.Map;
import java.util.Set;

public record ExecutionCommandCapabilities(
        Map<WorkCommandType, ExecutionCommandSupport> commands,
        Set<String> constraints
) {
    public ExecutionCommandCapabilities {
        commands = commands == null ? Map.of() : Map.copyOf(commands);
        constraints = constraints == null ? Set.of() : Set.copyOf(constraints);
    }

    public ExecutionCommandSupport support(WorkCommandType commandType) {
        return commands.getOrDefault(commandType, ExecutionCommandSupport.UNSUPPORTED);
    }
}
