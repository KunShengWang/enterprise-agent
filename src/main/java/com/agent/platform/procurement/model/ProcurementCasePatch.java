package com.agent.platform.procurement.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * Agent 对当前用户输入提出的采购状态变更建议。
 *
 * <p>Patch 只包含可由用户意图改变的业务字段；Case 身份、版本和派生字段
 * 永远由服务端上下文或 Java 计算，不属于模型可写入的协议。</p>
 */
public record ProcurementCasePatch(
        String productCategory,
        String productDescription,
        Integer quantity,
        BigDecimal budget,
        String currency,
        Integer requiredDeliveryDays,
        Map<String, String> hardConstraintsUpsert,
        Set<String> hardConstraintsRemove,
        Map<String, String> preferencesUpsert,
        Set<String> preferencesRemove,
        Set<String> excludedSuppliersAdd,
        Set<String> excludedSuppliersRemove,
        Set<String> fieldsToClear
) {
    public ProcurementCasePatch {
        productCategory = normalizeNullable(productCategory);
        productDescription = normalizeNullable(productDescription);
        currency = normalizeNullable(currency);
        hardConstraintsUpsert = normalizeMap(hardConstraintsUpsert);
        hardConstraintsRemove = normalizeSet(hardConstraintsRemove);
        preferencesUpsert = normalizeMap(preferencesUpsert);
        preferencesRemove = normalizeSet(preferencesRemove);
        excludedSuppliersAdd = normalizeSet(excludedSuppliersAdd);
        excludedSuppliersRemove = normalizeSet(excludedSuppliersRemove);
        fieldsToClear = normalizeSet(fieldsToClear);
    }

    public boolean hasChanges() {
        return productCategory != null || productDescription != null || quantity != null || budget != null
                || currency != null || requiredDeliveryDays != null
                || !hardConstraintsUpsert.isEmpty() || !hardConstraintsRemove.isEmpty()
                || !preferencesUpsert.isEmpty() || !preferencesRemove.isEmpty()
                || !excludedSuppliersAdd.isEmpty() || !excludedSuppliersRemove.isEmpty()
                || !fieldsToClear.isEmpty();
    }

    private static String normalizeNullable(String value) {
        return value == null ? null : value.trim();
    }

    private static Map<String, String> normalizeMap(Map<String, String> values) {
        if (values == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key == null ? null : key.trim(), value == null ? null : value.trim()));
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> normalizeSet(Set<String> values) {
        if (values == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value == null ? null : value.trim()));
        return Collections.unmodifiableSet(result);
    }
}
