package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;
import java.util.List;
import java.util.Map;

/** Schema exposure only. Execution authorization, approval binding and idempotency remain independent. */
public interface ToolSelector {
    Selection select(AgentExecutionProfile profile, ToolSelectionState business, AgentRunRecord run,
                     List<ToolExecutionRecord> executions, List<ToolDefinition> candidates);

    record Selection(List<ToolDefinition> tools, Map<String, Object> metrics) {
        public Selection { tools = List.copyOf(tools); metrics = Map.copyOf(metrics); }
        public boolean contains(String name) { return tools.stream().anyMatch(t -> t.name().equals(name)); }
    }
}
