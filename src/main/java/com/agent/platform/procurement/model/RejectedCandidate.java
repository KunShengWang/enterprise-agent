package com.agent.platform.procurement.model;

import java.util.List;

public record RejectedCandidate(SupplierCandidate supplier, List<String> reasons, List<String> evidenceRefs) {
    public RejectedCandidate {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
