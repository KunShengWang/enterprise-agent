package com.agent.platform.procurement.application;

import com.agent.platform.procurement.model.ProcurementCasePatch;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Java 唯一负责校验 Patch、合并权威 CaseState 和计算派生字段的组件。 */
@Component
public class ProcurementCasePatchMerger {
    private static final int MAX_CONSTRAINTS = 32;
    private static final int MAX_PREFERENCES = 32;
    private static final int MAX_EXCLUDED_SUPPLIERS = 128;
    private static final int MAX_FIELDS_TO_CLEAR = 8;
    private static final Set<String> SUPPORTED_PREFERENCES = Set.of("deliveryPriority", "pricePriority");
    private static final Set<String> CLEARABLE_FIELDS = Set.of(
            "productCategory", "productDescription", "quantity", "budget", "requiredDeliveryDays");

    public ProcurementCaseState merge(ProcurementCaseState current, ProcurementCasePatch patch) {
        if (patch == null || !patch.hasChanges()) throw new IllegalArgumentException("procurement Case Patch must contain a change");
        ProcurementCaseState base = current == null ? ProcurementCaseState.empty() : current;
        validate(patch);

        String category = patch.fieldsToClear().contains("productCategory") ? ""
                : patch.productCategory() == null ? base.productCategory() : patch.productCategory();
        String description = patch.fieldsToClear().contains("productDescription") ? ""
                : patch.productDescription() == null ? base.productDescription() : patch.productDescription();
        Integer quantity = patch.fieldsToClear().contains("quantity") ? null
                : patch.quantity() == null ? base.quantity() : patch.quantity();
        BigDecimal budget = patch.fieldsToClear().contains("budget") ? null
                : patch.budget() == null ? base.budget() : patch.budget();
        String currency = patch.currency() == null ? base.currency() : patch.currency();
        Integer deliveryDays = patch.fieldsToClear().contains("requiredDeliveryDays") ? null
                : patch.requiredDeliveryDays() == null ? base.requiredDeliveryDays() : patch.requiredDeliveryDays();

        Map<String, String> hard = mergeMap(base.hardConstraints(), patch.hardConstraintsUpsert(), patch.hardConstraintsRemove());
        Map<String, String> preferences = mergeMap(base.preferences(), patch.preferencesUpsert(), patch.preferencesRemove());
        Set<String> excluded = new LinkedHashSet<>(base.excludedSuppliers());
        excluded.removeAll(patch.excludedSuppliersRemove());
        excluded.addAll(patch.excludedSuppliersAdd());

        List<String> missing = new ArrayList<>();
        if (description == null || description.isBlank()) missing.add("productDescription");
        if (quantity == null) missing.add("quantity");
        if (budget == null) missing.add("budget");
        String phase = missing.isEmpty() ? "SOURCING" : "REQUIREMENT_UNDERSTANDING";
        return new ProcurementCaseState(category, description, quantity, budget, currency, deliveryDays,
                hard, preferences, excluded, missing, phase);
    }

    public void validate(ProcurementCasePatch patch) {
        if (patch == null || !patch.hasChanges()) throw new IllegalArgumentException("procurement Case Patch must contain a change");
        if ((patch.productCategory() != null && patch.productCategory().isBlank())
                || (patch.productDescription() != null && patch.productDescription().isBlank())) {
            throw new IllegalArgumentException("Patch text fields must not be blank");
        }
        if (patch.productCategory() != null && patch.productCategory().length() > 100) {
            throw new IllegalArgumentException("productCategory exceeds 100 characters");
        }
        if (patch.productDescription() != null && patch.productDescription().length() > 500) {
            throw new IllegalArgumentException("productDescription exceeds 500 characters");
        }
        if (patch.quantity() != null && (patch.quantity() < 1 || patch.quantity() > 100_000)) {
            throw new IllegalArgumentException("quantity must be between 1 and 100000");
        }
        if (patch.budget() != null && (patch.budget().signum() < 0 || patch.budget().scale() > 4)) {
            throw new IllegalArgumentException("budget must be non-negative and have at most 4 decimal places");
        }
        if (patch.currency() != null && !patch.currency().matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("currency must be a three-letter code");
        }
        if (patch.requiredDeliveryDays() != null
                && (patch.requiredDeliveryDays() < 1 || patch.requiredDeliveryDays() > 3650)) {
            throw new IllegalArgumentException("requiredDeliveryDays must be between 1 and 3650");
        }
        validateMap("hardConstraints", patch.hardConstraintsUpsert(), MAX_CONSTRAINTS);
        validateMap("preferences", patch.preferencesUpsert(), MAX_PREFERENCES);
        validateSet("hardConstraintsRemove", patch.hardConstraintsRemove(), MAX_CONSTRAINTS);
        validateSet("preferencesRemove", patch.preferencesRemove(), MAX_PREFERENCES);
        validateSet("excludedSuppliersAdd", patch.excludedSuppliersAdd(), MAX_EXCLUDED_SUPPLIERS);
        validateSet("excludedSuppliersRemove", patch.excludedSuppliersRemove(), MAX_EXCLUDED_SUPPLIERS);
        validateSet("fieldsToClear", patch.fieldsToClear(), MAX_FIELDS_TO_CLEAR);
        if (!CLEARABLE_FIELDS.containsAll(patch.fieldsToClear())) {
            throw new IllegalArgumentException("Patch fieldsToClear contains a protected or unsupported field");
        }
        if ((patch.productCategory() != null && patch.fieldsToClear().contains("productCategory"))
                || (patch.productDescription() != null && patch.fieldsToClear().contains("productDescription"))
                || (patch.quantity() != null && patch.fieldsToClear().contains("quantity"))
                || (patch.budget() != null && patch.fieldsToClear().contains("budget"))
                || (patch.requiredDeliveryDays() != null && patch.fieldsToClear().contains("requiredDeliveryDays"))) {
            throw new IllegalArgumentException("a Patch field cannot be updated and cleared at the same time");
        }
        if (!disjoint(patch.hardConstraintsUpsert().keySet(), patch.hardConstraintsRemove())
                || !disjoint(patch.preferencesUpsert().keySet(), patch.preferencesRemove())
                || !disjoint(patch.excludedSuppliersAdd(), patch.excludedSuppliersRemove())) {
            throw new IllegalArgumentException("Patch add/upsert and remove operations must be disjoint");
        }
        for (String key : patch.hardConstraintsUpsert().keySet()) validateHardConstraint(key, patch.hardConstraintsUpsert().get(key));
        for (String key : patch.hardConstraintsRemove()) validateHardConstraintKey(key);
        if (!SUPPORTED_PREFERENCES.containsAll(patch.preferencesUpsert().keySet())
                || !SUPPORTED_PREFERENCES.containsAll(patch.preferencesRemove())) {
            throw new IllegalArgumentException("unsupported preference key");
        }
        if (patch.preferencesUpsert().values().stream().anyMatch(value -> !"HIGH".equals(value))) {
            throw new IllegalArgumentException("preference value must be HIGH");
        }
    }

    private Map<String, String> mergeMap(Map<String, String> current,
                                         Map<String, String> upsert,
                                         Set<String> remove) {
        Map<String, String> result = new LinkedHashMap<>(current == null ? Map.of() : current);
        result.keySet().removeAll(remove);
        result.putAll(upsert);
        return Map.copyOf(result);
    }

    private void validateMap(String name, Map<String, String> values, int maxSize) {
        if (values.size() > maxSize) throw new IllegalArgumentException(name + " contains too many entries");
        values.forEach((key, value) -> {
            if (blank(key) || blank(value)) throw new IllegalArgumentException(name + " key and value must not be blank");
            if (key.length() > 100 || value.length() > 200) throw new IllegalArgumentException(name + " key/value is too long");
        });
    }

    private void validateSet(String name, Set<String> values, int maxSize) {
        if (values.size() > maxSize) throw new IllegalArgumentException(name + " contains too many entries");
        if (values.stream().anyMatch(this::blank)) throw new IllegalArgumentException(name + " must not contain blank values");
        if (values.stream().filter(java.util.Objects::nonNull).anyMatch(value -> value.length() > 128)) {
            throw new IllegalArgumentException(name + " value is too long");
        }
    }

    private void validateHardConstraint(String key, String value) {
        validateHardConstraintKey(key);
        if ("gpuMemoryMinGb".equals(key)) {
            try {
                if (Integer.parseInt(value) < 1) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("gpuMemoryMinGb must be a positive integer");
            }
        }
    }

    private void validateHardConstraintKey(String key) {
        if (!ProcurementDecisionEngine.SUPPORTED_HARD_CONSTRAINTS.contains(key)) {
            throw new IllegalArgumentException("unsupported hard constraint: " + key);
        }
    }

    private boolean disjoint(Set<String> left, Set<String> right) {
        return left.stream().noneMatch(right::contains);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
