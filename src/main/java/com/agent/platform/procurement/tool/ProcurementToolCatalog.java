package com.agent.platform.procurement.tool;

import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProcurementToolCatalog implements ToolCatalogContributor {
    public static final String SUPPLIER_SEARCH = "procurement_supplier_search";
    public static final String SUPPLIER_EVIDENCE = "procurement_supplier_evidence";

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                new ToolDefinition(SUPPLIER_SEARCH,
                        "只读查询采购商品、供应商报价，并由 Java 计算总价、预算、排除供应商和硬约束结果。采购事实必须来自返回结果。",
                        "{" +
                                "\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" +
                                "\"productDescription\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":500}," +
                                "\"productCategory\":{\"type\":\"string\",\"maxLength\":100},\"quantity\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100000}," +
                                "\"budget\":{\"type\":\"number\",\"minimum\":0},\"currency\":{\"type\":\"string\",\"maxLength\":3}," +
                                "\"requiredDeliveryDays\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":3650},\"hardConstraints\":{\"type\":\"object\"}," +
                                "\"preferences\":{\"type\":\"object\"},\"excludedSuppliers\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}," +
                                "\"required\":[\"productDescription\"]}"
                                ,
                        ToolRiskLevel.LOW, metadata("供应商与报价查询", "正在查询采购供应商和报价", List.of("productDescription", "quantity", "budget"))),
                new ToolDefinition(SUPPLIER_EVIDENCE,
                        "只读查询指定供应商的报价、规格和质保证据；不会创建 RFQ、PO 或任何采购副作用。",
                        "{" + "\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" +
                                "\"supplierId\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128},\"productDescription\":{\"type\":\"string\",\"maxLength\":500}," +
                                "\"productCategory\":{\"type\":\"string\",\"maxLength\":100},\"quantity\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100000},\"hardConstraints\":{\"type\":\"object\"}}," +
                                "\"required\":[\"supplierId\",\"productDescription\"]}",
                        ToolRiskLevel.LOW, metadata("供应商证据查询", "正在查询供应商证据", List.of("supplierId", "productDescription"))));
    }

    private Map<String, Object> metadata(String displayName, String action, List<String> keys) {
        return Map.of("provider", "procurement", "domain", "procurement", "readOnly", true,
                "sideEffect", false, "parallelSafe", true, "contractVersion", "procurement-sourcing-v1",
                "publicDisplayName", displayName, "publicActionSummary", action, "publicArgumentKeys", keys);
    }
}
