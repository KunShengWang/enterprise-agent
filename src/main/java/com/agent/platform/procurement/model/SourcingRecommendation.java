package com.agent.platform.procurement.model;

import java.util.List;

public record SourcingRecommendation(
        SupplierCandidate recommendedSupplier,
        SupplierOffer selectedOffer,
        List<SupplierCandidate> eligibleAlternatives,
        List<SupplierOffer> alternativeOffers,
        List<String> matchedConstraints,
        List<RejectedCandidate> rejectedCandidates,
        List<String> evidenceRefs,
        List<ProcurementTradeoffDimension> tradeoffDimensions,
        double confidence
) {
    public SourcingRecommendation {
        eligibleAlternatives = eligibleAlternatives == null ? List.of() : List.copyOf(eligibleAlternatives);
        alternativeOffers = alternativeOffers == null ? List.of() : List.copyOf(alternativeOffers);
        matchedConstraints = matchedConstraints == null ? List.of() : List.copyOf(matchedConstraints);
        rejectedCandidates = rejectedCandidates == null ? List.of() : List.copyOf(rejectedCandidates);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        tradeoffDimensions = tradeoffDimensions == null ? List.of() : List.copyOf(tradeoffDimensions);
        if (recommendedSupplier == null || selectedOffer == null) {
            throw new IllegalArgumentException("recommended supplier and selected offer are required");
        }
        if (!recommendedSupplier.supplierId().equals(selectedOffer.supplierId())) {
            throw new IllegalArgumentException("selected offer must belong to the recommended supplier");
        }
        if (alternativeOffers.stream().anyMatch(offer -> offer == null || offer.supplierId().equals(recommendedSupplier.supplierId()))) {
            throw new IllegalArgumentException("alternative offers must belong to eligible alternatives");
        }
        if (tradeoffDimensions.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("tradeoffDimensions must not contain null");
        }
        confidence = Math.max(0, Math.min(1, confidence));
    }
}
