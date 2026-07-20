package com.agent.platform.workbench.presentation;

import java.time.Instant;
import java.util.List;

public record PublicPresentation(
        String presentationId,
        String workItemId,
        long sequence,
        int schemaVersion,
        PublicPresentationKind kind,
        PublicPresentationStatus status,
        String title,
        String summary,
        List<String> steps,
        PublicPresentationDetail detail,
        String sourceType,
        String sourceId,
        String sourceEventId,
        Instant occurredAt,
        PublicVisibility visibility
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public PublicPresentation {
        if (presentationId == null || presentationId.isBlank() || workItemId == null || workItemId.isBlank()
                || sequence < 0 || schemaVersion < 1 || kind == null || status == null
                || sourceType == null || sourceId == null || sourceEventId == null
                || occurredAt == null || visibility == null) {
            throw new IllegalArgumentException("complete public presentation coordinates are required");
        }
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        steps = steps == null ? List.of() : List.copyOf(steps);
        detail = detail == null ? PublicPresentationDetail.empty() : detail;
    }
}
