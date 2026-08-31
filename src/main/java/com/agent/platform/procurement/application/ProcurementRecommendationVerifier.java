package com.agent.platform.procurement.application;

import com.agent.platform.procurement.model.SourcingRecommendation;
import com.agent.platform.procurement.model.SupplierEvidence;

import java.util.Set;
import java.util.stream.Collectors;

/** 校验推荐只引用本次 Provider ToolResult 返回的 Evidence。 */
public final class ProcurementRecommendationVerifier {
    private ProcurementRecommendationVerifier() { }

    public static void verify(SourcingRecommendation recommendation, java.util.List<SupplierEvidence> evidence) {
        if (recommendation == null) throw new IllegalArgumentException("recommendation is required");
        Set<String> available = (evidence == null ? java.util.List.<SupplierEvidence>of() : evidence).stream()
                .map(SupplierEvidence::evidenceId).collect(Collectors.toUnmodifiableSet());
        if (!available.containsAll(recommendation.evidenceRefs())) {
            throw new IllegalArgumentException("recommendation references evidence outside the current ToolResult");
        }
        if (recommendation.recommendedSupplier() == null && !recommendation.evidenceRefs().isEmpty()) {
            throw new IllegalArgumentException("recommendation evidence requires a recommended supplier");
        }
    }
}
