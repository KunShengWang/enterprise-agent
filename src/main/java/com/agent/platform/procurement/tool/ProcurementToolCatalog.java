package com.agent.platform.procurement.tool;

import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProcurementToolCatalog implements ToolCatalogContributor {
    public static final String CASE_PATCH = "procurement_case_patch";
    public static final String SUPPLIER_SEARCH = "procurement_supplier_search";
    public static final String SUPPLIER_EVIDENCE = "procurement_supplier_evidence";
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
                new ToolDefinition(SUPPLIER_EVIDENCE,
                        "只读查询当前 Provider 快照中指定供应商的报价、规格和质保证据。",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"supplierId\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128}},\"required\":[\"supplierId\"]}",
                        ToolRiskLevel.LOW, metadata("供应商证据", "正在查询供应商证据", List.of("supplierId"), true, false)),
                new ToolDefinition(RECOMMENDATION_FINALIZE,
                        "提交 Agent 基于 Eligibility/Evidence 的供应商推荐草案；Java 重新读取当前 Case 和 Provider 快照，验证版本、资格与证据后返回 canonical Recommendation。不会修改采购 Case 或执行采购业务副作用。",
                        """
                                {"type":"object","additionalProperties":false,"properties":{
                                  "evaluatedCaseVersion":{"type":"integer","minimum":0},
                                  "selectedSupplierId":{"type":"string","minLength":1,"maxLength":128},
                                  "evidenceRefs":{"type":"array","minItems":1,"items":{"type":"string","minLength":1}},
                                  "reasons":{"type":"array","items":{"type":"string","minLength":1}},
                                  "tradeoffs":{"type":"array","items":{"type":"string","minLength":1}},
                                  "risks":{"type":"array","items":{"type":"string","minLength":1}},
                                  "uncertainties":{"type":"array","items":{"type":"string","minLength":1}},
                                  "confidence":{"type":"number","minimum":0,"maximum":1}
                                },"required":["evaluatedCaseVersion","selectedSupplierId","evidenceRefs","reasons","tradeoffs","risks","uncertainties","confidence"]}
                                """.strip(),
                        ToolRiskLevel.LOW, metadata("确认供应商推荐", "正在验证供应商推荐与证据", List.of("selectedSupplierId", "evidenceRefs"), true, false))
        );
    }

    private Map<String, Object> metadata(String displayName, String action, List<String> keys,
                                         boolean readOnly, boolean sideEffect) {
        return Map.of("provider", "procurement", "domain", "procurement", "readOnly", readOnly,
                "sideEffect", sideEffect, "parallelSafe", false, "contractVersion", "procurement-sourcing-v2",
                "publicDisplayName", displayName, "publicActionSummary", action, "publicArgumentKeys", keys);
    }
}
