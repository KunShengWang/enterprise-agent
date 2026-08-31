package com.agent.platform.procurement.model;

import java.time.Instant;

public record SupplierEvidence(String evidenceId, String supplierId, String evidenceType,
                               String source, String fact, Instant collectedAt,
                               String sourceRecordId, String sourceSnapshot, Instant sourceAsOf,
                               String sourceDigest) {
    public SupplierEvidence(String evidenceId, String supplierId, String evidenceType,
                             String source, String fact, Instant collectedAt) {
        this(evidenceId, supplierId, evidenceType, source, fact, collectedAt, "", source, collectedAt, "");
    }

    public SupplierEvidence {
        if (evidenceId == null || evidenceId.isBlank() || supplierId == null || supplierId.isBlank()
                || evidenceType == null || evidenceType.isBlank() || fact == null || fact.isBlank()
                || collectedAt == null) throw new IllegalArgumentException("invalid supplier evidence");
        evidenceId = evidenceId.trim(); supplierId = supplierId.trim(); evidenceType = evidenceType.trim();
        source = source == null ? "" : source.trim(); fact = fact.trim();
        sourceRecordId = sourceRecordId == null || sourceRecordId.isBlank() ? evidenceId : sourceRecordId.trim();
        sourceSnapshot = sourceSnapshot == null || sourceSnapshot.isBlank() ? source : sourceSnapshot.trim();
        sourceAsOf = sourceAsOf == null ? collectedAt : sourceAsOf;
        sourceDigest = sourceDigest == null || sourceDigest.isBlank()
                ? EvidenceIdFactory.digest(source, sourceRecordId, sourceSnapshot, fact) : sourceDigest.trim();
    }
}
