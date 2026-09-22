package com.agent.platform.procurement.model;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

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
        eligibleAlternatives = eligibleAlternatives == null ? List.of() : new java.util.ArrayList<>(eligibleAlternatives);
        alternativeOffers = alternativeOffers == null ? List.of() : new java.util.ArrayList<>(alternativeOffers);
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
        Set<String> eligibleAlternativeSupplierIds = new HashSet<>();
        for (SupplierCandidate alternative : eligibleAlternatives) {
            if (alternative == null) {
                throw new IllegalArgumentException("eligible alternatives must not contain null");
            }
            if (!eligibleAlternativeSupplierIds.add(alternative.supplierId())) {
                throw new IllegalArgumentException("eligible alternatives must not contain duplicate supplier ids");
            }
            if (alternative.supplierId().equals(recommendedSupplier.supplierId())) {
                throw new IllegalArgumentException("eligible alternatives must not contain the recommended supplier");
            }
        }
        Set<String> alternativeOfferSupplierIds = new HashSet<>();
        for (SupplierOffer offer : alternativeOffers) {
            if (offer == null) {
                throw new IllegalArgumentException("alternative offers must not contain null");
            }
            if (!alternativeOfferSupplierIds.add(offer.supplierId())) {
                throw new IllegalArgumentException("alternative offers must not contain duplicate supplier ids");
            }
            if (offer.supplierId().equals(recommendedSupplier.supplierId())) {
                throw new IllegalArgumentException("alternative offers must not contain the recommended supplier");
            }
        }
        if (!eligibleAlternativeSupplierIds.equals(alternativeOfferSupplierIds)) {
            throw new IllegalArgumentException("eligible alternatives and alternative offers must contain the same suppliers");
        }
        if (eligibleAlternatives.size() != alternativeOffers.size()) {
            throw new IllegalArgumentException("eligible alternatives and alternative offers must have the same size");
        }
        for (int index = 0; index < eligibleAlternatives.size(); index++) {
            if (!eligibleAlternatives.get(index).supplierId().equals(alternativeOffers.get(index).supplierId())) {
                throw new IllegalArgumentException("eligible alternatives and alternative offers must match by position");
            }
        }
        eligibleAlternatives = List.copyOf(eligibleAlternatives);
        alternativeOffers = List.copyOf(alternativeOffers);
        if (tradeoffDimensions.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("tradeoffDimensions must not contain null");
        }
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
