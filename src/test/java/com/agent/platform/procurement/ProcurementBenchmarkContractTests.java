package com.agent.platform.procurement;

import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.SupplierOffer;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcurementBenchmarkContractTests {
    private static final String RESOURCE = "/procurement/benchmark/procurement-benchmark-v1.json";
    private static final Set<String> ROOT_FIELDS = Set.of("benchmarkVersion", "providerFixture", "cases");
    private static final Set<String> CASE_FIELDS = Set.of("caseId", "title", "userMessage", "expectedCase", "expected");
    private static final Set<String> EXPECTED_CASE_FIELDS = Set.of("productCategory", "productDescription", "quantity",
            "budget", "currency", "requiredDeliveryDays", "hardConstraints", "preferences", "excludedSuppliers");
    private static final Set<String> EXPECTED_FIELDS = Set.of("status", "eligibleSupplierIds", "preferredSupplierId",
            "requiredTradeoffDimensions", "requiredEvidenceTypes");
    private static final Set<String> CASE_IDS = Set.of("delivery_priority_two_eligible", "price_priority_two_eligible",
            "single_eligible_after_exclusions", "no_eligible_under_hard_constraints");
    private static final Set<String> FORBIDDEN_KEYS = Set.of("offers", "unitPrice", "totalPrice", "leadTimeDays",
            "warranty", "specifications", "supplierFacts", "evidenceRefs", "evidenceId", "createRfq", "rfq",
            "approval", "purchaseOrder", "payment", "expectedAnswerText", "expectedTokens", "expectedModelCalls",
            "expectedCost", "score", "risk", "compliance");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void benchmarkV1HasStableMinimalContract() throws IOException {
        JsonNode root = loadBenchmark();
        exactFields(root, ROOT_FIELDS, "root");
        assertEquals("procurement-benchmark-v1", root.path("benchmarkVersion").asText());
        JsonNode fixture = root.path("providerFixture");
        exactFields(fixture, Set.of("provider", "scenarioDir", "scenarioFile", "scenarioId", "sourceAsOf"), "providerFixture");
        assertEquals("synthetic", fixture.path("provider").asText());
        assertEquals("data/procurement/scenarios", fixture.path("scenarioDir").asText());
        assertEquals("complex_workstation_01.json", fixture.path("scenarioFile").asText());
        assertEquals("complex_workstation_01", fixture.path("scenarioId").asText());
        assertEquals("2026-01-01T00:00:00Z", fixture.path("sourceAsOf").asText());

        JsonNode cases = root.path("cases");
        assertTrue(cases.isArray());
        assertEquals(4, cases.size());
        Set<String> ids = new HashSet<>();
        for (JsonNode caseNode : cases) {
            String caseId = caseNode.path("caseId").asText();
            assertTrue(ids.add(caseId), caseId + " must be unique");
            assertTrue(CASE_IDS.contains(caseId), caseId + " is not a fixed v1 case");
            exactFields(caseNode, CASE_FIELDS, caseId);
            assertFalse(caseNode.path("title").asText().isBlank(), caseId + " title is blank");
            String userMessage = caseNode.path("userMessage").asText();
            assertFalse(userMessage.isBlank(), caseId + " userMessage is blank");

            JsonNode expectedCase = caseNode.path("expectedCase");
            exactFields(expectedCase, EXPECTED_CASE_FIELDS, caseId + " expectedCase");
            assertTrue(expectedCase.path("quantity").asInt() > 0, caseId + " quantity");
            assertTrue(expectedCase.path("budget").decimalValue().compareTo(BigDecimal.ZERO) >= 0, caseId + " budget");
            assertEquals("CNY", expectedCase.path("currency").asText(), caseId + " currency");
            assertTrue(expectedCase.path("requiredDeliveryDays").asInt() > 0, caseId + " delivery");
            assertEquals(Set.of("gpuMemoryMinGb"), fieldNames(expectedCase.path("hardConstraints")), caseId + " hard constraints");
            assertTrue(fieldNames(expectedCase.path("preferences")).stream()
                    .allMatch(key -> Set.of("deliveryPriority", "pricePriority").contains(key)), caseId + " preferences");
            expectedCase.path("preferences").properties().forEach(entry ->
                    assertEquals("HIGH", entry.getValue().asText(), caseId + " preference"));

            JsonNode expected = caseNode.path("expected");
            exactFields(expected, EXPECTED_FIELDS, caseId + " expected");
            assertFixedCaseSemantics(caseId, expectedCase, expected);
            String status = expected.path("status").asText();
            assertTrue(Set.of("RECOMMENDABLE", "NO_ELIGIBLE").contains(status), caseId + " status");
            JsonNode eligibleIds = expected.path("eligibleSupplierIds");
            assertTrue(eligibleIds.isArray(), caseId + " eligibleSupplierIds must be an array");
            List<String> expectedEligibleIds = values(eligibleIds);
            assertTrue(expectedEligibleIds.stream().noneMatch(String::isBlank), caseId + " eligible supplier id is blank");
            assertEquals(expectedEligibleIds.size(), new HashSet<>(expectedEligibleIds).size(),
                    caseId + " eligible supplier ids must be unique");
            JsonNode preferred = expected.path("preferredSupplierId");
            if ("RECOMMENDABLE".equals(status)) {
                assertFalse(expectedEligibleIds.isEmpty(), caseId + " recommendable case has no eligible supplier");
                assertTrue(!preferred.isNull() && !preferred.asText().isBlank(),
                        caseId + " recommendable case must have a preferred supplier");
                assertTrue(expectedEligibleIds.contains(preferred.asText()),
                        caseId + " preferred supplier is not eligible");
                String normalizedPreferred = normalizeSupplierToken(preferred.asText());
                assertFalse(normalizedPreferred.isBlank(), caseId + " preferred supplier is blank after normalization");
                assertFalse(normalizeSupplierToken(userMessage).contains(normalizedPreferred),
                        caseId + " userMessage leaks the preferred answer");
            } else {
                assertTrue(expectedEligibleIds.isEmpty(), caseId + " no-eligible case has eligible suppliers");
                assertTrue(preferred.isNull(), caseId + " no-eligible case must have JSON null preferred supplier");
                assertEquals(List.of(), values(expected.path("requiredTradeoffDimensions")),
                        caseId + " no-eligible case must have no tradeoff dimensions");
            }
            int eligibleCount = expectedEligibleIds.size();
            if (eligibleCount > 1) {
                assertEquals(1, expectedCase.path("preferences").size(), caseId + " must have one soft priority");
                assertEquals(List.of("PRICE", "DELIVERY"), values(expected.path("requiredTradeoffDimensions")), caseId + " tradeoff");
            } else {
                assertEquals(0, expectedCase.path("preferences").size(), caseId + " must have no soft priority");
                assertEquals(List.of(), values(expected.path("requiredTradeoffDimensions")), caseId + " tradeoff");
            }
            assertEquals(List.of("OFFER"), values(expected.path("requiredEvidenceTypes")), caseId + " evidence");
        }
        assertEquals(CASE_IDS, ids);
    }

    @Test
    void benchmarkV1GroundTruthMatchesFrozenProviderAndPreferenceRubric() throws IOException {
        JsonNode root = loadBenchmark();
        ProcurementDataProperties properties = new ProcurementDataProperties();
        assertEquals(root.path("providerFixture").path("provider").asText(), properties.getProvider());
        assertEquals(root.path("providerFixture").path("scenarioDir").asText(), properties.getScenarioDir());
        assertEquals(root.path("providerFixture").path("scenarioFile").asText(), properties.getScenarioFile());
        AwsSyntheticProcurementProvider provider = new AwsSyntheticProcurementProvider(mapper, properties);
        ProcurementDecisionEngine engine = new ProcurementDecisionEngine();
        Map<String, Set<String>> actualEligibleByCase = new LinkedHashMap<>();

        for (JsonNode caseNode : root.path("cases")) {
            String caseId = caseNode.path("caseId").asText();
            ProcurementCaseState state = toCaseState(caseNode.path("expectedCase"));
            var candidates = provider.searchSuppliers(state);
            var offers = provider.getSupplierOffers(state, candidates);
            var evaluation = engine.evaluate(state, candidates, offers);
            Set<String> actualEligible = eligibleSupplierIds(evaluation);
            Set<String> expectedEligible = new HashSet<>(values(caseNode.path("expected").path("eligibleSupplierIds")));
            assertEquals(expectedEligible, actualEligible, caseId + " eligibility mismatch");
            actualEligibleByCase.put(caseId, actualEligible);

            assertTrue(offers.stream().allMatch(offer -> offer.sourceSnapshot().equals("scenario:complex_workstation_01")
                    && offer.sourceAsOf().equals(Instant.parse("2026-01-01T00:00:00Z"))), caseId + " fixture provenance");
            Map<String, SupplierOffer> eligibleOffers = offersBySupplier(offers, actualEligible);
            assertEquals(actualEligible, eligibleOffers.keySet(), caseId + " eligible offer coverage");
            JsonNode preferredNode = caseNode.path("expected").path("preferredSupplierId");
            if (preferredNode.isNull()) {
                assertEquals("NO_ELIGIBLE", caseNode.path("expected").path("status").asText(), caseId + " status");
            } else {
                String preferred = preferredNode.asText();
                assertEquals(preferred, preferredSupplierId(caseId, caseNode.path("expectedCase"), actualEligible, eligibleOffers),
                        caseId + " preference rubric mismatch");
                assertTrue(provider.getSupplierEvidence(preferred, state).stream()
                        .anyMatch(evidence -> evidence.evidenceType().equals("OFFER")), caseId + " OFFER evidence");
            }
        }
        assertEquals(Set.of("supplier-b", "supplier-d"), actualEligibleByCase.get("delivery_priority_two_eligible"));
        assertEquals(actualEligibleByCase.get("delivery_priority_two_eligible"),
                actualEligibleByCase.get("price_priority_two_eligible"), "paired eligibility mismatch");
        assertEquals(Set.of("supplier-d"), actualEligibleByCase.get("single_eligible_after_exclusions"));
        assertEquals(Set.of(), actualEligibleByCase.get("no_eligible_under_hard_constraints"));
    }

    @Test
    void benchmarkV1DoesNotLeakProviderFactsOrSideEffectFields() throws IOException {
        for (JsonNode caseNode : loadBenchmark().path("cases")) {
            assertNoForbiddenKeys(caseNode, caseNode.path("caseId").asText());
        }
    }

    private JsonNode loadBenchmark() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input, "benchmark resource missing: " + RESOURCE);
            return mapper.readTree(input);
        }
    }

    private void exactFields(JsonNode node, Set<String> expected, String label) {
        assertEquals(expected, fieldNames(node), label + " fields");
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        if (node != null && node.isObject()) node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    private List<String> values(JsonNode node) {
        List<String> values = new java.util.ArrayList<>();
        if (node != null && node.isArray()) node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private ProcurementCaseState toCaseState(JsonNode expectedCase) {
        return new ProcurementCaseState(expectedCase.path("productCategory").asText(),
                expectedCase.path("productDescription").asText(), expectedCase.path("quantity").asInt(),
                expectedCase.path("budget").decimalValue(), expectedCase.path("currency").asText(),
                expectedCase.path("requiredDeliveryDays").asInt(), stringMap(expectedCase.path("hardConstraints")),
                stringMap(expectedCase.path("preferences")), new HashSet<>(values(expectedCase.path("excludedSuppliers"))),
                List.of(), "SOURCING");
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return result;
    }

    private Set<String> eligibleSupplierIds(ProcurementDecisionEngine.Evaluation evaluation) {
        return evaluation.candidates().stream().filter(ProcurementDecisionEngine.CandidateResult::eligible)
                .map(result -> result.candidate().supplierId()).collect(Collectors.toSet());
    }

    private Map<String, SupplierOffer> offersBySupplier(List<SupplierOffer> offers, Set<String> eligible) {
        return offers.stream().filter(offer -> eligible.contains(offer.supplierId()))
                .collect(Collectors.toMap(SupplierOffer::supplierId, offer -> offer, (left, right) -> left, LinkedHashMap::new));
    }

    // MIN_LEAD_TIME and MIN_TOTAL_PRICE are benchmark rubrics for explicit preferences, not production scoring rules.
    private void assertFixedCaseSemantics(String caseId, JsonNode expectedCase, JsonNode expected) {
        switch (caseId) {
            case "delivery_priority_two_eligible" -> {
                assertEquals(Map.of("deliveryPriority", "HIGH"), stringMap(expectedCase.path("preferences")), caseId + " preferences");
                assertEquals("RECOMMENDABLE", expected.path("status").asText(), caseId + " status");
                assertEquals("supplier-d", expected.path("preferredSupplierId").asText(), caseId + " preferred");
            }
            case "price_priority_two_eligible" -> {
                assertEquals(Map.of("pricePriority", "HIGH"), stringMap(expectedCase.path("preferences")), caseId + " preferences");
                assertEquals("RECOMMENDABLE", expected.path("status").asText(), caseId + " status");
                assertEquals("supplier-b", expected.path("preferredSupplierId").asText(), caseId + " preferred");
            }
            case "single_eligible_after_exclusions" -> {
                assertEquals(Map.of(), stringMap(expectedCase.path("preferences")), caseId + " preferences");
                assertEquals("RECOMMENDABLE", expected.path("status").asText(), caseId + " status");
                assertEquals("supplier-d", expected.path("preferredSupplierId").asText(), caseId + " preferred");
            }
            case "no_eligible_under_hard_constraints" -> {
                assertEquals(Map.of(), stringMap(expectedCase.path("preferences")), caseId + " preferences");
                assertEquals("NO_ELIGIBLE", expected.path("status").asText(), caseId + " status");
                assertTrue(expected.path("preferredSupplierId").isNull(), caseId + " preferred must be null");
            }
            default -> throw new AssertionError("unexpected fixed v1 case: " + caseId);
        }
    }

    private String normalizeSupplierToken(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String preferredSupplierId(String caseId, JsonNode expectedCase, Set<String> eligible, Map<String, SupplierOffer> offers) {
        if (eligible.size() == 1) return eligible.iterator().next();
        List<SupplierOffer> winners;
        if (expectedCase.path("preferences").has("deliveryPriority")) {
            int minimumLeadTime = offers.values().stream().mapToInt(SupplierOffer::leadTimeDays).min().orElseThrow();
            winners = offers.values().stream().filter(offer -> offer.leadTimeDays() == minimumLeadTime).toList();
        } else if (expectedCase.path("preferences").has("pricePriority")) {
            BigDecimal minimumPrice = offers.values().stream().map(SupplierOffer::totalPrice).min(BigDecimal::compareTo).orElseThrow();
            winners = offers.values().stream().filter(offer -> offer.totalPrice().compareTo(minimumPrice) == 0).toList();
        } else {
            throw new AssertionError(caseId + " multi-eligible case has no supported preference");
        }
        assertEquals(1, winners.size(), caseId + " preference minimum is ambiguous");
        return winners.get(0).supplierId();
    }

    private void assertNoForbiddenKeys(JsonNode node, String path) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                assertFalse(FORBIDDEN_KEYS.contains(entry.getKey()), path + " contains forbidden key " + entry.getKey());
                assertNoForbiddenKeys(entry.getValue(), path + "." + entry.getKey());
            });
        } else if (node.isArray()) {
            node.forEach(value -> assertNoForbiddenKeys(value, path));
        }
    }
}
