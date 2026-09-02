package com.agent.platform.procurement.tool;

import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ProcurementToolCatalog implements ToolCatalogContributor {
    public static final String CASE_PATCH = "procurement_case_patch";
    public static final String SUPPLIER_SEARCH = "procurement_supplier_search";
    public static final String COMMERCIAL_ANALYSIS = "procurement_commercial_analysis";
    public static final String DELIVERY_ANALYSIS = "procurement_delivery_analysis";
    public static final String RECOMMENDATION_FINALIZE = "procurement_recommendation_finalize";

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                new ToolDefinition(CASE_PATCH,
                        "提交本轮用户采购意图的结构化 Case Patch。Java 会校验、CAS 合并并重新计算 authoritative CaseState；不能携带身份、版本或派生字段。",
                        """
                                {"type":"object","additionalProperties":false,"properties":{
                                  "productCategory":{"type":"string","maxLength":100},
                                  "productDescription":{"type":"string","minLength":1,"maxLength":500},
                                  "quantity":{"type":"integer","minimum":1,"maximum":100000},
                                  "budget":{"type":"number","minimum":0},
                                  "currency":{"type":"string","pattern":"^[A-Za-z]{3}$"},
                                  "requiredDeliveryDays":{"type":"integer","minimum":1,"maximum":3650},
                                  "hardConstraintsUpsert":{"type":"object"},"hardConstraintsRemove":{"type":"array","items":{"type":"string"}},
                                  "preferencesUpsert":{"type":"object"},"preferencesRemove":{"type":"array","items":{"type":"string"}},
                                  "excludedSuppliersAdd":{"type":"array","items":{"type":"string"}},"excludedSuppliersRemove":{"type":"array","items":{"type":"string"}},
                                  "fieldsToClear":{"type":"array","items":{"type":"string","enum":["productCategory","productDescription","quantity","budget","requiredDeliveryDays"]}}
                                }}
                                """.strip(),
                        ToolRiskLevel.LOW, metadata("采购需求 Patch", "正在合并采购需求状态", List.of("productDescription", "quantity", "budget"), false, true)),
                new ToolDefinition(SUPPLIER_SEARCH,
                        "只读读取当前 authoritative ProcurementCaseState，查询采购供应商报价并由 Java 计算 Eligibility。不会接受模型传入的权威数量、预算或约束。",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}",
                        ToolRiskLevel.LOW, metadata("供应商寻源", "正在查询采购供应商和报价", List.of(), true, false)),
                specialist(COMMERCIAL_ANALYSIS,
                        "只读分析已完成供应商寻源中的价格、预算、质保等商业权衡。仅在多个 Eligible 候选存在实质商业 trade-off 时调用；输入事实由 Java 从当前 Search 结果提供。",
                        "商业分析"),
                specialist(DELIVERY_ANALYSIS,
                        "只读分析已完成供应商寻源中的交期、交付余量等交付权衡。仅在多个 Eligible 候选存在实质交付 trade-off 时调用；输入事实由 Java 从当前 Search 结果提供。",
                        "交付分析"),
                new ToolDefinition(RECOMMENDATION_FINALIZE,
                        "提交 Agent 基于 Eligibility/Evidence 的供应商推荐草案；Java 重新读取当前 Case 和 Provider 快照，验证版本、资格与证据后返回 canonical Recommendation。不会修改采购 Case 或执行采购业务副作用。",
                        """
                                {"type":"object","additionalProperties":false,"properties":{
                                  "evaluatedCaseVersion":{"type":"integer","minimum":0},
                                  "selectedSupplierId":{"type":"string","minLength":1,"maxLength":128},
                                  "evidenceRefs":{"type":"array","minItems":1,"items":{"type":"string","minLength":1}},
                                  "tradeoffDimensions":{"type":"array","items":{"type":"string","enum":["PRICE","DELIVERY","WARRANTY","SPECIFICATION"]}},
                                  "confidence":{"type":"number","minimum":0,"maximum":1}
                                },"required":["evaluatedCaseVersion","selectedSupplierId","evidenceRefs","tradeoffDimensions","confidence"]}
                                """.strip(),
                        ToolRiskLevel.LOW, metadata("确认供应商推荐", "正在验证供应商推荐与证据", List.of("selectedSupplierId", "evidenceRefs"), true, false))
        );
    }

    private ToolDefinition specialist(String name, String description, String displayName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "procurement");
        metadata.put("domain", "procurement");
        metadata.put("readOnly", true);
        metadata.put("sideEffect", false);
        metadata.put("parallelSafe", true);
        metadata.put("executionKind", "SUB_AGENT");
        metadata.put("singleUse", true);
        metadata.put("contractVersion", "procurement-specialist-v1");
        metadata.put("publicDisplayName", displayName);
        metadata.put("publicActionSummary", "正在执行采购维度分析");
        metadata.put("publicArgumentKeys", List.of());
        return new ToolDefinition(name, description,
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}",
                ToolRiskLevel.LOW,
                Map.copyOf(metadata));
    }

    private Map<String, Object> metadata(String displayName, String action, List<String> keys,
                                         boolean readOnly, boolean sideEffect) {
        return Map.of("provider", "procurement", "domain", "procurement", "readOnly", readOnly,
                "sideEffect", sideEffect, "parallelSafe", false, "contractVersion", "procurement-sourcing-v3",
                "publicDisplayName", displayName, "publicActionSummary", action, "publicArgumentKeys", keys);
    }
}
