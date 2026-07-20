package com.agent.platform.workbench.presentation;

import java.util.Map;

public record PublicPresentationDetail(
        String targetLabel,
        String referenceType,
        String referenceId,
        PublicToolPresentation tool,
        Map<String, String> attributes
) {
    public PublicPresentationDetail {
        targetLabel = text(targetLabel);
        referenceType = text(referenceType);
        referenceId = text(referenceId);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static PublicPresentationDetail empty() {
        return new PublicPresentationDetail("", "", "", null, Map.of());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
