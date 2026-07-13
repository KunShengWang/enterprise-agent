package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 向模型提供统一能力目录。RAG 在 AgentLoop 中表现为只读能力，不再是固定路由分支。
 */
@Service
public class DefaultAgentCapabilityRegistry implements AgentCapabilityRegistry {

    public static final String KNOWLEDGE_SEARCH = "knowledge_search";

    private static final ToolDefinition KNOWLEDGE_SEARCH_DEFINITION = new ToolDefinition(
            KNOWLEDGE_SEARCH,
            "Search the enterprise knowledge base. Use when the answer depends on internal policies, procedures or troubleshooting documents.",
            """
                    {"type":"object","properties":{"query":{"type":"string"},"topK":{"type":"integer","minimum":1,"maximum":10}},"required":["query"]}
                    """.strip(),
            ToolRiskLevel.LOW,
            Map.of("provider", "rag", "readOnly", true)
    );

    private final ToolRegistry toolRegistry;

    public DefaultAgentCapabilityRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public List<ToolDefinition> listCapabilities() {
        List<ToolDefinition> capabilities = new ArrayList<>();
        capabilities.add(KNOWLEDGE_SEARCH_DEFINITION);
        capabilities.addAll(toolRegistry.listTools());
        return List.copyOf(capabilities);
    }

    @Override
    public Optional<ToolDefinition> findCapability(String name) {
        if (KNOWLEDGE_SEARCH.equals(name)) {
            return Optional.of(KNOWLEDGE_SEARCH_DEFINITION);
        }
        return toolRegistry.findTool(name);
    }
}
