package com.agent.platform.ordercare.incident.model;

import java.util.Set;

public enum IncidentAgentRole {
    ORDER_ANALYST(Set.of(EvidenceSubtype.ORDER_STATUS_SET)),
    INVENTORY_ANALYST(Set.of(EvidenceSubtype.INVENTORY_DEDUCT_SET, EvidenceSubtype.INVENTORY_INVARIANT)),
    MQ_ANALYST(Set.of(EvidenceSubtype.DEAD_LETTER_SET, EvidenceSubtype.QUEUE_RUNTIME_STATUS)),
    SOP_ANALYST(Set.of(EvidenceSubtype.SOP_GUIDANCE));

    private final Set<EvidenceSubtype> allowedEvidenceSubtypes;

    IncidentAgentRole(Set<EvidenceSubtype> allowedEvidenceSubtypes) {
        this.allowedEvidenceSubtypes = Set.copyOf(allowedEvidenceSubtypes);
    }

    public Set<EvidenceSubtype> allowedEvidenceSubtypes() {
        return allowedEvidenceSubtypes;
    }
}
