package com.agent.platform.tool;

import com.agent.platform.mcp.McpToolGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LocalToolRegistry implements ToolRegistry {

    private final ObjectProvider<McpToolGateway> mcpToolGatewayProvider;

    private final List<ToolDefinition> tools = List.of(
            new ToolDefinition(
                    "ticket_status",
                    "Query support ticket status by ticketId. Use this before answering concrete ticket status.",
                    """
                            {"type":"object","properties":{"ticketId":{"type":"string","description":"Ticket id such as T1001"}},"required":["ticketId"]}
                            """.strip(),
                    ToolRiskLevel.LOW,
                    Map.of("provider", "local")
            ),
            new ToolDefinition(
                    "ticket_create",
                    "Create a support ticket for a user issue. Use this when the user asks to create or report an issue.",
                    """
                            {"type":"object","properties":{"title":{"type":"string","description":"Issue title"},"priority":{"type":"string","enum":["P0","P1","P2","P3"],"description":"Business priority"}},"required":["title"]}
                            """.strip(),
                    ToolRiskLevel.MEDIUM,
                    Map.of("provider", "local")
            ),
            new ToolDefinition(
                    "ticket_priority_update",
                    "Update ticket priority. High risk because it changes business handling priority.",
                    """
                            {"type":"object","properties":{"ticketId":{"type":"string"},"priority":{"type":"string","enum":["P0","P1","P2","P3"]}},"required":["ticketId","priority"]}
                            """.strip(),
                    ToolRiskLevel.HIGH,
                    Map.of("provider", "local")
            ),
            new ToolDefinition(
                    "ticket_close",
                    "Close a support ticket with a close reason. High risk because it changes ticket lifecycle state.",
                    """
                            {"type":"object","properties":{"ticketId":{"type":"string"},"closeReason":{"type":"string"}},"required":["ticketId","closeReason"]}
                            """.strip(),
                    ToolRiskLevel.HIGH,
                    Map.of("provider", "local")
            )
    );

    public LocalToolRegistry() {
        this.mcpToolGatewayProvider = null;
    }

    @Autowired
    public LocalToolRegistry(ObjectProvider<McpToolGateway> mcpToolGatewayProvider) {
        this.mcpToolGatewayProvider = mcpToolGatewayProvider;
    }

    @Override
    public List<ToolDefinition> listTools() {
        List<ToolDefinition> mergedTools = new ArrayList<>(tools);
        if (mcpToolGatewayProvider != null) {
            mcpToolGatewayProvider.ifAvailable(gateway -> mergedTools.addAll(gateway.discoverTools()));
        }
        return List.copyOf(mergedTools);
    }

    @Override
    public Optional<ToolDefinition> findTool(String toolName) {
        return listTools().stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst();
    }
}
