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
            "Inspect one FlowOrder recovery case by a known business identifier. Returns authoritative facts, a deterministic diagnosis code, hard risks, and server-owned candidate actions. Read only.",
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
            "Create one immutable FlowOrder recovery Proposal for an already diagnosed case. FlowOrder owns target selection, state fingerprint, effects, warnings, expiry, and the bound actionRequestId. No business side effect.",
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
            "Execute exactly one previously created immutable recovery Proposal. Only proposalId is model-visible; the server restores the approved version, fingerprint, preview digest and human approval evidence. High risk and always requires approval.",
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
