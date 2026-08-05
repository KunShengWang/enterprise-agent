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

    public static final String DELEGATE_ORDER_ANALYST = "delegate_order_analyst";
    public static final String DELEGATE_INVENTORY_ANALYST = "delegate_inventory_analyst";
    public static final String DELEGATE_MQ_ANALYST = "delegate_mq_analyst";
    public static final String REVIEW_INCIDENT_EVIDENCE = "review_incident_evidence";

    private static final String SNAPSHOT_SCHEMA = """
            {"type":"object","properties":{"snapshotId":{"type":"string","minLength":1}},"required":["snapshotId"],"additionalProperties":false}
            """.strip();

    private static final String DELEGATION_SCHEMA = """
            {"type":"object","properties":{"objective":{"type":"string","minLength":1,"maxLength":500}},"required":["objective"],"additionalProperties":false}
            """.strip();

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                definition(ORDER_FACTS, "订单事实查询",
                        "读取不可变事故快照范围内的订单事实。绝不直接接收 requestId。"),
                definition(INVENTORY_FACTS, "库存事实查询",
                        "读取不可变事故快照范围内的扣减事实和库存不变量事实。"),
                definition(MQ_FACTS, "消息事实查询",
                        "先读取已持久化的死信事实，再观察 RabbitMQ 队列运行态；消息代理超时时返回部分事实。"),
                delegationDefinition(DELEGATE_ORDER_ANALYST, "委派订单分析 Agent",
                        "委派只读订单 Specialist，在服务器绑定的事故快照内核对订单终态。"),
                delegationDefinition(DELEGATE_INVENTORY_ANALYST, "委派库存分析 Agent",
                        "委派只读库存 Specialist，在服务器绑定的事故快照内核对扣减、释放和库存不变量。"),
                delegationDefinition(DELEGATE_MQ_ANALYST, "委派消息分析 Agent",
                        "委派只读 MQ Specialist，核对持久化死信和 RabbitMQ 运行态。"),
                reviewerDefinition());
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
                        "singleUse", true,
                        "scopeAuthority", "incident-store",
                        "publicDisplayName", displayName,
                        "publicActionSummary", "正在读取事故范围内的权威事实",
                        "publicArgumentKeys", List.of("snapshotId")
                )
        );
    }

    private ToolDefinition delegationDefinition(String name, String displayName, String description) {
        return new ToolDefinition(
                name,
                description,
                DELEGATION_SCHEMA,
                ToolRiskLevel.LOW,
                Map.ofEntries(
                        Map.entry("provider", "floworder-incident-subagent"),
                        Map.entry("domain", "ordercare-incident"),
                        Map.entry("readOnly", true),
                        Map.entry("executionKind", "SUB_AGENT"),
                        Map.entry("parallelSafe", true),
                        Map.entry("singleUse", true),
                        Map.entry("initialOnly", true),
                        Map.entry("maxDelegationDepth", 1),
                        Map.entry("scopeAuthority", "trusted-execution-context"),
                        Map.entry("publicDisplayName", displayName),
                        Map.entry("publicActionSummary", "正在委派只读事故调查子 Agent"),
                        Map.entry("publicArgumentKeys", List.of("objective"))
                )
        );
    }

    private ToolDefinition reviewerDefinition() {
        return new ToolDefinition(
                REVIEW_INCIDENT_EVIDENCE,
                "仅在服务器已经完成 Specialist 汇合和 Java 一致性检查后，委派 Reviewer 审查规范化证据。",
                DELEGATION_SCHEMA,
                ToolRiskLevel.LOW,
                Map.ofEntries(
                        Map.entry("provider", "floworder-incident-subagent"),
                        Map.entry("domain", "ordercare-incident"),
                        Map.entry("readOnly", true),
                        Map.entry("executionKind", "SUB_AGENT"),
                        Map.entry("parallelSafe", false),
                        Map.entry("singleUse", true),
                        Map.entry("maxDelegationDepth", 1),
                        Map.entry("stateGate", "REVIEWING"),
                        Map.entry("requiredFollowUpType", "REVIEW_READY"),
                        Map.entry("scopeAuthority", "trusted-execution-context"),
                        Map.entry("publicDisplayName", "委派事故证据 Reviewer"),
                        Map.entry("publicActionSummary", "正在审查已汇合的事故证据"),
                        Map.entry("publicArgumentKeys", List.of("objective"))
                ));
    }
}
