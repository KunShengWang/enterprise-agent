package com.agent.platform.workbench.application;

import java.util.Map;

public record ResolvedRouteContext(
        String conversationSummary,
        Map<String, String> trustedIdentifiers,
        Map<String, String> serverResolvedIdentifiers
) {
    public ResolvedRouteContext {
        conversationSummary = conversationSummary == null ? "" : conversationSummary.trim();
        trustedIdentifiers = trustedIdentifiers == null ? Map.of() : Map.copyOf(trustedIdentifiers);
        serverResolvedIdentifiers = serverResolvedIdentifiers == null ? Map.of() : Map.copyOf(serverResolvedIdentifiers);
    }
}
