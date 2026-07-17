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

    private static final ToolDefinition CASE_INSPECT_DEFINITION = new ToolDefinition(
            CASE_INSPECT,
            "Inspect one FlowOrder recovery case by a known business identifier. Returns authoritative facts, a deterministic diagnosis code, hard risks, and server-owned candidate actions. Read only.",
            """
                    {"type":"object","additionalProperties":false,"properties":{"identifierType":{"type":"string","enum":["REQUEST_ID","ORDER_NO","DEDUCT_NO","DEAD_LETTER_ID"]},"identifierValue":{"type":"string","minLength":1,"maxLength":128},"symptom":{"type":"string","maxLength":500}},"required":["identifierType","identifierValue"]}
                    """.strip(),
            ToolRiskLevel.LOW,
            Map.of("provider", "floworder", "domain", "ordercare", "readOnly", true,
                    "contractVersion", "floworder-recovery-case-v1")
    );

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(CASE_INSPECT_DEFINITION);
    }
}
