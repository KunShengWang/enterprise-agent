package com.agent.platform.procurement.agent;

import com.agent.platform.runtime.*;
import java.util.Set;

public record AgentDefinition(String agentId, String instructions, String outputSchema,
                              ContextPolicy contextPolicy, Set<String> toolSet, AgentRunLimits runBudget) {
    public AgentDefinition { toolSet = Set.copyOf(toolSet); }
    public AgentExecutionProfile profile() {
        return new AgentExecutionProfile(agentId, instructions + "\nFinal output JSON Schema (Runtime rejects unknown enum/fields):\n" + outputSchema, toolSet, runBudget, false, contextPolicy);
    }
}
