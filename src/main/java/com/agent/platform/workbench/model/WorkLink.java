package com.agent.platform.workbench.model;

import java.time.Instant;

public record WorkLink(
        String workItemId,
        String dispatchRequestId,
        WorkLinkType linkType,
        String linkedId,
        WorkLinkRelation relation,
        Instant createdAt
) {
}
