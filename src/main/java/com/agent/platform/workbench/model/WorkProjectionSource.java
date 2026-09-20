package com.agent.platform.workbench.model;

/** Historical source identifiers remain readable; only AGENT_RUN supports new projection writes. */
public record WorkProjectionSource(
        String workItemId,
        String sourceType,
        String sourceId
) {
}
