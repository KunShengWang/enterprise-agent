package com.agent.platform.procurement;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.procurement.application.ProcurementCasePatchMerger;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.application.ProcurementRecommendationVerifier;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCasePatch;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementTradeoffDimension;
import com.agent.platform.procurement.model.SourcingRecommendation;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierOffer;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.procurement.tool.ProcurementToolHandler;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolExecutionContext;
import com.agent.platform.workbench.target.ExecutionTargetId;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcurementSourcingMvpTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementDataProperties dataProperties = new ProcurementDataProperties();
    private final AwsSyntheticProcurementProvider provider = new AwsSyntheticProcurementProvider(mapper, dataProperties);

    @Test
    void targetProfileAndToolMetadataMatchTheInternalStateBoundary() {
        IncidentCommandProperties incident = new IncidentCommandProperties();
        var target = new ExecutionTargetRegistry(incident).findEnabled(
                new com.agent.platform.workbench.security.AuthenticatedPrincipal("tenant", "buyer", Set.of("USER")),
                ExecutionTargetId.PROCUREMENT_SOURCING.name()).orElseThrow();
        assertEquals("procurement-sourcing-readonly-v1", target.executionProfileId());
        assertEquals(com.agent.platform.workbench.target.TargetRiskLevel.LOW, target.riskLevel());
        var profile = new ProcurementSourcingExecutionProfileFactory().createProfile();
        assertEquals(Set.of(ProcurementToolCatalog.CASE_PATCH, ProcurementToolCatalog.SUPPLIER_SEARCH,
                ProcurementToolCatalog.COMMERCIAL_ANALYSIS, ProcurementToolCatalog.DELIVERY_ANALYSIS,
                ProcurementToolCatalog.RECOMMENDATION_FINALIZE),
                profile.allowedCapabilities());
        assertTrue(profile.longTermMemoryEnabled());
        assertTrue(profile.systemPrompt().contains("软偏好"));
        assertTrue(profile.systemPrompt().contains("当前用户"));
        assertTrue(profile.systemPrompt().contains("当前供应商事实"));
        assertTrue(profile.systemPrompt().contains("ToolResult"));

        var definitions = new ProcurementToolCatalog().definitions();
        assertEquals(Map.of("readOnly", false, "sideEffect", true), metadata(definitions, ProcurementToolCatalog.CASE_PATCH));
        assertEquals(Map.of("readOnly", true, "sideEffect", false), metadata(definitions, ProcurementToolCatalog.SUPPLIER_SEARCH));
        assertEquals(Map.of("readOnly", true, "sideEffect", false), metadata(definitions, ProcurementToolCatalog.RECOMMENDATION_FINALIZE));
        assertEquals(5, definitions.size());
        assertEquals("procurement-specialist-v1", definition(definitions, ProcurementToolCatalog.COMMERCIAL_ANALYSIS)
                .metadata().get("contractVersion"));
        assertEquals("procurement-specialist-v1", definition(definitions, ProcurementToolCatalog.DELIVERY_ANALYSIS)
                .metadata().get("contractVersion"));
        assertEquals("LOW", definition(definitions, ProcurementToolCatalog.COMMERCIAL_ANALYSIS).riskLevel().name());
        assertEquals("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}",
                definition(definitions, ProcurementToolCatalog.COMMERCIAL_ANALYSIS).inputSchema());
        assertEquals(Map.of("readOnly", true, "sideEffect", false, "parallelSafe", true,
                        "executionKind", "SUB_AGENT", "singleUse", true),
                specialistMetadata(definitions, ProcurementToolCatalog.COMMERCIAL_ANALYSIS));
        assertEquals(Map.of("readOnly", true, "sideEffect", false, "parallelSafe", true,
                        "executionKind", "SUB_AGENT", "singleUse", true),
                specialistMetadata(definitions, ProcurementToolCatalog.DELIVERY_ANALYSIS));
        assertEquals(Set.of("procurement-sourcing-v3"), definitions.stream()
                .filter(definition -> !definition.name().equals(ProcurementToolCatalog.COMMERCIAL_ANALYSIS)
                        && !definition.name().equals(ProcurementToolCatalog.DELIVERY_ANALYSIS))
                .map(definition -> String.valueOf(definition.metadata().get("contractVersion")))
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void authoritativeCasePatchCreatesTheStateUsedBySearch() throws Exception {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCaseService service = service(store);
        service.applyPatch("tenant", "conversation", "buyer", completePatch(), "patch-1");
        ProcurementToolHandler handler = handler(store);

        var result = handler.execute(new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "search-1", Map.of()), context());

        assertTrue(result.success(), result.errorMessage());
        JsonNode json = mapper.readTree(result.content());
        assertEquals(1, json.path("caseVersion").asInt());
        assertEquals(Set.of("supplier-b", "supplier-d"), ids(json.path("eligibleSuppliers")));
        assertTrue(json.path("evidence").size() >= 8);
        assertEquals(true, result.metadata().get("readOnly"));
        assertEquals(false, result.metadata().get("sideEffect"));
    }

    @Test
    void modelCannotProvideAuthoritativeSearchFields() {
        MemoryCaseStore store = new MemoryCaseStore();
        service(store).applyPatch("tenant", "conversation", "buyer", completePatch(), "patch-1");
        var result = handler(store).execute(new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "search-1",
                Map.of("quantity", 1)), context());

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("unknown argument"));
    }

    @Test
    void awsProviderAndDecisionEngineExposeBAndDEligibilityWithoutRecommendationOrEvidenceGeneration() {
        ProcurementCaseState state = completeState();
        var candidates = provider.searchSuppliers(state);
        var offers = provider.getSupplierOffers(state, candidates);
        var evaluation = new ProcurementDecisionEngine().evaluate(state, candidates, offers);

        assertEquals(Set.of("supplier-a", "supplier-b", "supplier-c", "supplier-d"),
                candidates.stream().map(SupplierCandidate::supplierId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("supplier-b", "supplier-d"), evaluation.candidates().stream()
                .filter(ProcurementDecisionEngine.CandidateResult::eligible)
                .map(candidate -> candidate.candidate().supplierId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(new BigDecimal("550000"), offers.stream().filter(o -> o.supplierId().equals("supplier-b"))
                .findFirst().orElseThrow().totalPrice());
        assertEquals(new BigDecimal("580000"), offers.stream().filter(o -> o.supplierId().equals("supplier-d"))
                .findFirst().orElseThrow().totalPrice());
        assertTrue(evaluation.candidates().stream().anyMatch(c -> c.candidate().supplierId().equals("supplier-a")
                && !c.eligible() && c.failures().contains("EXCLUDED_SUPPLIER")));
        assertTrue(evaluation.candidates().stream().anyMatch(c -> c.candidate().supplierId().equals("supplier-c")
                && !c.eligible() && c.failures().stream().anyMatch(reason -> reason.contains("HARD_CONSTRAINT_FAILED"))));
    }

    @Test
    void modelCannotInjectFreeFactsOrUnsupportedTradeoffDimensionsIntoRecommendationFinalize() {
        MemoryCaseStore store = new MemoryCaseStore();
        service(store).applyPatch("tenant", "conversation", "buyer", completePatch(), "patch-1");
        ProcurementCaseState state = store.findByTenantUserAndConversationId("tenant", "buyer", "conversation")
                .orElseThrow().state();
        String supplierBOffer = provider.getSupplierEvidence("supplier-b", state).get(0).evidenceId();
        String supplierDOffer = provider.getSupplierEvidence("supplier-d", state).get(0).evidenceId();
        Map<String, Object> valid = Map.of(
                "evaluatedCaseVersion", 1,
                "selectedSupplierId", "supplier-d",
                "evidenceRefs", List.of(supplierBOffer, supplierDOffer),
                "tradeoffDimensions", List.of("DELIVERY", "PRICE"),
                "confidence", 0.86);
        Map<String, Object> freeFact = new java.util.LinkedHashMap<>(valid);
        freeFact.put("reasons", List.of("Supplier D 通过 ISO9001"));
        var freeFactResult = handler(store).execute(new ToolCallRequest(ProcurementToolCatalog.RECOMMENDATION_FINALIZE,
                "finalize-free-fact", freeFact), context());
        assertFalse(freeFactResult.success());
        assertTrue(freeFactResult.errorMessage().contains("unknown argument"));

        Map<String, Object> invalidDimension = new java.util.LinkedHashMap<>(valid);
        invalidDimension.put("tradeoffDimensions", List.of("RISK"));
        var invalidDimensionResult = handler(store).execute(new ToolCallRequest(ProcurementToolCatalog.RECOMMENDATION_FINALIZE,
                "finalize-invalid-dimension", invalidDimension), context());
        assertFalse(invalidDimensionResult.success());
        assertTrue(invalidDimensionResult.errorMessage().contains("invalid tradeoffDimension"));
    }

    @Test
    void noEligibleSupplierIsReturnedWhenHardConstraintsCannotBeMet() {
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("400000"), "CNY", 21, Map.of("gpuMemoryMinGb", "64"),
                Map.of(), Set.of(), List.of(), "SOURCING");
        var evaluation = new ProcurementDecisionEngine().evaluate(state, provider.searchSuppliers(state),
                provider.getSupplierOffers(state, provider.searchSuppliers(state)));
        assertTrue(evaluation.candidates().stream().noneMatch(ProcurementDecisionEngine.CandidateResult::eligible));
    }

    @Test
    void rawAwsSupplierBaseDoesNotBecomeSupplierOffer() {
        ProcurementCaseState state = new ProcurementCaseState("办公用品", "办公用品", 2,
                new BigDecimal("1000"), "CNY", null, Map.of(), Map.of(), Set.of(), List.of(), "SOURCING");
        assertTrue(provider.getSupplierOffers(state, provider.searchSuppliers(state)).isEmpty());
    }

    @Test
    void unknownHardConstraintFailsClosedInDecisionEngine() {
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("iso9001", "true"),
                Map.of(), Set.of(), List.of(), "SOURCING");
        var result = new ProcurementDecisionEngine().evaluate(state, provider.searchSuppliers(state),
                provider.getSupplierOffers(state, provider.searchSuppliers(state)));
        assertTrue(result.candidates().stream().noneMatch(ProcurementDecisionEngine.CandidateResult::eligible));
        assertTrue(result.candidates().stream().allMatch(c -> c.failures().contains("UNSUPPORTED_HARD_CONSTRAINT:iso9001")));
    }

    @Test
    void recommendationVerifierRejectsUnknownEvidenceReference() {
        SupplierOffer offer = provider.getSupplierOffers(completeState(), provider.searchSuppliers(completeState())).stream()
                .filter(value -> value.supplierId().equals("supplier-b")).findFirst().orElseThrow();
        var recommendation = new SourcingRecommendation(
                new SupplierCandidate("supplier-b", "Supplier B", "fixture"), offer, List.of(), List.of(),
                List.of(), List.of(), List.of("missing-evidence"), List.of(ProcurementTradeoffDimension.PRICE), 1.0);
        assertThrows(IllegalArgumentException.class,
                () -> ProcurementRecommendationVerifier.verify(recommendation, List.of(), Set.of("supplier-b")));
    }

    @Test
    void sourcingRecommendationRejectsMismatchedAlternativeSuppliers() {
        assertThrows(IllegalArgumentException.class, () -> recommendation(
                List.of(supplier("supplier-b")), List.of(offer("supplier-c")), 0.82));
        assertThrows(IllegalArgumentException.class, () -> recommendation(
                List.of(supplier("supplier-b")), List.of(), 0.82));
        assertThrows(IllegalArgumentException.class, () -> recommendation(
                List.of(supplier("supplier-b"), supplier("supplier-c")),
                List.of(offer("supplier-c"), offer("supplier-b")), 0.82));
    }

    @Test
    void sourcingRecommendationRejectsRecommendedSupplierAsAlternative() {
        assertThrows(IllegalArgumentException.class, () -> recommendation(
                List.of(supplier("supplier-d")), List.of(offer("supplier-d")), 0.82));
    }

    @Test
    void sourcingRecommendationRejectsNullAndDuplicateAlternativeSuppliers() {
        assertThrows(IllegalArgumentException.class, () -> recommendation(
                List.of(supplier("supplier-b"), supplier("supplier-b")),
                List.of(offer("supplier-b"), offer("supplier-b")), 0.82));
        assertThrows(IllegalArgumentException.class, () -> recommendation(
                java.util.Collections.singletonList((SupplierCandidate) null),
                java.util.Collections.singletonList(offer("supplier-b")), 0.82));
        assertThrows(IllegalArgumentException.class, () -> recommendation(
                List.of(supplier("supplier-b")),
                java.util.Collections.singletonList((SupplierOffer) null), 0.82));
    }

    @Test
    void sourcingRecommendationRejectsOutOfRangeConfidence() {
        assertThrows(IllegalArgumentException.class, () -> recommendation(List.of(), List.of(), 1.01));
        assertThrows(IllegalArgumentException.class, () -> recommendation(List.of(), List.of(), -0.01));
        assertThrows(IllegalArgumentException.class, () -> recommendation(List.of(), List.of(), Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> recommendation(List.of(), List.of(), Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> recommendation(List.of(), List.of(), Double.NEGATIVE_INFINITY));
    }

    @Test
    void sourcingRecommendationAcceptsMatchingAlternativeAndConfidence() {
        SourcingRecommendation recommendation = recommendation(
                List.of(supplier("supplier-b")), List.of(offer("supplier-b")), 0.82);

        assertEquals(List.of("supplier-b"), recommendation.eligibleAlternatives().stream()
                .map(SupplierCandidate::supplierId).toList());
        assertEquals(List.of("supplier-b"), recommendation.alternativeOffers().stream()
                .map(SupplierOffer::supplierId).toList());
        assertEquals(0.82, recommendation.confidence());
        SourcingRecommendation lowerBound = recommendation(List.of(), List.of(), 0.0);
        SourcingRecommendation upperBound = recommendation(List.of(), List.of(), 1.0);
        assertEquals(0.0, lowerBound.confidence());
        assertEquals(1.0, upperBound.confidence());
    }

    @Test
    void sameConversationIsolatedByTenantAndUser() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCaseService service = service(store);
        ProcurementCase tenantA = service.applyPatch("tenant-a", "same-conversation", "buyer-a",
                patch("计算工作站", "工作站", 10, new BigDecimal("100000")), "a");
        ProcurementCase tenantB = service.applyPatch("tenant-b", "same-conversation", "buyer-a",
                patch("计算工作站", "工作站", 20, new BigDecimal("200000")), "b");
        assertEquals(10, store.findByTenantUserAndConversationId("tenant-a", "buyer-a", "same-conversation").orElseThrow().state().quantity());
        assertEquals(20, store.findByTenantUserAndConversationId("tenant-b", "buyer-a", "same-conversation").orElseThrow().state().quantity());
        assertFalse(tenantA.caseId().equals(tenantB.caseId()));
    }

    private Map<String, Object> metadata(List<com.agent.platform.tool.ToolDefinition> definitions, String name) {
        return definition(definitions, name)
                .metadata().entrySet().stream().filter(entry -> entry.getKey().equals("readOnly") || entry.getKey().equals("sideEffect"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private com.agent.platform.tool.ToolDefinition definition(
            List<com.agent.platform.tool.ToolDefinition> definitions, String name) {
        return definitions.stream().filter(definition -> definition.name().equals(name)).findFirst().orElseThrow();
    }

    private Map<String, Object> specialistMetadata(
            List<com.agent.platform.tool.ToolDefinition> definitions, String name) {
        return definition(definitions, name).metadata().entrySet().stream()
                .filter(entry -> Set.of("readOnly", "sideEffect", "parallelSafe", "executionKind", "singleUse")
                        .contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private ProcurementToolHandler handler(MemoryCaseStore store) {
        ProcurementCasePatchMerger merger = new ProcurementCasePatchMerger();
        ProcurementDecisionEngine engine = new ProcurementDecisionEngine();
        return new ProcurementToolHandler(provider, mapper, store, new ProcurementCaseService(store, merger),
                new com.agent.platform.procurement.application.ProcurementRecommendationFinalizer(store, provider, engine),
                merger, engine);
    }

    private ProcurementCaseService service(MemoryCaseStore store) {
        return new ProcurementCaseService(store, new ProcurementCasePatchMerger());
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext("run", "conversation", "buyer", "tenant", Set.of("USER"), Map.of());
    }

    private ProcurementCaseState completeState() {
        return new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"),
                Map.of("deliveryPriority", "HIGH"), Set.of("Supplier A"), List.of(), "SOURCING");
    }

    private ProcurementCasePatch completePatch() {
        return new ProcurementCasePatch("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"), Set.of(),
                Map.of("deliveryPriority", "HIGH"), Set.of(), Set.of("Supplier A"), Set.of(), Set.of());
    }

    private ProcurementCasePatch patch(String category, String description, int quantity, BigDecimal budget) {
        return new ProcurementCasePatch(category, description, quantity, budget, "CNY", null,
                Map.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    private SourcingRecommendation recommendation(List<SupplierCandidate> alternatives,
                                                  List<SupplierOffer> alternativeOffers,
                                                  double confidence) {
        return new SourcingRecommendation(supplier("supplier-d"), offer("supplier-d"), alternatives,
                alternativeOffers, List.of(), List.of(), List.of("evidence"),
                List.of(ProcurementTradeoffDimension.DELIVERY), confidence);
    }

    private SupplierCandidate supplier(String supplierId) {
        return provider.searchSuppliers(completeState()).stream()
                .filter(value -> value.supplierId().equals(supplierId)).findFirst().orElseThrow();
    }

    private SupplierOffer offer(String supplierId) {
        return provider.getSupplierOffers(completeState(), provider.searchSuppliers(completeState())).stream()
                .filter(value -> value.supplierId().equals(supplierId)).findFirst().orElseThrow();
    }

    private Set<String> ids(JsonNode values) {
        Set<String> result = new java.util.HashSet<>();
        for (JsonNode value : values) result.add(value.path("supplierId").asText());
        return result;
    }

    private static final class MemoryCaseStore implements ProcurementCaseStore {
        private final Map<String, ProcurementCase> values = new ConcurrentHashMap<>();

        @Override
        public Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId, String userId, String conversationId) {
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

        private String key(String tenantId, String userId, String conversationId) {
            return tenantId + "|" + userId + "|" + conversationId;
        }
    }
}
