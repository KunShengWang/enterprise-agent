package com.agent.platform.procurement.workflow;

import com.agent.platform.procurement.model.*;

/** A projection of authoritative Case state, never a separately stored state machine. */
public enum ProcurementStage {
    REQUISITION, SOURCING, RFQ;
    public static ProcurementStage from(ProcurementCase value) {
        if (value == null || !value.state().missingFields().isEmpty()) return REQUISITION;
        return switch (value.status()) {
            case CLARIFICATION_REQUIRED -> REQUISITION;
            case SOURCING, NO_ELIGIBLE_SUPPLIER -> SOURCING;
            case RECOMMENDATION_READY -> RFQ;
        };
    }
}
