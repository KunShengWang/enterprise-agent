package com.agent.platform.tool;

import com.agent.platform.mcp.McpToolGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LocalToolRegistry implements ToolRegistry {

    /*
        Spring 提供的一种延迟获取 Bean 的方式
        不直接注入 McpToolGateway 对象，而是注入一个“获取它的供应器”，需要的时候再从 Spring 容器中拿
     */
    private final ObjectProvider<McpToolGateway> mcpToolGatewayProvider;

    private final ObjectProvider<ToolCatalogContributor> catalogContributors;

    private final List<ToolDefinition> tools = List.of(
            new ToolDefinition(
                    "ticket_status",
                    "Query support ticket status by ticketId. Use this before answering concrete ticket status.",
                    """
                            {"type":"object","properties":{"ticketId":{"type":"string","description":"Ticket id such as T1001"}},"required":["ticketId"]}
                            """.strip(),
                    ToolRiskLevel.LOW,
                    Map.of("provider", "local", "publicDisplayName", "工单状态查询",
                            "publicActionSummary", "正在查询工单状态",
                            "publicArgumentKeys", List.of("ticketId"))
            ),
            new ToolDefinition(
                    "ticket_create",
                    "Create a support ticket for a user issue. Use this when the user asks to create or report an issue.",
                    """
                            {"type":"object","properties":{"title":{"type":"string","description":"Issue title"},"priority":{"type":"string","enum":["P0","P1","P2","P3"],"description":"Business priority"}},"required":["title"]}
                            """.strip(),
                    ToolRiskLevel.MEDIUM,
                    Map.of("provider", "local", "publicDisplayName", "创建支持工单",
                            "publicActionSummary", "正在创建支持工单",
                            "publicArgumentKeys", List.of("title", "priority"))
            ),
            new ToolDefinition(
                    "ticket_priority_update",
                    "Update ticket priority. High risk because it changes business handling priority.",
                    """
                            {"type":"object","properties":{"ticketId":{"type":"string"},"priority":{"type":"string","enum":["P0","P1","P2","P3"]}},"required":["ticketId","priority"]}
                            """.strip(),
                    ToolRiskLevel.HIGH,
                    Map.of("provider", "local", "publicDisplayName", "更新工单优先级",
                            "publicActionSummary", "正在更新已审批的工单优先级",
                            "publicArgumentKeys", List.of("ticketId", "priority"))
            ),
            new ToolDefinition(
                    "ticket_close",
                    "Close a support ticket with a close reason. High risk because it changes ticket lifecycle state.",
                    """
                            {"type":"object","properties":{"ticketId":{"type":"string"},"closeReason":{"type":"string"}},"required":["ticketId","closeReason"]}
                            """.strip(),
                    ToolRiskLevel.HIGH,
                    Map.of("provider", "local", "publicDisplayName", "关闭支持工单",
                            "publicActionSummary", "正在关闭已审批的支持工单",
                            "publicArgumentKeys", List.of("ticketId"))
            )
    );

    public LocalToolRegistry(ObjectProvider<McpToolGateway> mcpToolGatewayProvider,
                             ObjectProvider<ToolCatalogContributor> catalogContributors) {
        this.mcpToolGatewayProvider = mcpToolGatewayProvider;
        this.catalogContributors = catalogContributors;
    }

    /**
     * 列出全部工具
     */
    @Override
    public List<ToolDefinition> listTools() {
        List<ToolDefinition> mergedTools = new ArrayList<>(tools);// ① 内置工具
        // 遍历 ToolCatalogContributor 的子类，把他们的工具整合到 mergedTools
        catalogContributors.orderedStream()
                .forEach(contributor -> mergedTools.addAll(contributor.definitions()));
        mcpToolGatewayProvider.ifAvailable(gateway -> mergedTools.addAll(gateway.discoverTools()));
        return List.copyOf(mergedTools);
    }

    /**
     * 根据工具名称寻找工具
     */
    @Override
    public Optional<ToolDefinition> findTool(String toolName) {
        return listTools().stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst();
    }
}
