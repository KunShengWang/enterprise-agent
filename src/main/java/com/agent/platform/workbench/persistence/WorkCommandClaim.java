package com.agent.platform.workbench.persistence;

import com.agent.platform.workbench.model.WorkCommandExecution;

public record WorkCommandClaim(WorkCommandExecution execution, boolean acquired) {
}
