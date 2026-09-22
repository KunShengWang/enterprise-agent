package com.agent.platform.procurement.application;

import com.agent.platform.procurement.model.SourcingRecommendation;
import com.agent.platform.procurement.model.SupplierEvidence;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

/** 校验推荐只引用本次 Provider ToolResult 返回的 Evidence。 */
public final class ProcurementRecommendationVerifier {
    private ProcurementRecommendationVerifier() { }

    public static void verify(SourcingRecommendation recommendation, List<SupplierEvidence> evidence,
                              Set<String> eligibleSupplierIds) {
        if (recommendation == null) throw new IllegalArgumentException("recommendation is required");
        List<SupplierEvidence> currentEvidence = evidence == null ? List.of() : List.copyOf(evidence);
        Set<String> available = currentEvidence.stream().map(SupplierEvidence::evidenceId)
                .collect(Collectors.toUnmodifiableSet());
        if (!available.containsAll(recommendation.evidenceRefs())) {
            throw new IllegalArgumentException("recommendation references evidence outside the current ToolResult");
        }
        if (recommendation.recommendedSupplier() == null) {
            if (!recommendation.evidenceRefs().isEmpty()) {
                throw new IllegalArgumentException("recommendation evidence requires a recommended supplier");
            }
            return;
        }
        Map<String, SupplierEvidence> evidenceById = new HashMap<>();
        currentEvidence.forEach(value -> evidenceById.put(value.evidenceId(), value));
        boolean selectedOffer = recommendation.evidenceRefs().stream()
                .map(evidenceById::get)
                .anyMatch(value -> value != null && value.supplierId().equals(recommendation.recommendedSupplier().supplierId())
                        && "OFFER".equals(value.evidenceType()));
        if (!selectedOffer) {
            throw new IllegalArgumentException("recommendation must reference selected supplier OFFER evidence");
        }
        Set<String> eligible = eligibleSupplierIds == null ? Set.of() : Set.copyOf(eligibleSupplierIds);
        if (eligible.size() > 1) {
            if (recommendation.tradeoffDimensions().isEmpty()) {
                throw new IllegalArgumentException("multiple eligible suppliers require tradeoffDimensions");
            }
            boolean alternativeOffer = recommendation.evidenceRefs().stream()
                    .map(evidenceById::get)
                    .anyMatch(value -> value != null && "OFFER".equals(value.evidenceType())
                            && eligible.contains(value.supplierId())
                            && !value.supplierId().equals(recommendation.recommendedSupplier().supplierId()));
            if (!alternativeOffer) {
                throw new IllegalArgumentException("multiple eligible suppliers require alternative OFFER evidence");
            }
        }
    }
}
