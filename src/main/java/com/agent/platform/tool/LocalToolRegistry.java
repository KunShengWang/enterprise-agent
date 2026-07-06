package com.agent.platform.tool;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LocalToolRegistry implements ToolRegistry {

    private final List<ToolDefinition> tools = List.of(
            new ToolDefinition(
                    "ticket_status",
                    "Query support ticket status by ticketId.",
                    "{\"type\":\"object\",\"properties\":{\"ticketId\":{\"type\":\"string\"}},\"required\":[\"ticketId\"]}",
                    ToolRiskLevel.LOW,
                    Map.of("provider", "local")
            ),
            new ToolDefinition(
                    "ticket_create",
                    "Create a support ticket for a user issue.",
                    "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"priority\":{\"type\":\"string\"}},\"required\":[\"title\"]}",
                    ToolRiskLevel.MEDIUM,
                    Map.of("provider", "local")
            ),
            new ToolDefinition(
                    "ticket_priority_update",
                    "Update ticket priority. High risk because it changes business handling priority.",
                    "{\"type\":\"object\",\"properties\":{\"ticketId\":{\"type\":\"string\"},\"priority\":{\"type\":\"string\"}},\"required\":[\"ticketId\",\"priority\"]}",
                    ToolRiskLevel.HIGH,
                    Map.of("provider", "local")
            )
    );

    @Override
    public List<ToolDefinition> listTools() {
        return tools;
    }

    @Override
    public Optional<ToolDefinition> findTool(String toolName) {
        return tools.stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst();
    }
}
