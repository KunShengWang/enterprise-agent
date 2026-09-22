package com.agent.platform.procurement;

import com.agent.platform.procurement.application.ProcurementCaseVersionConflictException;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.application.ProcurementRecommendationFinalizer;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementCaseStatus;
import com.agent.platform.procurement.model.ProcurementRecommendationDraft;
import com.agent.platform.procurement.model.ProcurementTradeoffDimension;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcurementRecommendationFinalizeTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementDataProperties properties = new ProcurementDataProperties();
    private final AwsSyntheticProcurementProvider provider = new AwsSyntheticProcurementProvider(mapper, properties);

    @Test
    void finalizerAcceptsAgentChoiceAmongMultipleEligibleSuppliersWithBothOfferEvidence() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, completeState(), 4);
        String supplierBOffer = provider.getSupplierEvidence("supplier-b", current.state()).get(0).evidenceId();
        String supplierDOffer = provider.getSupplierEvidence("supplier-d", current.state()).get(0).evidenceId();
        var providerOffers = provider.getSupplierOffers(current.state(), provider.searchSuppliers(current.state()));
        var expectedSupplierBOffer = providerOffers.stream().filter(offer -> offer.supplierId().equals("supplier-b"))
                .findFirst().orElseThrow();
        var expectedSupplierDOffer = providerOffers.stream().filter(offer -> offer.supplierId().equals("supplier-d"))
                .findFirst().orElseThrow();

        ProcurementRecommendationFinalizer.Finalization result = finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-d",
                        List.of(supplierBOffer, supplierDOffer), List.of(ProcurementTradeoffDimension.DELIVERY,
                                ProcurementTradeoffDimension.PRICE)));

        assertEquals("Supplier D", result.recommendation().recommendedSupplier().supplierName());
        assertOfferMatchesProviderSnapshot(expectedSupplierDOffer, result.recommendation().selectedOffer());
        assertEquals("supplier-d", result.recommendation().selectedOffer().supplierId());
        assertEquals(new BigDecimal("580000"), result.recommendation().selectedOffer().totalPrice());
        assertEquals(12, result.recommendation().selectedOffer().leadTimeDays());
        assertEquals(List.of("supplier-b"), result.recommendation().eligibleAlternatives().stream()
                .map(value -> value.supplierId()).toList());
        assertOfferMatchesProviderSnapshot(expectedSupplierBOffer, result.recommendation().alternativeOffers().get(0));
        assertEquals(new BigDecimal("550000"), result.recommendation().alternativeOffers().get(0).totalPrice());
        assertEquals(18, result.recommendation().alternativeOffers().get(0).leadTimeDays());
        assertEquals(List.of(ProcurementTradeoffDimension.DELIVERY, ProcurementTradeoffDimension.PRICE),
                result.recommendation().tradeoffDimensions());
        assertEquals(Set.of("recommendedSupplier", "selectedOffer", "eligibleAlternatives", "alternativeOffers",
                        "matchedConstraints", "rejectedCandidates", "evidenceRefs", "tradeoffDimensions", "confidence"),
                Arrays.stream(result.recommendation().getClass().getRecordComponents())
                        .map(component -> component.getName()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, result.evaluation().candidates().stream()
                .filter(ProcurementDecisionEngine.CandidateResult::eligible).count());
    }

    @Test
    void finalizerRejectsIneligibleSupplierAndUnknownEvidence() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, stateWithExcludedSupplier(), 1);
        String excludedEvidence = provider.getSupplierEvidence("supplier-a", current.state()).get(0).evidenceId();

        assertThrows(IllegalArgumentException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-a",
                        List.of(excludedEvidence), List.of())));
        assertThrows(IllegalArgumentException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-b",
                        List.of("evidence-not-from-provider"), List.of(ProcurementTradeoffDimension.PRICE))));
    }

    @Test
    void finalizerFailsClosedForStaleCaseVersion() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, completeState(), 3);
        String evidence = provider.getSupplierEvidence("supplier-b", current.state()).get(0).evidenceId();

        assertThrows(ProcurementCaseVersionConflictException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version() - 1, "supplier-b",
                        List.of(evidence), List.of(ProcurementTradeoffDimension.PRICE))));
    }

    @Test
    void finalizerRequiresSelectedOfferEvidenceNotWarrantyOnly() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, completeState(), 1);
        String warranty = provider.getSupplierEvidence("supplier-d", current.state()).get(1).evidenceId();

        assertThrows(IllegalArgumentException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-d",
                        List.of(warranty), List.of(ProcurementTradeoffDimension.DELIVERY))));
    }

    @Test
    void multiEligibleFinalizerRequiresTradeoffDimensionsAndAlternativeOfferEvidence() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, completeState(), 1);
        String supplierDOffer = provider.getSupplierEvidence("supplier-d", current.state()).get(0).evidenceId();
        String supplierBOffer = provider.getSupplierEvidence("supplier-b", current.state()).get(0).evidenceId();

        assertThrows(IllegalArgumentException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-d",
                        List.of(supplierDOffer), List.of())));
        assertThrows(IllegalArgumentException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-d",
                        List.of(supplierDOffer), List.of(ProcurementTradeoffDimension.DELIVERY))));
        // The complete pair is accepted by the first test; this assertion keeps the alternative evidence explicit.
        var result = finalizer(store).finalize("tenant", "buyer", "conversation",
                draft(current.version(), "supplier-d", List.of(supplierDOffer, supplierBOffer), List.of(ProcurementTradeoffDimension.DELIVERY)));
        assertEquals("supplier-d", result.recommendation().recommendedSupplier().supplierId());
    }

    @Test
    void finalizerRejectsCaseWithNoEligibleSupplier() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCaseState state = new ProcurementCaseState(
                "计算工作站", "CUDA 开发工作站", 50, new BigDecimal("400000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "64"), Map.of(), Set.of(), List.of(), "SOURCING");
        ProcurementCase current = save(store, state, 0);
        String evidence = provider.getSupplierEvidence("supplier-b", current.state()).get(0).evidenceId();

        assertThrows(IllegalArgumentException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-b",
                        List.of(evidence), List.of())));
    }

    private ProcurementRecommendationFinalizer finalizer(MemoryCaseStore store) {
        return new ProcurementRecommendationFinalizer(store, provider, new ProcurementDecisionEngine());
    }

    private ProcurementCaseState completeState() {
        return new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"),
                Map.of("deliveryPriority", "HIGH"), Set.of("Supplier A"), List.of(), "SOURCING");
    }

    private ProcurementCaseState stateWithExcludedSupplier() {
        return new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"),
                Map.of(), Set.of("Supplier A"), List.of(), "SOURCING");
    }

    private ProcurementRecommendationDraft draft(long version, String supplierId, List<String> evidenceRefs,
                                                 List<ProcurementTradeoffDimension> tradeoffDimensions) {
        return new ProcurementRecommendationDraft(version, supplierId, evidenceRefs, tradeoffDimensions, 0.82);
    }

    private void assertOfferMatchesProviderSnapshot(com.agent.platform.procurement.model.SupplierOffer expected,
                                                    com.agent.platform.procurement.model.SupplierOffer actual) {
        assertEquals(expected.supplierId(), actual.supplierId());
        assertEquals(expected.productId(), actual.productId());
        assertEquals(expected.productName(), actual.productName());
        assertEquals(expected.unitPrice(), actual.unitPrice());
        assertEquals(expected.currency(), actual.currency());
        assertEquals(expected.quantity(), actual.quantity());
        assertEquals(expected.totalPrice(), actual.totalPrice());
        assertEquals(expected.leadTimeDays(), actual.leadTimeDays());
        assertEquals(expected.warranty(), actual.warranty());
        assertEquals(expected.specifications(), actual.specifications());
        assertEquals(expected.source(), actual.source());
        assertEquals(expected.sourceRecordId(), actual.sourceRecordId());
        assertEquals(expected.sourceSnapshot(), actual.sourceSnapshot());
        assertEquals(expected.sourceAsOf(), actual.sourceAsOf());
        assertEquals(expected.sourceDigest(), actual.sourceDigest());
    }

    private ProcurementCase save(MemoryCaseStore store, ProcurementCaseState state, long version) {
        ProcurementCase value = new ProcurementCase(
                "case-1", "tenant", "conversation", "buyer", ProcurementCaseStatus.SOURCING,
                state, Instant.now(), Instant.now(), version, "seed");
        store.put(value);
        return value;
    }

    private static final class MemoryCaseStore implements ProcurementCaseStore {
        private final Map<String, ProcurementCase> values = new ConcurrentHashMap<>();

        @Override
        public Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId, String userId,
                                                                             String conversationId) {
            return Optional.ofNullable(values.get(key(tenantId, userId, conversationId)));
        }

        @Override
        public boolean createIfAbsent(ProcurementCase value) {
            return values.putIfAbsent(key(value.tenantId(), value.userId(), value.conversationId()), value) == null;
        }

        @Override
        public boolean saveIfVersion(ProcurementCase value, long expectedVersion) {
            synchronized (values) {
                String key = key(value.tenantId(), value.userId(), value.conversationId());
                ProcurementCase current = values.get(key);
                if (current == null || current.version() != expectedVersion) return false;
                values.put(key, value);
                return true;
            }
        }

        private void put(ProcurementCase value) {
            values.put(key(value.tenantId(), value.userId(), value.conversationId()), value);
        }

        private String key(String tenantId, String userId, String conversationId) {
            return tenantId + "|" + userId + "|" + conversationId;
        }
    }
}
