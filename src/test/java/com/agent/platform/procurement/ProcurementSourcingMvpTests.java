package com.agent.platform.procurement;

import com.agent.platform.procurement.application.ProcurementCaseParser;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.procurement.tool.ProcurementToolHandler;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolExecutionContext;
import com.agent.platform.workbench.target.ExecutionTargetId;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcurementSourcingMvpTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementDataProperties dataProperties = new ProcurementDataProperties();
    private final AwsSyntheticProcurementProvider provider = new AwsSyntheticProcurementProvider(mapper, dataProperties);

    @Test
    void targetAndProfileAreRegisteredAsLowRiskReadOnly() {
        IncidentCommandProperties incident = new IncidentCommandProperties();
        var target = new ExecutionTargetRegistry(incident).findEnabled(
                new com.agent.platform.workbench.security.AuthenticatedPrincipal("tenant", "buyer", java.util.Set.of("USER")),
                ExecutionTargetId.PROCUREMENT_SOURCING.name()).orElseThrow();
        assertEquals("procurement-sourcing-readonly-v1", target.executionProfileId());
        assertEquals(com.agent.platform.workbench.target.TargetRiskLevel.LOW, target.riskLevel());
        var profile = new ProcurementSourcingExecutionProfileFactory().createProfile();
        assertEquals(java.util.Set.of(ProcurementToolCatalog.CASE_PATCH, ProcurementToolCatalog.SUPPLIER_SEARCH,
                ProcurementToolCatalog.SUPPLIER_EVIDENCE, ProcurementToolCatalog.RECOMMENDATION_FINALIZE),
                profile.allowedCapabilities());
        assertTrue(new ProcurementToolCatalog().definitions().stream()
                .filter(definition -> definition.name().equals(ProcurementToolCatalog.CASE_PATCH))
                .findFirst().orElseThrow().inputSchema().contains("fieldsToClear"));
        assertFalse(profile.longTermMemoryEnabled());
    }

    @Test
    void sameConversationIncrementallyUpdatesOneAuthoritativeCaseState() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCaseService service = new ProcurementCaseService(store, new ProcurementCaseParser());
        ProcurementCase first = service.upsert("conversation-1", "buyer-1", "研发部门需要采购 CUDA 开发工作站");
        ProcurementCase second = service.upsert("conversation-1", "buyer-1", "50台，预算60万，最好三周内到，显存至少24GB，不要 Supplier A");
        assertEquals(first.caseId(), second.caseId());
        assertEquals(50, second.state().quantity());
        assertEquals(new BigDecimal("600000"), second.state().budget());
        assertEquals(21, second.state().requiredDeliveryDays());
        assertEquals("24", second.state().hardConstraints().get("gpuMemoryMinGb"));
        assertTrue(second.state().excludedSuppliers().contains("Supplier A"));
        assertTrue(second.state().missingFields().isEmpty());
        assertEquals(second.caseId(), store.findByTenantAndConversationId("default-tenant", "conversation-1").orElseThrow().caseId());
    }

    @Test
    void replayingOlderAppliedInputDoesNotRollbackNewerCaseState() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCaseService service = new ProcurementCaseService(store, new ProcurementCaseParser());
        service.upsert("tenant", "conversation-1", "buyer-1", "采购50台工作站，预算60万", "input-a");
        ProcurementCase newer = service.upsert("tenant", "conversation-1", "buyer-1", "改成80台", "input-b");
        ProcurementCase replay = service.upsert("tenant", "conversation-1", "buyer-1", "采购50台工作站，预算60万", "input-a");
        assertEquals(80, newer.state().quantity());
        assertEquals(80, replay.state().quantity());
        assertEquals(newer.version(), replay.version());
        assertTrue(replay.appliedInputIds().containsAll(java.util.Set.of("input-a", "input-b")));
    }

    @Test
    void awsProviderUsesCanonicalModelsAndJavaCalculatesEligibility() throws Exception {
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"),
                Map.of("deliveryPriority", "HIGH"), java.util.Set.of("Supplier A"), java.util.List.of(), "SOURCING");
        var candidates = provider.searchSuppliers(state);
        var offers = provider.getSupplierOffers(state, candidates);
        var evaluation = new ProcurementDecisionEngine().evaluate(state, candidates, offers);
        assertTrue(evaluation.candidates().stream().anyMatch(candidate -> candidate.eligible()
                && candidate.candidate().supplierName().equals("Supplier B")));
        assertEquals(new BigDecimal("550000"), offers.stream().filter(o -> o.supplierId().equals("supplier-b")).findFirst().orElseThrow().totalPrice());
        assertTrue(evaluation.candidates().stream().anyMatch(c -> c.candidate().supplierName().equals("Supplier A")
                && !c.eligible() && c.failures().contains("EXCLUDED_SUPPLIER")));
        assertTrue(evaluation.candidates().stream().anyMatch(c -> c.candidate().supplierName().equals("Supplier C")
                && !c.eligible() && c.failures().stream().anyMatch(reason -> reason.contains("HARD_CONSTRAINT_FAILED"))));
        var evidenceIds = evaluation.evidence().stream().map(SupplierEvidence::evidenceId).collect(java.util.stream.Collectors.toSet());
        assertTrue(evaluation.candidates().stream().flatMap(candidate -> candidate.evidenceRefs().stream())
                .allMatch(evidenceIds::contains));

        ProcurementToolHandler handler = new ProcurementToolHandler(provider, mapper);
        var result = handler.execute(new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "call-1", Map.of(
                "productDescription", "CUDA 开发工作站", "quantity", 50, "budget", 600000,
                "requiredDeliveryDays", 21, "hardConstraints", Map.of("gpuMemoryMinGb", "24"),
                "excludedSuppliers", java.util.List.of("Supplier A"))), ToolExecutionContext.empty());
        assertTrue(result.success(), result.errorMessage());
        JsonNode json = mapper.readTree(result.content());
        assertFalse(json.path("recommendationAvailable").asBoolean());
        assertEquals("Supplier B", json.path("eligibleSuppliers").get(0).path("supplierName").asText());
        assertTrue(result.content().contains("totalPrice"));
        assertFalse(result.content().contains("supplier_name"));
    }

    @Test
    void configuredScenarioCanExposeMultipleEligibleSuppliersWithoutJavaRecommendation() throws Exception {
        dataProperties.setScenarioFile("complex_workstation_multi_eligible_01.json");
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"),
                Map.of("deliveryPriority", "HIGH"), Set.of(), java.util.List.of(), "SOURCING");
        var candidates = provider.searchSuppliers(state);
        var evaluation = new ProcurementDecisionEngine().evaluate(state, candidates, provider.getSupplierOffers(state, candidates));

        assertEquals(Set.of("supplier-b", "supplier-d"), evaluation.candidates().stream()
                .filter(ProcurementDecisionEngine.CandidateResult::eligible)
                .map(candidate -> candidate.candidate().supplierId()).collect(java.util.stream.Collectors.toSet()));
        ProcurementToolHandler handler = new ProcurementToolHandler(provider, mapper);
        var result = handler.execute(new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "multi-eligible", Map.of(
                "productDescription", "CUDA 开发工作站", "quantity", 50, "budget", 600000)), ToolExecutionContext.empty());
        assertTrue(result.success(), result.errorMessage());
        assertFalse(mapper.readTree(result.content()).path("recommendationAvailable").asBoolean());
    }

    @Test
    void noEligibleSupplierIsReturnedWhenHardConstraintsCannotBeMet() {
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("400000"), "CNY", 21, Map.of("gpuMemoryMinGb", "64"),
                Map.of(), java.util.Set.of(), java.util.List.of(), "SOURCING");
        var candidates = provider.searchSuppliers(state);
        var evaluation = new ProcurementDecisionEngine().evaluate(state, candidates,
                provider.getSupplierOffers(state, candidates));
        assertTrue(evaluation.candidates().stream().noneMatch(ProcurementDecisionEngine.CandidateResult::eligible));
    }

    @Test
    void toolRejectsUnknownArgumentsAtBusinessBoundary() {
        ProcurementToolHandler handler = new ProcurementToolHandler(provider, mapper);
        var result = handler.execute(new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "call-2",
                Map.of("productDescription", "CUDA 工作站", "unexpected", true)), ToolExecutionContext.empty());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("unknown argument"));
    }

    @Test
    void trustedCaseStateCannotBeOverwrittenByToolQuantityOrBudget() throws Exception {
        ProcurementCaseState trusted = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"),
                Map.of(), java.util.Set.of("Supplier A"), java.util.List.of(), "SOURCING");
        ProcurementToolHandler handler = new ProcurementToolHandler(provider, mapper);
        var result = handler.execute(new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "call-trusted", Map.of(
                "productDescription", "别的产品", "quantity", 1, "budget", 1)),
                new ToolExecutionContext("run", "session", "buyer", "tenant", java.util.Set.of("USER"),
                        Map.of("procurementCaseState", trusted)));
        assertTrue(result.success(), result.errorMessage());
        JsonNode json = mapper.readTree(result.content());
        JsonNode trustedOffer = null;
        for (JsonNode offer : json.path("offers")) {
            if ("supplier-b".equals(offer.path("supplierId").asText())) trustedOffer = offer;
        }
        assertEquals(50, trustedOffer.path("quantity").asInt());
    }

    @Test
    void rawAwsCatalogDoesNotBecomeSupplierOffer() {
        ProcurementCaseState state = new ProcurementCaseState("办公用品", "办公用品", 2,
                new BigDecimal("1000"), "CNY", null, Map.of(), Map.of(), java.util.Set.of(),
                java.util.List.of(), "SOURCING");
        assertTrue(provider.getSupplierOffers(state, provider.searchSuppliers(state)).isEmpty());
    }

    @Test
    void incompleteTrustedCaseCannotBeCompletedByModelToolArguments() {
        ProcurementToolHandler handler = new ProcurementToolHandler(provider, mapper);
        var result = handler.execute(new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "call-incomplete", Map.of(
                "productDescription", "CUDA 工作站", "quantity", 50, "budget", 600000)),
                new ToolExecutionContext("run", "session", "buyer", "tenant", java.util.Set.of("USER"),
                        Map.of("procurementCaseState", ProcurementCaseState.empty())));
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("incomplete"));
    }

    @Test
    void trustedEmptyHardConstraintsCannotBeInjectedByToolArguments() throws Exception {
        ProcurementCaseState trusted = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of(), Map.of(), java.util.Set.of(),
                java.util.List.of(), "SOURCING");
        ProcurementToolHandler handler = new ProcurementToolHandler(provider, mapper);
        var result = handler.execute(new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "call-empty-hard", Map.of(
                "quantity", 50, "budget", 600000, "hardConstraints", Map.of("gpuMemoryMinGb", "64"))),
                new ToolExecutionContext("run", "session", "buyer", "tenant", java.util.Set.of("USER"),
                        Map.of("procurementCaseState", trusted)));
        assertTrue(result.success(), result.errorMessage());
        JsonNode json = mapper.readTree(result.content());
        assertFalse(json.path("recommendationAvailable").asBoolean());
        assertTrue(json.path("eligibleSuppliers").size() > 0);
        for (JsonNode evaluation : json.path("evaluations")) {
            assertFalse(evaluation.path("failures").toString().contains("gpuMemoryMinGb"));
        }
    }

    @Test
    void trustedEmptyExcludedSuppliersCannotBeInjectedByToolArguments() throws Exception {
        ProcurementCaseState trusted = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"), Map.of(),
                java.util.Set.of(), java.util.List.of(), "SOURCING");
        ProcurementToolHandler handler = new ProcurementToolHandler(provider, mapper);
        var result = handler.execute(new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "call-empty-excluded", Map.of(
                "quantity", 50, "budget", 600000, "excludedSuppliers", java.util.List.of("Supplier B"))),
                new ToolExecutionContext("run", "session", "buyer", "tenant", java.util.Set.of("USER"),
                        Map.of("procurementCaseState", trusted)));
        assertTrue(result.success(), result.errorMessage());
        assertEquals("Supplier B", mapper.readTree(result.content()).path("eligibleSuppliers").get(0)
                .path("supplierName").asText());
    }

    @Test
    void unknownHardConstraintFailsClosedInDecisionEngine() {
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("iso9001", "true"), Map.of(),
                java.util.Set.of(), java.util.List.of(), "SOURCING");
        var result = new ProcurementDecisionEngine().evaluate(state, provider.searchSuppliers(state),
                provider.getSupplierOffers(state, provider.searchSuppliers(state)));
        assertTrue(result.candidates().stream().noneMatch(ProcurementDecisionEngine.CandidateResult::eligible));
        assertTrue(result.candidates().stream().allMatch(c -> c.failures().stream()
                .anyMatch(reason -> reason.contains("UNSUPPORTED_HARD_CONSTRAINT:iso9001"))));
    }

    @Test
    void recommendationVerifierRejectsUnknownEvidenceReference() {
        var recommendation = new com.agent.platform.procurement.model.SourcingRecommendation(
                new com.agent.platform.procurement.model.SupplierCandidate("supplier-b", "Supplier B", "fixture"),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of("missing-evidence"), java.util.List.of(), 1.0);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> com.agent.platform.procurement.application.ProcurementRecommendationVerifier.verify(recommendation, java.util.List.of()));
    }

    @Test
    void supplierEvidenceIdIsStableAcrossProviderCalls() {
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("600000"), "CNY", 21, Map.of("gpuMemoryMinGb", "24"), Map.of(),
                java.util.Set.of("Supplier A"), java.util.List.of(), "SOURCING");
        String first = provider.getSupplierEvidence("supplier-b", state).get(0).evidenceId();
        String second = provider.getSupplierEvidence("supplier-b", state).get(0).evidenceId();
        assertEquals(first, second);
    }

    @Test
    void budgetAndCurrencyRulesAreDeterministicJavaDecisions() {
        ProcurementCaseState state = new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50,
                new BigDecimal("500000"), "USD", 21, Map.of(), Map.of(), java.util.Set.of(),
                java.util.List.of(), "SOURCING");
        var evaluation = new ProcurementDecisionEngine().evaluate(state, provider.searchSuppliers(state),
                provider.getSupplierOffers(state, provider.searchSuppliers(state)));
        assertTrue(evaluation.candidates().stream().noneMatch(ProcurementDecisionEngine.CandidateResult::eligible));
        assertTrue(evaluation.candidates().stream().allMatch(candidate -> candidate.failures().stream()
                .anyMatch(reason -> reason.equals("CURRENCY_MISMATCH"))));
    }

    @Test
    void parserLeavesRequiredFieldsMissingUntilUserClarifies() {
        ProcurementCaseState state = new ProcurementCaseParser().merge(ProcurementCaseState.empty(), "帮研发部门采购 CUDA 工作站");
        assertTrue(state.missingFields().containsAll(java.util.List.of("quantity", "budget")));
        assertEquals("REQUIREMENT_UNDERSTANDING", state.currentPhase());
    }

    @Test
    void sameConversationIsolatedByTenantAndUser() {
        MemoryCaseStore store = new MemoryCaseStore();
        ProcurementCaseService service = new ProcurementCaseService(store, new ProcurementCaseParser());
        ProcurementCase tenantA = service.upsert("tenant-a", "same-conversation", "buyer-a", "采购10台工作站，预算10万", "a");
        ProcurementCase tenantB = service.upsert("tenant-b", "same-conversation", "buyer-a", "采购20台工作站，预算20万", "b");
        assertEquals(10, store.findByTenantUserAndConversationId("tenant-a", "buyer-a", "same-conversation").orElseThrow().state().quantity());
        assertEquals(20, store.findByTenantUserAndConversationId("tenant-b", "buyer-a", "same-conversation").orElseThrow().state().quantity());
        assertFalse(tenantA.caseId().equals(tenantB.caseId()));
    }

    private static final class MemoryCaseStore implements ProcurementCaseStore {
        private final Map<String, ProcurementCase> values = new ConcurrentHashMap<>();
        @Override public Optional<ProcurementCase> findByTenantAndConversationId(String tenantId, String conversationId) {
            return values.values().stream().filter(value -> value.tenantId().equals(tenantId)
                    && value.conversationId().equals(conversationId)).findFirst();
        }
        @Override public Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId, String userId, String conversationId) {
            return Optional.ofNullable(values.get(tenantId + "|" + userId + "|" + conversationId));
        }
        @Override public ProcurementCase save(ProcurementCase value) { values.put(value.tenantId() + "|" + value.userId() + "|" + value.conversationId(), value); return value; }
    }
}
