package com.agent.platform.procurement;

import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.application.ProcurementRecommendationFinalizer;
import com.agent.platform.procurement.application.ProcurementCaseVersionConflictException;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementCaseStatus;
import com.agent.platform.procurement.model.ProcurementRecommendationDraft;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import com.agent.platform.procurement.provider.ProcurementDataProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcurementRecommendationFinalizeTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementDataProperties properties = new ProcurementDataProperties();
    private final ProcurementDataProvider provider = new AwsSyntheticProcurementProvider(mapper, properties);

    @Test
    void finalizerAcceptsAgentChoiceAmongMultipleEligibleSuppliers() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, multiEligibleState(), 4);
        var supplierBEvidence = provider.getSupplierEvidence("supplier-b", current.state());
        var supplierDEvidence = provider.getSupplierEvidence("supplier-d", current.state());
        ProcurementRecommendationDraft draft = draft(current.version(), "supplier-d",
                supplierBEvidence.get(0).evidenceId(), supplierDEvidence.get(0).evidenceId());

        ProcurementRecommendationFinalizer.Finalization result = new ProcurementRecommendationFinalizer(store, provider)
                .finalize("tenant", "buyer", "conversation", draft);

        assertEquals("Supplier D", result.recommendation().recommendedSupplier().supplierName());
        assertEquals(1, result.recommendation().alternatives().size());
        assertEquals("supplier-b", result.recommendation().alternatives().get(0).supplierId());
        assertEquals(draft.evidenceRefs(), result.recommendation().evidenceRefs());
        assertEquals(2, result.evaluation().candidates().stream()
                .filter(ProcurementDecisionEngine.CandidateResult::eligible).count());
        assertEquals(2, result.evidence().stream().map(evidence -> evidence.supplierId()).distinct().count());
    }

    @Test
    void finalizerRejectsIneligibleSupplierAndEvidenceFromAnotherSupplier() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, stateWithExcludedSupplier(), 1);
        String excludedEvidence = provider.getSupplierEvidence("supplier-a", current.state()).get(0).evidenceId();

        assertThrows(IllegalArgumentException.class, () -> new ProcurementRecommendationFinalizer(store, provider)
                .finalize("tenant", "buyer", "conversation", draft(current.version(), "supplier-a", excludedEvidence)));
    }

    @Test
    void finalizerFailsClosedForUnknownEvidenceAndStaleCaseVersion() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, multiEligibleState(), 3);
        ProcurementRecommendationFinalizer finalizer = new ProcurementRecommendationFinalizer(store, provider);

        assertThrows(IllegalArgumentException.class, () -> finalizer.finalize(
                "tenant", "buyer", "conversation", draft(current.version(), "supplier-b", "evidence-not-from-provider")));

        var evidence = provider.getSupplierEvidence("supplier-b", current.state());
        assertThrows(ProcurementCaseVersionConflictException.class, () -> finalizer.finalize(
                "tenant", "buyer", "conversation", draft(current.version() - 1, "supplier-b", evidence.get(0).evidenceId())));
    }

    @Test
    void finalizerRejectsCaseWithNoEligibleSupplier() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCase current = save(store, new ProcurementCaseState(
                "计算工作站", "CUDA 开发工作站", 50, new BigDecimal("400000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "64"), Map.of(), Set.of(), java.util.List.of(), "SOURCING"), 0);
        String evidence = provider.getSupplierEvidence("supplier-b", current.state()).get(0).evidenceId();

        assertThrows(IllegalArgumentException.class, () -> new ProcurementRecommendationFinalizer(store, provider)
                .finalize("tenant", "buyer", "conversation", draft(current.version(), "supplier-b", evidence)));
    }

    private ProcurementCaseState multiEligibleState() {
        properties.setScenarioFile("complex_workstation_multi_eligible_01.json");
        return new ProcurementCaseState(
                "计算工作站", "CUDA 开发工作站", 50, new BigDecimal("600000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "24"), Map.of("pricePriority", "MEDIUM"), Set.of(),
                java.util.List.of(), "SOURCING");
    }

    private ProcurementCaseState stateWithExcludedSupplier() {
        return new ProcurementCaseState(
                "计算工作站", "CUDA 开发工作站", 50, new BigDecimal("600000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "24"), Map.of(), Set.of("Supplier A"),
                java.util.List.of(), "SOURCING");
    }

    private ProcurementCase save(MemoryCaseStore store, ProcurementCaseState state, long version) {
        ProcurementCase value = new ProcurementCase(
                "case-1", "tenant", "conversation", "buyer", ProcurementCaseStatus.SOURCING,
                state, Instant.now(), Instant.now(), version, "seed");
        store.save(value);
        return value;
    }

    private ProcurementRecommendationDraft draft(long version, String supplierId, String... evidenceRefs) {
        return new ProcurementRecommendationDraft(version, supplierId, java.util.List.of(evidenceRefs),
                java.util.List.of("基于当前报价和交期做出选择"),
                java.util.List.of("价格与交期存在权衡"),
                java.util.List.of("报价和交期需要在下单前重新确认"),
                java.util.List.of("当前仅使用 synthetic fixture 证据"), 0.82);
    }

    private static final class MemoryCaseStore implements ProcurementCaseStore {
        private final Map<String, ProcurementCase> values = new ConcurrentHashMap<>();

        @Override
        public Optional<ProcurementCase> findByTenantAndConversationId(String tenantId, String conversationId) {
            return values.values().stream().filter(value -> value.tenantId().equals(tenantId)
                    && value.conversationId().equals(conversationId)).findFirst();
        }

        @Override
        public Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId, String userId,
                                                                             String conversationId) {
            return Optional.ofNullable(values.get(key(tenantId, userId, conversationId)));
        }

        @Override
        public ProcurementCase save(ProcurementCase value) {
            values.put(key(value.tenantId(), value.userId(), value.conversationId()), value);
            return value;
        }

        private String key(String tenantId, String userId, String conversationId) {
            return tenantId + "|" + userId + "|" + conversationId;
        }
    }
}
