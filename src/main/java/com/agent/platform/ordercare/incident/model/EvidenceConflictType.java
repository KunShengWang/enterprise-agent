package com.agent.platform.ordercare.incident.model;

public enum EvidenceConflictType {
    SCOPE_MISMATCH,
    TRUNCATED_RESULT,
    MISSING_EVIDENCE,
    COUNT_MISMATCH,
    SET_DIFFERENCE,
    TIME_SKEW,
    STALE_DATA,
    INVARIANT_VIOLATION,
    DUPLICATE_OR_AMBIGUOUS_MAPPING
}
