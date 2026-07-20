package com.agent.platform.workbench.dispatch;

import org.springframework.stereotype.Component;

@Component
public class NoopDispatchFailureInjector implements DispatchFailureInjector {
    @Override public void afterAdapterResult(DispatchClaim claim, DispatchResult result) { }
}
