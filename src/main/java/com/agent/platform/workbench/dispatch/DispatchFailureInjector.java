package com.agent.platform.workbench.dispatch;

public interface DispatchFailureInjector {
    void afterAdapterResult(DispatchClaim claim, DispatchResult result);
}
