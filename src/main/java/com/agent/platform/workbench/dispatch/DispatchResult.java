package com.agent.platform.workbench.dispatch;

import com.agent.platform.workbench.model.WorkLinkType;

public record DispatchResult(
        String dispatchRequestId,
        WorkLinkType linkType,
        String linkedId,
        boolean newlyCreated
) {
    public DispatchResult {
        if (dispatchRequestId == null || dispatchRequestId.isBlank()
                || linkType == null || linkedId == null || linkedId.isBlank()) {
            throw new IllegalArgumentException("dispatchRequestId, linkType and linkedId are required");
        }
        dispatchRequestId = dispatchRequestId.trim();
        linkedId = linkedId.trim();
    }
}
