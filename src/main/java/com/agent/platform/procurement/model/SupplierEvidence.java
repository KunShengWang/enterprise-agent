package com.agent.platform.procurement.model;

import java.time.Instant;
import java.util.Locale;

public record SupplierEvidence(String evidenceId, String supplierId, String evidenceType,
                               String source, String fact, Instant collectedAt,
                               String sourceRecordId, String sourceSnapshot, Instant sourceAsOf,
                               String sourceDigest) {
    public SupplierEvidence {
        if (evidenceId == null || evidenceId.isBlank() || supplierId == null || supplierId.isBlank()
                || evidenceType == null || evidenceType.isBlank() || fact == null || fact.isBlank()
                || collectedAt == null || source == null || source.isBlank()
                || sourceRecordId == null || sourceRecordId.isBlank() || sourceSnapshot == null
                || sourceSnapshot.isBlank() || sourceAsOf == null || sourceDigest == null
                || sourceDigest.isBlank()) throw new IllegalArgumentException("invalid supplier evidence provenance");
        evidenceId = evidenceId.trim(); supplierId = supplierId.trim();
        evidenceType = evidenceType.trim().toUpperCase(Locale.ROOT);
        source = source.trim(); fact = fact.trim(); sourceRecordId = sourceRecordId.trim();
        sourceSnapshot = sourceSnapshot.trim(); sourceDigest = sourceDigest.trim();
        String expectedId = EvidenceIdFactory.id(supplierId, evidenceType, source, sourceRecordId,
                sourceSnapshot, sourceAsOf.toString(), sourceDigest, fact);
        if (!evidenceId.equals(expectedId)) throw new IllegalArgumentException("supplier evidence id does not match provenance");
    }
}
