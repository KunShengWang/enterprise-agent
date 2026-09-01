package com.agent.platform.procurement.model;

import java.util.List;

/** Agent 基于当前 Eligibility/Evidence ToolResult 提出的推荐草案。 */
public record ProcurementRecommendationDraft(
        long evaluatedCaseVersion,
        String selectedSupplierId,
        List<String> evidenceRefs,
        List<String> reasons,
        List<String> tradeoffs,
        List<String> risks,
        List<String> uncertainties,
        double confidence
) {
    public ProcurementRecommendationDraft {
        selectedSupplierId = selectedSupplierId == null ? "" : selectedSupplierId.trim();
        evidenceRefs = distinct(evidenceRefs);
        reasons = copy(reasons);
        tradeoffs = copy(tradeoffs);
        risks = copy(risks);
        uncertainties = copy(uncertainties);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream().map(value -> value == null ? "" : value.trim()).toList();
    }

    private static List<String> distinct(List<String> values) {
        return copy(values).stream().filter(value -> !value.isBlank()).distinct().toList();
    }
}
