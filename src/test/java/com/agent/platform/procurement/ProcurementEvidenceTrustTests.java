package com.agent.platform.procurement;

import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.EvidenceIdFactory;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.model.SupplierOffer;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcurementEvidenceTrustTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementDataProperties properties = new ProcurementDataProperties();
    private final AwsSyntheticProcurementProvider provider = new AwsSyntheticProcurementProvider(mapper, properties);

    @Test
    void providerEvidenceIdBindsCompleteSourceProvenanceAndFact() {
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"),
                Map.of(), Set.of(), List.of(), "SOURCING");
        SupplierOffer offer = provider.getSupplierOffers(state, provider.searchSuppliers(state)).stream()
                .filter(value -> value.supplierId().equals("supplier-d")).findFirst().orElseThrow();
        SupplierEvidence evidence = provider.getSupplierEvidence("supplier-d", state).get(0);

        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), offer.sourceAsOf());
        assertNotEquals(EvidenceIdFactory.digest(offer.source(), offer.sourceRecordId(), offer.sourceSnapshot(),
                offer.productId() + "|" + offer.unitPrice() + "|" + offer.quantity()), offer.sourceDigest());
        assertEquals(EvidenceIdFactory.id(evidence.supplierId(), evidence.evidenceType(), evidence.source(),
                evidence.sourceRecordId(), evidence.sourceSnapshot(), evidence.sourceAsOf().toString(),
                evidence.sourceDigest(), evidence.fact()), evidence.evidenceId());
    }

    @Test
    void tamperedEvidenceIdOrProvenanceIsRejected() {
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"),
                Map.of(), Set.of(), List.of(), "SOURCING");
        SupplierEvidence evidence = provider.getSupplierEvidence("supplier-d", state).get(0);

        assertThrows(IllegalArgumentException.class, () -> new SupplierEvidence(
                evidence.evidenceId(), evidence.supplierId(), evidence.evidenceType(), evidence.source(),
                evidence.fact() + " 被篡改", evidence.collectedAt(), evidence.sourceRecordId(),
                evidence.sourceSnapshot(), evidence.sourceAsOf(), evidence.sourceDigest()));
        assertThrows(IllegalArgumentException.class, () -> new SupplierEvidence(
                evidence.evidenceId(), evidence.supplierId(), evidence.evidenceType(), evidence.source(),
                evidence.fact(), evidence.collectedAt(), evidence.sourceRecordId(), evidence.sourceSnapshot(),
                evidence.sourceAsOf(), "different-source-digest"));
    }

    @Test
    void offerRequiresExplicitSourceProvenance() {
        assertThrows(IllegalArgumentException.class, () -> new SupplierOffer(
                "supplier-x", "product-x", "产品", BigDecimal.ONE, "CNY", 1, null, 1,
                "1 年", Map.of(), "", Instant.parse("2026-01-01T00:00:00Z"),
                "record-x", "snapshot-x", Instant.parse("2026-01-01T00:00:00Z"), "digest-x"));
    }
}
