package com.agent.platform.workbench.model;

import java.time.Instant;

public record WorkRelation(
        String sourceWorkItemId,
        String targetWorkItemId,
        WorkRelationType relationType,
        String createdByInputId,
        Instant createdAt
) {
}
