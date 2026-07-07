package com.agent.platform.skill;

import java.util.List;

public record SkillMatch(
        SkillDefinition skill,
        double score,
        List<String> matchedTerms,
        String reason
) {

    public SkillMatch {
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
    }
}
