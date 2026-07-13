package com.agent.platform.guardrail;

import java.util.List;

public record NormalizedPrompt(
        String original,
        String canonical,
        List<String> decodedVariants
) {

    public NormalizedPrompt {
        original = original == null ? "" : original;
        canonical = canonical == null ? "" : canonical;
        decodedVariants = decodedVariants == null ? List.of() : List.copyOf(decodedVariants);
    }
}
