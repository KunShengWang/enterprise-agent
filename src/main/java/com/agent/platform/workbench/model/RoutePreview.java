package com.agent.platform.workbench.model;

import java.time.Instant;
import java.util.Map;

public record RoutePreview(
        String previewId,
        String workItemId,
        String routeDecisionId,
        String targetId,
        int previewVersion,
        String validatedInputDigest,
        String scopeDigest,
        Map<String, Object> payload,
        RoutePreviewStatus status,
        Instant expiresAt,
        String confirmedBy,
        Instant confirmedAt,
        Instant createdAt
) {
    public RoutePreview {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        confirmedBy = confirmedBy == null ? "" : confirmedBy.trim();
    }
}
