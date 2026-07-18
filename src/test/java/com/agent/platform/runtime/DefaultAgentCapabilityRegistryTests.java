package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAgentCapabilityRegistryTests {

    @Test
    void mergesRuntimeAndExternalDefinitionsWithoutOwningExecution() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDefinition external = definition("ticket_status", "local");
        when(toolRegistry.listTools()).thenReturn(List.of(external));
        DefaultAgentCapabilityRegistry registry = new DefaultAgentCapabilityRegistry(toolRegistry);

        List<ToolDefinition> capabilities = registry.listCapabilities();

        assertEquals(List.of("knowledge_search", "skill_catalog", "ticket_status"),
                capabilities.stream().map(ToolDefinition::name).toList());
        assertEquals(external, registry.findCapability("  ticket_status ").orElseThrow());
        assertTrue(registry.findCapability("missing").isEmpty());
    }

    @Test
    void rejectsDuplicateNamesAcrossRuntimeAndExternalProviders() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(List.of(definition("knowledge_search", "mcp")));
        DefaultAgentCapabilityRegistry registry = new DefaultAgentCapabilityRegistry(toolRegistry);

        IllegalStateException failure = assertThrows(IllegalStateException.class, registry::listCapabilities);

        assertTrue(failure.getMessage().contains("duplicate capability name 'knowledge_search'"));
        assertTrue(failure.getMessage().contains("rag"));
        assertTrue(failure.getMessage().contains("mcp"));
    }

    @Test
    void rejectsDuplicateNamesBetweenExternalContributors() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(List.of(
                definition("shared_tool", "local"),
                definition("shared_tool", "mcp")
        ));
        DefaultAgentCapabilityRegistry registry = new DefaultAgentCapabilityRegistry(toolRegistry);

        assertThrows(IllegalStateException.class, registry::listCapabilities);
    }

    private ToolDefinition definition(String name, String provider) {
        return new ToolDefinition(
                name,
                "test tool",
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                Map.of("provider", provider)
        );
    }
}
