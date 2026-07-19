package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.RoutingAttempt;
import org.springframework.stereotype.Component;

@Component
public class NoopRoutingFailureInjector implements RoutingFailureInjector {
    @Override
    public void afterModelResult(RoutingAttempt attempt, RouterModelResult result) {
    }
}
