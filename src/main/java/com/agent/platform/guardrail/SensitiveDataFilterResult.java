package com.agent.platform.guardrail;

import java.util.List;

public record SensitiveDataFilterResult(
        String safeContent,
        List<String> categories
) {

    public SensitiveDataFilterResult {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
