package com.agent.platform.workbench.model;

public record WorkProjectionSource(
        String workItemId,
        String sourceType,
        String sourceId
) {
}
