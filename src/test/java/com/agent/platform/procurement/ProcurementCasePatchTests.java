package com.agent.platform.procurement;

import com.agent.platform.procurement.application.ProcurementCasePatchMerger;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.procurement.application.ProcurementCaseVersionConflictException;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCasePatch;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcurementCasePatchTests {

    private final ProcurementCasePatchMerger merger = new ProcurementCasePatchMerger();

    @Test
    void partialPatchPreservesExistingFactsAndUpdatesOnlyUserIntent() {
        ProcurementCaseState current = new ProcurementCaseState(
                "计算工作站", "CUDA 开发工作站", 50, new BigDecimal("600000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "24"), Map.of("deliveryPriority", "HIGH"), Set.of("Supplier A"),
                java.util.List.of(), "SOURCING");
        ProcurementCasePatch patch = new ProcurementCasePatch(
                null, null, 60, null, null, null,
                Map.of("gpuMemoryMinGb", "32"), Set.of(), Map.of(), Set.of(),
                Set.of("Supplier C"), Set.of("Supplier A"), Set.of());

        ProcurementCaseState next = merger.merge(current, patch);

        assertEquals("CUDA 开发工作站", next.productDescription());
        assertEquals(60, next.quantity());
        assertEquals(new BigDecimal("600000"), next.budget());
        assertEquals(21, next.requiredDeliveryDays());
        assertEquals("32", next.hardConstraints().get("gpuMemoryMinGb"));
        assertEquals(Map.of("deliveryPriority", "HIGH"), next.preferences());
        assertEquals(Set.of("Supplier C"), next.excludedSuppliers());
        assertTrue(next.missingFields().isEmpty());
    }

    @Test
    void patchCanExplicitlyClearScalarRequirementsAndRejectsProtectedFields() {
        ProcurementCaseState current = new ProcurementCaseState(
                "计算工作站", "CUDA 开发工作站", 50, new BigDecimal("600000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "24"), Map.of("deliveryPriority", "HIGH"), Set.of(),
                java.util.List.of(), "SOURCING");

        ProcurementCaseState cleared = merger.merge(current, new ProcurementCasePatch(
                null, null, null, null, null, null, Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(),
                Set.of("budget", "requiredDeliveryDays")));

        assertEquals(null, cleared.budget());
        assertEquals(null, cleared.requiredDeliveryDays());
        assertEquals(java.util.List.of("budget"), cleared.missingFields());
        assertEquals("REQUIREMENT_UNDERSTANDING", cleared.currentPhase());
        assertThrows(IllegalArgumentException.class, () -> merger.validate(new ProcurementCasePatch(
                null, null, null, new BigDecimal("650000"), null, null, Map.of(), Set.of(), Map.of(), Set.of(),
                Set.of(), Set.of(), Set.of("budget"))));
        assertThrows(IllegalArgumentException.class, () -> merger.validate(new ProcurementCasePatch(
                null, null, null, null, null, null, Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(),
                Set.of("caseId"))));
        assertThrows(IllegalArgumentException.class, () -> merger.validate(new ProcurementCasePatch(
                null, null, null, null, null, null, Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(),
                Set.of("currency"))));
    }

    @Test
    void patchCanRemoveCollectionRequirementsWithoutChangingOtherFacts() {
        ProcurementCaseState current = new ProcurementCaseState(
                "计算工作站", "CUDA 开发工作站", 50, new BigDecimal("600000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "24"), Map.of("deliveryPriority", "HIGH"), Set.of("Supplier A"),
                java.util.List.of(), "SOURCING");

        ProcurementCaseState next = merger.merge(current, new ProcurementCasePatch(
                null, null, null, null, null, null,
                Map.of(), Set.of("gpuMemoryMinGb"), Map.of(), Set.of("deliveryPriority"),
                Set.of(), Set.of("Supplier A"), Set.of()));

        assertEquals("计算工作站", next.productCategory());
        assertEquals("CUDA 开发工作站", next.productDescription());
        assertEquals(50, next.quantity());
        assertEquals(new BigDecimal("600000"), next.budget());
        assertEquals("CNY", next.currency());
        assertEquals(21, next.requiredDeliveryDays());
        assertTrue(next.hardConstraints().isEmpty());
        assertTrue(next.preferences().isEmpty());
        assertTrue(next.excludedSuppliers().isEmpty());
        assertTrue(next.missingFields().isEmpty());
    }

    @Test
    void patchRejectsUnsupportedConstraintAndNullCollectionEntryAsClientError() {
        assertThrows(IllegalArgumentException.class, () -> merger.validate(new ProcurementCasePatch(
                null, "CUDA 工作站", null, null, null, null,
                Map.of("iso9001", "true"), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of())));
        assertThrows(IllegalArgumentException.class, () -> merger.validate(new ProcurementCasePatch(
                null, "CUDA 工作站", null, null, null, null,
                Map.of(), Set.of(), Map.of(), Set.of(),
                new java.util.LinkedHashSet<>(java.util.Collections.singletonList(null)), Set.of(), Set.of())));
    }

    @Test
    void applyingTheSamePatchTwiceIsIdempotentByRuntimeInputId() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCaseService service = new ProcurementCaseService(store, merger);
        ProcurementCasePatch patch = new ProcurementCasePatch(
                "计算工作站", "CUDA 开发工作站", 50, new BigDecimal("600000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "24"), Set.of(), Map.of(), Set.of(), Set.of("Supplier A"), Set.of(), Set.of());

        ProcurementCase first = service.applyPatch("tenant", "conversation", "buyer", patch, "tool-call-1");
        ProcurementCase replay = service.applyPatch("tenant", "conversation", "buyer", patch, "tool-call-1");

        assertEquals(first.caseId(), replay.caseId());
        assertEquals(1, replay.version());
        assertEquals(first.state(), replay.state());
    }

    @Test
    void replayingAnOlderPatchCannotRollBackAMoreRecentUpdate() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCaseService service = new ProcurementCaseService(store, merger);
        ProcurementCasePatch initial = new ProcurementCasePatch(
                "计算工作站", "CUDA 开发工作站", 50, new BigDecimal("600000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "24"), Set.of(), Map.of(), Set.of(), Set.of("Supplier A"), Set.of(), Set.of());
        ProcurementCasePatch update = new ProcurementCasePatch(
                null, null, 80, null, null, null,
                Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of());

        service.applyPatch("tenant", "conversation", "buyer", initial, "input-a");
        ProcurementCase updated = service.applyPatch("tenant", "conversation", "buyer", update, "input-b");
        ProcurementCase replay = service.applyPatch("tenant", "conversation", "buyer", initial, "input-a");

        assertEquals(80, updated.state().quantity());
        assertEquals(80, replay.state().quantity());
        assertEquals(2, replay.version());
        assertEquals("input-b", replay.lastAppliedInputId());
        assertEquals(Set.of("input-a", "input-b"), replay.appliedInputIds());
    }

    @Test
    void patchRequiresRuntimeInputIdAndRejectsBlankId() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCaseService service = new ProcurementCaseService(store, merger);
        ProcurementCasePatch patch = new ProcurementCasePatch(
                null, "CUDA 开发工作站", null, null, null, null,
                Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of());

        assertThrows(IllegalArgumentException.class, () -> service.applyPatch(
                "tenant", "conversation", "buyer", patch, " "));
        assertThrows(IllegalArgumentException.class, () -> service.applyPatch(
                "tenant", "conversation", "buyer", patch, null));
    }

    @Test
    void concurrentPatchesUseVersionCasAndDoNotSilentlyOverwrite() throws Exception {
        BarrierCaseStore store = new BarrierCaseStore();
        ProcurementCaseService service = new ProcurementCaseService(store, merger);
        service.ensureCase("tenant", "conversation", "buyer");
        ProcurementCasePatch quantity = new ProcurementCasePatch(
                null, null, 50, null, null, null, Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of());
        ProcurementCasePatch budget = new ProcurementCasePatch(
                null, null, null, new BigDecimal("600000"), null, null, Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProcurementCase> first = executor.submit(() -> service.applyPatch(
                    "tenant", "conversation", "buyer", quantity, "quantity-call"));
            Future<ProcurementCase> second = executor.submit(() -> service.applyPatch(
                    "tenant", "conversation", "buyer", budget, "budget-call"));
            int success = 0;
            int conflicts = 0;
            for (Future<ProcurementCase> future : java.util.List.of(first, second)) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    success++;
                }
                catch (ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof ProcurementCaseVersionConflictException);
                    conflicts++;
                }
            }
            assertEquals(1, success);
            assertEquals(1, conflicts);
            assertEquals(1, store.findByTenantUserAndConversationId("tenant", "buyer", "conversation")
                    .orElseThrow().version());
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static class MemoryCaseStore implements ProcurementCaseStore {
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

        protected ProcurementCase replace(ProcurementCase value) {
            values.put(key(value.tenantId(), value.userId(), value.conversationId()), value);
            return value;
        }

        protected String key(String tenantId, String userId, String conversationId) {
            return tenantId + "|" + userId + "|" + conversationId;
        }
    }

    private static final class BarrierCaseStore extends MemoryCaseStore {
        private final CyclicBarrier firstTwoSaves = new CyclicBarrier(2);
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public boolean saveIfVersion(ProcurementCase value, long expectedVersion) {
            if (attempts.incrementAndGet() <= 2) {
                try {
                    firstTwoSaves.await(5, TimeUnit.SECONDS);
                }
                catch (Exception exception) {
                    throw new IllegalStateException("CAS test barrier failed", exception);
                }
            }
            synchronized (this) {
                Optional<ProcurementCase> current = findByTenantUserAndConversationId(
                        value.tenantId(), value.userId(), value.conversationId());
                if (current.isEmpty() || current.get().version() != expectedVersion) return false;
                replace(value);
                return true;
            }
        }
    }
}
