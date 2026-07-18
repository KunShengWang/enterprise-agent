package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 向模型提供统一能力目录。RAG 在 AgentLoop 中表现为只读能力，不再是固定路由分支。
 */
@Service
public class DefaultAgentCapabilityRegistry implements AgentCapabilityRegistry {

    public static final String KNOWLEDGE_SEARCH = "knowledge_search";

    public static final String SKILL_CATALOG = "skill_catalog";

    /*
     * 这里故意保存“能力定义”，而不注册可直接执行的 Spring AI ToolCallback。
     *
     * ToolCallback 同时包含模型可见定义和 call() 执行入口；若交给 ChatClient/ToolCallingAdvisor
     * 自动执行，会绕过 AgentToolRuntime 的 Profile 白名单、Schema、Policy、人工审批、幂等 claim、
     * UNKNOWN 对账和审计。当前 Runtime 采用 user-controlled tool execution，因此 Registry 只负责
     * 聚合与查找定义，真正执行必须统一进入 AgentToolRuntime。
     *
     * 将来接入 Provider 原生 Tool Calling 时，可以在 AgentModelGateway 边界把这里的 ToolDefinition
     * 转换成 Spring AI/Provider 定义，但返回的 ToolCall 仍必须交还 Runtime，不能直接调用业务方法。
     * 详见 docs/design-decisions.md 的 ADR-12。
     */
    private static final ToolDefinition KNOWLEDGE_SEARCH_DEFINITION = new ToolDefinition(
            KNOWLEDGE_SEARCH,
            "Search the enterprise knowledge base. Use when the answer depends on internal policies, procedures or troubleshooting documents.",
            """
                    {"type":"object","properties":{"query":{"type":"string"},"topK":{"type":"integer","minimum":1,"maximum":10}},"required":["query"]}
                    """.strip(),
            ToolRiskLevel.LOW,
            Map.of("provider", "rag", "readOnly", true)
    );

    private static final ToolDefinition SKILL_CATALOG_DEFINITION = new ToolDefinition(
            SKILL_CATALOG,
            "List persisted Agent skills or load one skill by name. Skills provide task guidance but never grant tool permissions.",
            """
                    {"type":"object","properties":{"name":{"type":"string","description":"Optional exact skill name"}}}
                    """.strip(),
            ToolRiskLevel.LOW,
            Map.of("provider", "skill-registry", "readOnly", true, "grantsPermissions", false)
    );

    private final ToolRegistry toolRegistry;

    public DefaultAgentCapabilityRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 列出 agent 的能力，也就是 agent 能访问的工具
     * 包括本地定义的工具和 mcp 提供的工具
     */
    @Override
    public List<ToolDefinition> listCapabilities() {
        Map<String, ToolDefinition> capabilities = new LinkedHashMap<>();
        register(capabilities, KNOWLEDGE_SEARCH_DEFINITION, "runtime");
        register(capabilities, SKILL_CATALOG_DEFINITION, "runtime");
        for (ToolDefinition definition : toolRegistry.listTools()) {
            register(capabilities, definition, providerOf(definition));
        }
        return List.copyOf(capabilities.values());
    }

    @Override
    public Optional<ToolDefinition> findCapability(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.trim();
        return listCapabilities().stream()
                .filter(definition -> definition.name().equals(normalized))
                .findFirst();
    }

    private void register(Map<String, ToolDefinition> capabilities,
                          ToolDefinition definition,
                          String provider) {
        if (definition == null || definition.name() == null || definition.name().isBlank()) {
            throw new IllegalStateException("capability definition must have a non-blank name; provider=" + provider);
        }
        String name = definition.name().trim();
        ToolDefinition existing = capabilities.putIfAbsent(name, definition);
        if (existing != null) {
            throw new IllegalStateException(
                    "duplicate capability name '" + name + "' from providers "
                            + providerOf(existing) + " and " + provider
            );
        }
    }

    private String providerOf(ToolDefinition definition) {
        if (definition == null || definition.metadata() == null) {
            return "unknown";
        }
        Object provider = definition.metadata().get("provider");
        return provider == null || String.valueOf(provider).isBlank()
                ? "unknown"
                : String.valueOf(provider);
    }
}
