package com.agent.platform.ordercare.tool;

import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class OrderCareToolCatalog implements ToolCatalogContributor {

    public static final String CASE_INSPECT = "floworder_case_inspect";
    public static final String RECOVERY_PREVIEW = "floworder_recovery_preview";
    public static final String RECOVERY_EXECUTE = "floworder_recovery_execute";

    private static final ToolDefinition CASE_INSPECT_DEFINITION = new ToolDefinition(
            CASE_INSPECT,
            "根据已知业务标识检查一个 FlowOrder 恢复案例。返回权威事实、确定性诊断代码、硬风险和服务端控制的候选操作。只读。",
            """
                    {"type":"object","additionalProperties":false,"properties":{"identifierType":{"type":"string","enum":["REQUEST_ID","ORDER_NO","DEDUCT_NO","DEAD_LETTER_ID"]},"identifierValue":{"type":"string","minLength":1,"maxLength":128},"symptom":{"type":"string","maxLength":500}},"required":["identifierType","identifierValue"]}
                    """.strip(),
            ToolRiskLevel.LOW,
            Map.of("provider", "floworder", "domain", "ordercare", "readOnly", true,
                    "contractVersion", "floworder-recovery-case-v1",
                    "publicDisplayName", "订单案例查询", "publicActionSummary", "正在读取订单恢复事实",
                    "publicArgumentKeys", List.of("identifierType", "identifierValue"))
    );

    private static final ToolDefinition RECOVERY_PREVIEW_DEFINITION = new ToolDefinition(
            RECOVERY_PREVIEW,
            "为已经诊断的案例创建一个不可变 FlowOrder 恢复 Proposal。目标选择、状态指纹、影响、警告、过期时间和绑定的 actionRequestId 均由 FlowOrder 管理。不会产生业务副作用。",
            """
                    {"type":"object","additionalProperties":false,"properties":{"identifierType":{"type":"string","enum":["REQUEST_ID","ORDER_NO","DEDUCT_NO","DEAD_LETTER_ID"]},"identifierValue":{"type":"string","minLength":1,"maxLength":128},"suggestedReason":{"type":"string","maxLength":500}},"required":["identifierType","identifierValue"]}
                    """.strip(),
            ToolRiskLevel.LOW,
            Map.of("provider", "floworder", "domain", "ordercare", "readOnly", true,
                    "contractVersion", "floworder-recovery-proposal-v1",
                    "publicDisplayName", "恢复预演", "publicActionSummary", "正在创建无副作用恢复预演",
                    "publicArgumentKeys", List.of("identifierType", "identifierValue"))
    );

    private static final ToolDefinition RECOVERY_EXECUTE_DEFINITION = new ToolDefinition(
            RECOVERY_EXECUTE,
            "只执行一个先前创建的不可变恢复 Proposal。模型只能看到 proposalId；服务端负责恢复已审批的版本、状态指纹、预演摘要和人工审批凭据。此操作风险高，始终需要审批。",
            """
                    {"type":"object","additionalProperties":false,"properties":{"proposalId":{"type":"string","minLength":20,"maxLength":128}},"required":["proposalId"]}
                    """.strip(),
            ToolRiskLevel.HIGH,
            Map.of("provider", "floworder", "domain", "ordercare", "readOnly", false,
                    "sideEffect", true, "contractVersion", "floworder-recovery-proposal-v1",
                    "publicDisplayName", "执行恢复", "publicActionSummary", "正在执行已审批的恢复方案",
                    "publicArgumentKeys", List.of("proposalId"))
    );

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(CASE_INSPECT_DEFINITION, RECOVERY_PREVIEW_DEFINITION, RECOVERY_EXECUTE_DEFINITION);
    }
}
