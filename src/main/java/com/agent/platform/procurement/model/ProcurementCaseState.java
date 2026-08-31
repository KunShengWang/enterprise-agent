package com.agent.platform.procurement.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 当前采购任务的结构化权威状态，不承担跨任务长期偏好。 */
public record ProcurementCaseState(
        String productCategory,
        String productDescription,
        Integer quantity,
        BigDecimal budget,
        String currency,
        Integer requiredDeliveryDays,
        Map<String, String> hardConstraints,
        Map<String, String> preferences,
        Set<String> excludedSuppliers,
        List<String> missingFields,
        String currentPhase
) {
    public ProcurementCaseState {
        productCategory = text(productCategory);
        productDescription = text(productDescription);
        quantity = quantity == null || quantity > 0 ? quantity : null;
        budget = budget == null || budget.signum() >= 0 ? budget : null;
        currency = currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase();
        requiredDeliveryDays = requiredDeliveryDays == null || requiredDeliveryDays > 0
                ? requiredDeliveryDays : null;
        hardConstraints = hardConstraints == null ? Map.of() : Map.copyOf(hardConstraints);
        preferences = preferences == null ? Map.of() : Map.copyOf(preferences);
        excludedSuppliers = excludedSuppliers == null ? Set.of() : Set.copyOf(excludedSuppliers);
        missingFields = missingFields == null ? List.of() : missingFields.stream().filter(v -> v != null && !v.isBlank()).toList();
        currentPhase = text(currentPhase);
    }

    public static ProcurementCaseState empty() {
        return new ProcurementCaseState("", "", null, null, "CNY", null,
                Map.of(), Map.of(), Set.of(), List.of("productDescription", "quantity", "budget"),
                "REQUIREMENT_UNDERSTANDING");
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
