package com.agent.platform.workbench.persistence;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkCommandExecution;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

import java.time.Duration;
import java.util.Optional;

public interface WorkCommandExecutionStore {

    WorkCommandClaim claim(AuthenticatedPrincipal principal,
                           String inputId,
                           String workItemId,
                           WorkCommandType commandType,
                           long expectedWorkVersion,
                           String leaseOwner,
                           Duration leaseDuration);

    WorkCommandExecution complete(AuthenticatedPrincipal principal,
                                  String commandRequestId,
                                  String leaseOwner,
                                  long claimToken,
                                  WorkCommandCompletion completion);

    Optional<WorkCommandExecution> findByInput(AuthenticatedPrincipal principal, String inputId);

    WorkCommandExecution recordUnboundRejection(AuthenticatedPrincipal principal,
                                                 String inputId,
                                                 WorkCommandType commandType,
                                                 String resultCode,
                                                 String message);

    AgentWorkItem requireWorkItem(AuthenticatedPrincipal principal, String workItemId);
}
