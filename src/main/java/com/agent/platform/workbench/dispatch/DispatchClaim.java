package com.agent.platform.workbench.dispatch;

import com.agent.platform.workbench.model.DispatchAttempt;

public record DispatchClaim(DispatchAttempt attempt, DispatchRequest request) {
}
