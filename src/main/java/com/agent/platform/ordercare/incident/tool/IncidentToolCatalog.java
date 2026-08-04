package com.agent.platform.ordercare.incident.tool;

import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class IncidentToolCatalog implements ToolCatalogContributor {

    /**
     * 查询事故范围内的订单事实
     */
    public static final String ORDER_FACTS = "floworder_incident_order_facts";
    /**
     * 查询事故范围内的库存扣减和库存不变量事实
     */
    public static final String INVENTORY_FACTS = "floworder_incident_inventory_facts";
    /**
     * 查询事故范围内的持久化死信事实和 RabbitMQ 实时运行状态
     */
    public static final String MQ_FACTS = "floworder_incident_mq_facts";

    private static final String SNAPSHOT_SCHEMA = """
            {"type":"object","properties":{"snapshotId":{"type":"string","minLength":1}},"required":["snapshotId"],"additionalProperties":false}
            """.strip();

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                definition(ORDER_FACTS, "订单事实查询",
                        "读取不可变事故快照范围内的订单事实。绝不直接接收 requestId。"),
                definition(INVENTORY_FACTS, "库存事实查询",
                        "读取不可变事故快照范围内的扣减事实和库存不变量事实。"),
                definition(MQ_FACTS, "消息事实查询",
                        "先读取已持久化的死信事实，再观察 RabbitMQ 队列运行态；消息代理超时时返回部分事实。"));
    }

    private ToolDefinition definition(String name, String displayName, String description) {
        return new ToolDefinition(
                name,
                description,
                SNAPSHOT_SCHEMA,
                ToolRiskLevel.LOW,
                Map.of(
                        "provider", "floworder-incident",
                        "domain", "ordercare-incident",
                        "readOnly", true,
                        "scopeAuthority", "incident-store",
                        "publicDisplayName", displayName,
                        "publicActionSummary", "正在读取事故范围内的权威事实",
                        "publicArgumentKeys", List.of("snapshotId")
                )
        );
    }
}
