package com.agent.platform.ordercare.incident.tool;

import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class IncidentToolCatalog implements ToolCatalogContributor {

    public static final String ORDER_FACTS = "floworder_incident_order_facts";
    public static final String INVENTORY_FACTS = "floworder_incident_inventory_facts";
    public static final String MQ_FACTS = "floworder_incident_mq_facts";

    private static final String SNAPSHOT_SCHEMA = """
            {"type":"object","properties":{"snapshotId":{"type":"string","minLength":1}},"required":["snapshotId"],"additionalProperties":false}
            """.strip();

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                definition(ORDER_FACTS, "订单事实查询",
                        "Read bounded order facts for an immutable incident snapshot. Never accepts requestIds directly."),
                definition(INVENTORY_FACTS, "库存事实查询",
                        "Read bounded deduct and inventory invariant facts for an immutable incident snapshot."),
                definition(MQ_FACTS, "消息事实查询",
                        "Read persisted dead-letter facts first, then observe RabbitMQ queue runtime; Broker timeout returns partial facts."));
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
