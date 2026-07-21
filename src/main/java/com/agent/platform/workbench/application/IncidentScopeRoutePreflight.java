package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.model.RouteValidationResult;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

import java.util.Optional;

public interface IncidentScopeRoutePreflight {

    IncidentScopeRoutePreflight NOOP = (principal, workItem, decision, context) -> Optional.empty();

    Optional<RouteValidationResult> resolve(AuthenticatedPrincipal principal,
                                            AgentWorkItem workItem,
                                            ExecutionDecision decision,
                                            ResolvedRouteContext context);
}
