package com.agent.platform.procurement.model;

import java.util.List;

public record SourcingRecommendation(
        SupplierCandidate recommendedSupplier,
        List<SupplierCandidate> alternatives,
        List<String> matchedConstraints,
        List<RejectedCandidate> rejectedCandidates,
        List<String> tradeoffs,
        List<String> reasons,
        List<String> risks,
        List<String> evidenceRefs,
        List<String> uncertainties,
        double confidence
) {
    public SourcingRecommendation {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        matchedConstraints = matchedConstraints == null ? List.of() : List.copyOf(matchedConstraints);
        rejectedCandidates = rejectedCandidates == null ? List.of() : List.copyOf(rejectedCandidates);
        tradeoffs = tradeoffs == null ? List.of() : List.copyOf(tradeoffs);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        risks = risks == null ? List.of() : List.copyOf(risks);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        uncertainties = uncertainties == null ? List.of() : List.copyOf(uncertainties);
        confidence = Math.max(0, Math.min(1, confidence));
    }
}
