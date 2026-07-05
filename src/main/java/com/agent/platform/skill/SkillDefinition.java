package com.agent.platform.skill;

import java.util.List;

public record SkillDefinition(
        String name,
        String description,
        String promptTemplate,
        List<String> toolNames,
        String inputSchema,
        String outputSchema,
        String riskLevel
) {

    public SkillDefinition {
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }
}
