package com.agent.platform.tool;

import com.agent.platform.mcp.McpToolGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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
                    "根据 ticketId 查询支持工单状态。回答具体工单状态前应先调用此工具。",
                    """
                            {"type":"object","properties":{"ticketId":{"type":"string","description":"工单 ID，例如 T1001"}},"required":["ticketId"]}
                            """.strip(),
                    ToolRiskLevel.LOW,
                    Map.of("provider", "local", "publicDisplayName", "工单状态查询",
                            "publicActionSummary", "正在查询工单状态",
                            "publicArgumentKeys", List.of("ticketId"))
            ),
            new ToolDefinition(
                    "ticket_create",
                    "为用户问题创建支持工单。当用户要求创建工单或上报问题时使用。",
                    """
                            {"type":"object","properties":{"title":{"type":"string","description":"问题标题"},"priority":{"type":"string","enum":["P0","P1","P2","P3"],"description":"业务优先级"}},"required":["title"]}
                            """.strip(),
                    ToolRiskLevel.MEDIUM,
                    Map.of("provider", "local", "publicDisplayName", "创建支持工单",
                            "publicActionSummary", "正在创建支持工单",
                            "publicArgumentKeys", List.of("title", "priority"))
            ),
            new ToolDefinition(
                    "ticket_priority_update",
                    "更新工单优先级。此操作会改变业务处理优先级，属于高风险操作。",
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
                    "填写关闭原因并关闭支持工单。此操作会改变工单生命周期状态，属于高风险操作。",
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
        Map<String, ToolDefinition> mergedTools = new LinkedHashMap<>();
        tools.forEach(tool -> addUnique(mergedTools, tool, "local"));
        // 遍历 ToolCatalogContributor 的子类，把他们的工具整合到 mergedTools
        contributorStream().forEach(contributor -> contributor.definitions()
                .forEach(tool -> addUnique(mergedTools, tool, "contributor")));
        McpToolGateway gateway = mcpToolGatewayProvider == null ? null : mcpToolGatewayProvider.getIfAvailable();
        if (gateway != null) {
            gateway.discoverTools().forEach(tool -> addUnique(mergedTools, tool, "mcp"));
        }
        return List.copyOf(mergedTools.values());
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

    private Stream<ToolCatalogContributor> contributorStream() {
        return catalogContributors == null ? Stream.empty() : catalogContributors.orderedStream();
    }

    private void addUnique(Map<String, ToolDefinition> mergedTools, ToolDefinition definition, String source) {
        if (definition == null || definition.name() == null || definition.name().isBlank()) {
            throw new IllegalStateException("tool definition name must not be blank: " + source);
        }
        ToolDefinition previous = mergedTools.putIfAbsent(definition.name(), definition);
        if (previous != null) {
            throw new IllegalStateException("duplicate tool name '" + definition.name()
                    + "' between providers '" + providerOf(previous) + "' and '" + source + "'");
        }
    }

    private String providerOf(ToolDefinition definition) {
        Object provider = definition.metadata().get("provider");
        return provider == null ? "unknown" : String.valueOf(provider);
    }
}
