package com.agent.platform.procurement;

import com.agent.platform.procurement.application.ProcurementCaseVersionConflictException;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.application.ProcurementRecommendationFinalizer;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementCaseStatus;
import com.agent.platform.procurement.model.ProcurementRecommendationDraft;
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

        ProcurementRecommendationFinalizer.Finalization result = finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-d",
                        List.of(supplierBOffer, supplierDOffer), List.of("价格与交期存在权衡")));

        assertEquals("Supplier D", result.recommendation().recommendedSupplier().supplierName());
        assertEquals(List.of("supplier-b"), result.recommendation().alternatives().stream()
                .map(value -> value.supplierId()).toList());
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
                        List.of("evidence-not-from-provider"), List.of("价格与交期存在权衡"))));
    }

    @Test
    void finalizerFailsClosedForStaleCaseVersion() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, completeState(), 3);
        String evidence = provider.getSupplierEvidence("supplier-b", current.state()).get(0).evidenceId();

        assertThrows(ProcurementCaseVersionConflictException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version() - 1, "supplier-b",
                        List.of(evidence), List.of("价格与交期存在权衡"))));
    }

    @Test
    void finalizerRequiresSelectedOfferEvidenceNotWarrantyOnly() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, completeState(), 1);
        String warranty = provider.getSupplierEvidence("supplier-d", current.state()).get(1).evidenceId();

        assertThrows(IllegalArgumentException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-d",
                        List.of(warranty), List.of("交期更快"))));
    }

    @Test
    void multiEligibleFinalizerRequiresTradeoffAndAlternativeOfferEvidence() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, completeState(), 1);
        String supplierDOffer = provider.getSupplierEvidence("supplier-d", current.state()).get(0).evidenceId();
        String supplierBOffer = provider.getSupplierEvidence("supplier-b", current.state()).get(0).evidenceId();

        assertThrows(IllegalArgumentException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-d",
                        List.of(supplierDOffer), List.of())));
        assertThrows(IllegalArgumentException.class, () -> finalizer(store).finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-d",
                        List.of(supplierDOffer), List.of("交期更快"))));
        // The complete pair is accepted by the first test; this assertion keeps the alternative evidence explicit.
        var result = finalizer(store).finalize("tenant", "buyer", "conversation",
                draft(current.version(), "supplier-d", List.of(supplierDOffer, supplierBOffer), List.of("交期更快")));
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
                                                 List<String> tradeoffs) {
        return new ProcurementRecommendationDraft(version, supplierId, evidenceRefs,
                List.of("基于当前报价和交期做出选择"), tradeoffs,
                List.of("报价和交期需要在下单前重新确认"),
                List.of("当前仅使用 synthetic fixture 证据"), 0.82);
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
