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
    public static final String CREATE_RFQ = "procurement_create_rfq";

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                new ToolDefinition(CASE_PATCH,
                        "提交本轮用户采购意图的结构化 Case Patch。Java 会校验、CAS 合并并重新计算 authoritative CaseState；不能携带身份、版本或派生字段。",
                        """
                                {"type":"object","additionalProperties":false,"properties":{
                                  "productCategory":{"type":"string","maxLength":100,"description":"采购对象的上位业务或商品类别，不是完整自然语言需求；不要包含数量、预算、交期、约束和偏好。"},
                                  "productDescription":{"type":"string","minLength":1,"maxLength":500,"description":"具体采购对象的简洁产品描述；不要重复已有独立字段表达的数量、预算、币种、交期、hard constraint 和 preference。"},
                                  "quantity":{"type":"integer","minimum":1,"maximum":100000},
                                  "budget":{"type":"number","minimum":0},
                                  "currency":{"type":"string","pattern":"^[A-Za-z]{3}$"},
                                  "requiredDeliveryDays":{"type":"integer","minimum":1,"maximum":3650},
                                  "hardConstraintsUpsert":{"type":"object","additionalProperties":false,"properties":{
                                    "gpuMemoryMinGb":{"type":"string","pattern":"^[1-9][0-9]*$","description":"GPU 显存最低 GB，使用正整数字符串；这是必须满足的 hard constraint。"}
                                  }},
                                  "hardConstraintsRemove":{"type":"array","items":{"type":"string","enum":["gpuMemoryMinGb"]}},
                                  "preferencesUpsert":{"type":"object","additionalProperties":false,"properties":{
                                    "deliveryPriority":{"type":"string","enum":["HIGH"],"description":"用户明确把交付速度作为优先权衡维度的软偏好。"},
                                    "pricePriority":{"type":"string","enum":["HIGH"],"description":"用户明确把价格作为优先权衡维度的软偏好。"}
                                  }},
                                  "preferencesRemove":{"type":"array","items":{"type":"string","enum":["deliveryPriority","pricePriority"]}},
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
                        ToolRiskLevel.LOW, metadata("确认供应商推荐", "正在验证供应商推荐与证据", List.of("selectedSupplierId", "evidenceRefs"), true, false)),
                new ToolDefinition(CREATE_RFQ,
                        "提出创建一个受控 RFQ 的意图。模型必须使用空 arguments；服务端会在人工审批前根据当前 Case 和本 Run 已验证的 Recommendation 重建具体 RFQ。只有用户明确要求发起 RFQ 且 Finalize 成功后才可调用；批准前不会创建外部资源。",
                        """
                                {"type":"object","additionalProperties":false,"properties":{
                                  "caseId":{"type":"string"},"caseVersion":{"type":"integer"},"supplierId":{"type":"string"},
                                  "productCategory":{"type":"string"},"productDescription":{"type":"string"},"quantity":{"type":"integer"},
                                  "currency":{"type":"string"},"requiredDeliveryDays":{"type":"integer"},"hardConstraints":{"type":"object"},
                                  "sourceRecommendationToolCallId":{"type":"string"},"idempotencyKey":{"type":"string"}
                                }}
                                """.strip(),
                        ToolRiskLevel.HIGH,
                        metadata("创建审批绑定的采购 RFQ", "正在创建审批绑定的采购 RFQ", List.of(), false, true,
                                "procurement-rfq-v1", true))
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
        return metadata(displayName, action, keys, readOnly, sideEffect,
                "procurement-sourcing-v4", false);
    }

    private Map<String, Object> metadata(String displayName, String action, List<String> keys,
                                         boolean readOnly, boolean sideEffect,
                                         String contractVersion, boolean singleUse) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "procurement");
        metadata.put("domain", "procurement");
        metadata.put("readOnly", readOnly);
        metadata.put("sideEffect", sideEffect);
        metadata.put("parallelSafe", false);
        if (singleUse) {
            metadata.put("singleUse", true);
        }
        metadata.put("contractVersion", contractVersion);
        metadata.put("publicDisplayName", displayName);
        metadata.put("publicActionSummary", action);
        metadata.put("publicArgumentKeys", keys);
        return Map.copyOf(metadata);
    }
}
