package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

public interface RouteDecisionPostProcessor {
    void afterEffectiveDecision(AuthenticatedPrincipal principal,
                                AgentWorkItem workItem,
                                RoutingDecisionRecord decision);
}
