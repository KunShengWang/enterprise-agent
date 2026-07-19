package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.RoutingAttempt;

public interface RoutingFailureInjector {
    void afterModelResult(RoutingAttempt attempt, RouterModelResult result);
}
