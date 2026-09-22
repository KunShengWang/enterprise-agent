package com.agent.platform.tool;

import com.agent.platform.mcp.McpToolGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalToolRegistryTests {

    @Test
    void localToolsRemainAvailableWhenMcpIsNotConfigured() {
        LocalToolRegistry registry = registry(null, List.of());

        List<ToolDefinition> tools = registry.listTools();

        assertEquals(4, tools.size());
        assertTrue(registry.findTool("ticket_status").isPresent());
        assertTrue(registry.findTool("ticket_close").isPresent());
    }

    @Test
    void duplicateMcpNamesFailClosedInsteadOfDependingOnIterationOrder() {
        ToolDefinition first = mcp("mcp.shared.echo", "server-a");
        ToolDefinition second = mcp("mcp.shared.echo", "server-b");
        LocalToolRegistry registry = registry(new StaticGateway(List.of(first, second)), List.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class, registry::listTools);

        assertEquals("duplicate tool name 'mcp.shared.echo' between providers 'mcp' and 'mcp'",
                failure.getMessage());
    }

    @Test
    void localAndMcpNameCollisionIsNotSilentlyOverwritten() {
        LocalToolRegistry registry = registry(
                new StaticGateway(List.of(mcp("ticket_status", "remote"))), List.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class, registry::listTools);

        assertEquals("duplicate tool name 'ticket_status' between providers 'local' and 'mcp'",
                failure.getMessage());
    }

    @Test
    void contributorCollisionIsAlsoRejectedDeterministically() {
        ToolCatalogContributor contributor = () -> List.of(
                new ToolDefinition("ticket_status", "shadow", "{\"type\":\"object\"}",
                        ToolRiskLevel.LOW, Map.of("provider", "contributor")));
        LocalToolRegistry registry = registry(null, List.of(contributor));

        IllegalStateException failure = assertThrows(IllegalStateException.class, registry::listTools);

        assertEquals("duplicate tool name 'ticket_status' between providers 'local' and 'contributor'",
                failure.getMessage());
    }

    private static LocalToolRegistry registry(McpToolGateway gateway,
                                               List<ToolCatalogContributor> contributors) {
        return new LocalToolRegistry(
                objectProvider(gateway), streamProvider(contributors));
    }

    private static ToolDefinition mcp(String name, String serverId) {
        return new ToolDefinition(name, "remote", "{\"type\":\"object\"}", ToolRiskLevel.MEDIUM,
                Map.of("provider", "mcp", "mcpServerId", serverId,
                        "mcpToolName", name, "mcpSessionGeneration", 1L));
    }

    private static <T> ObjectProvider<T> objectProvider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    private static <T> ObjectProvider<T> streamProvider(List<T> values) {
        return new ObjectProvider<>() {
            @Override
            public Stream<T> orderedStream() {
                return values.stream();
            }
        };
    }

    private static final class StaticGateway implements McpToolGateway {

        private final List<ToolDefinition> tools;

        private StaticGateway(List<ToolDefinition> tools) {
            this.tools = List.copyOf(tools);
        }

        @Override
        public List<ToolDefinition> discoverTools() {
            return tools;
        }

        @Override
        public ToolCallResult callTool(ToolCallRequest request) {
            return new ToolCallResult(request.toolName(), true, "ok", "", Map.of("provider", "mcp"));
        }
    }
}
