package com.agent.platform.procurement;

import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.runtime.ToolExecutionState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.*;

/** The two indexes have different sources. Raw offer JSON is never converted to SupplierOffer. */
final class ProcurementAnswerEvidence {
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    record Fact(String subject, String property, String value, String currency, String path) { }
    record ReferenceFacts(List<Fact> facts, Map<String, String> products, String snapshot, String sourceAsOf, BigDecimal quantity) { }
    record ObservedEvidence(List<Fact> facts) { }

    static ReferenceFacts reference(EvaluationCase c, byte[] fixtureBytes) throws IOException {
        require(fixtureBytes != null && ProcurementEvaluationReports.sha256(fixtureBytes).equals(c.fixtureSha256()), "FIXTURE_HASH_MISMATCH");
        // Also bind the caller-supplied expected fields to the actual frozen benchmark, not merely its label/hash.
        try (var input = ProcurementAnswerEvidence.class.getResourceAsStream("/procurement/benchmark/procurement-benchmark-v1.json")) {
            require(input != null, "FROZEN_BENCHMARK_MISSING");
            byte[] bytes = input.readAllBytes();
            require(ProcurementEvaluationReports.sha256(bytes).equals(c.datasetSha256()), "DATASET_HASH_MISMATCH");
            var root = JSON.readTree(bytes);
            require(root.path("benchmarkVersion").asText().equals(c.datasetVersion())
                    && root.path("providerFixture").equals(c.providerFixture()), "DATASET_DEFINITION_MISMATCH");
            JsonNode found = null;
            for (var item : root.path("cases")) if (item.path("caseId").asText().equals(c.caseId())) found = item;
            require(found != null && found.path("expectedCase").equals(c.expectedCase()) && found.path("expected").equals(c.expected())
                    && found.path("userMessage").asText().equals(c.userMessage()), "FROZEN_CASE_MISMATCH");
        }
        var fixture = JSON.readTree(fixtureBytes);
        require(fixture.path("scenarioId").equals(c.providerFixture().path("scenarioId"))
                && fixture.path("sourceAsOf").equals(c.providerFixture().path("sourceAsOf")), "FIXTURE_IDENTITY_MISMATCH");
        require(fixture.path("offers").isArray() && !fixture.path("offers").isEmpty(), "MISSING_FIXTURE_OFFERS");
        List<Fact> facts = new ArrayList<>();
        Map<String, String> products = new TreeMap<>();
        BigDecimal quantity = c.expectedCase().path("quantity").decimalValue();
        int i = 0;
        for (var offer : fixture.path("offers")) {
            String subject = offer.path("supplierId").asText(), product = offer.path("productId").asText();
            require(!subject.isBlank() && !product.isBlank() && products.putIfAbsent(subject, product) == null, "AMBIGUOUS_FIXTURE_OFFER");
            String path = "fixture:/offers/" + i++;
            String currency = offer.path("currency").asText();
            require(currency.matches("[A-Z]{3}"), "INVALID_FIXTURE_CURRENCY");
            facts.add(new Fact(subject, "CURRENCY", currency, currency, path + "/currency"));
            BigDecimal unit = decimal(offer, "unitPrice"), lead = decimal(offer, "leadTimeDays");
            add(facts, subject, "UNIT_PRICE", unit, currency, path + "/unitPrice");
            add(facts, subject, "TOTAL_PRICE", unit.multiply(quantity), currency, path + "/unitPrice * benchmark:/expectedCase/quantity");
            add(facts, subject, "LEAD_TIME", lead, "", path + "/leadTimeDays");
        }
        add(facts, "case", "QUANTITY", quantity, "", "benchmark:/expectedCase/quantity");
        add(facts, "case", "REQUIRED_DELIVERY", c.expectedCase().path("requiredDeliveryDays").decimalValue(), "", "benchmark:/expectedCase/requiredDeliveryDays");
        String preferred = c.expected().path("preferredSupplierId").asText();
        facts.add(new Fact(preferred, "RECOMMENDATION", preferred, "", "benchmark:/expected/preferredSupplierId"));
        for (var eligible : c.expected().path("eligibleSupplierIds")) if (!eligible.asText().equals(preferred))
            facts.add(new Fact(eligible.asText(), "ALTERNATIVE", eligible.asText(), "", "benchmark:/expected/eligibleSupplierIds"));
        return new ReferenceFacts(List.copyOf(facts), Map.copyOf(products), "scenario:" + fixture.path("scenarioId").asText(), fixture.path("sourceAsOf").asText(), quantity);
    }

    static ObservedEvidence observed(ExecutionArtifact a, ReferenceFacts reference) {
        List<Fact> facts = new ArrayList<>();
        var state = JSON.valueToTree(a.finalCase().state());
        add(facts, "case", "QUANTITY", decimal(state, "quantity"), "", "artifact:/finalCase/state/quantity");
        add(facts, "case", "REQUIRED_DELIVERY", decimal(state, "requiredDeliveryDays"), "", "artifact:/finalCase/state/requiredDeliveryDays");
        int finalizations = 0;
        for (int i = 0; i < a.toolExecutions().size(); i++) {
            var record = a.toolExecutions().get(i);
            if (record.state() != ToolExecutionState.SUCCEEDED || record.result() == null || !record.result().success()) continue;
            String base = "artifact:/toolExecutions/" + i + "/result/content#";
            if (record.toolName().equals(ProcurementToolCatalog.SUPPLIER_SEARCH)) {
                var payload = JSON.readTree(record.result().content());
                bind(payload, a, false);
                require(!payload.has("offers") || payload.path("offers").isArray(), "INVALID_OFFERS_ARRAY");
                int j = 0;
                for (var offer : payload.path("offers")) offer(facts, offer, payload.path("evidence"), base + "/offers/" + j++, reference);
            } else if (record.toolName().equals(ProcurementToolCatalog.RECOMMENDATION_FINALIZE)) {
                var payload = JSON.readTree(record.result().content());
                bind(payload, a, true);
                require(++finalizations == 1, "AMBIGUOUS_FINALIZATIONS");
                var recommendation = payload.path("recommendation");
                require(recommendation.isObject(), "INVALID_RECOMMENDATION");
                require(!recommendation.has("eligibleAlternatives") || recommendation.path("eligibleAlternatives").isArray(), "INVALID_ALTERNATIVES_ARRAY");
                String supplier = recommendation.path("recommendedSupplier").path("supplierId").asText();
                if (!supplier.isBlank()) facts.add(new Fact(supplier, "RECOMMENDATION", supplier, "", base + "/recommendation/recommendedSupplier/supplierId"));
                int j = 0;
                for (var alternative : recommendation.path("eligibleAlternatives")) {
                    String id = alternative.path("supplierId").asText();
                    if (!id.isBlank()) facts.add(new Fact(id, "ALTERNATIVE", id, "", base + "/recommendation/eligibleAlternatives/" + j));
                    j++;
                }
                offer(facts, recommendation.path("selectedOffer"), payload.path("evidence"), base + "/recommendation/selectedOffer", reference);
            }
        }
        return new ObservedEvidence(List.copyOf(facts));
    }

    private static void offer(List<Fact> facts, JsonNode offer, JsonNode evidence, String path, ReferenceFacts reference) {
        String supplier = offer.path("supplierId").asText();
        // Missing/different product identity cannot support this frozen product's price.
        if (!Objects.equals(reference.products().get(supplier), offer.path("productId").asText())) return;
        // Missing provenance is not support. Contradicting provenance is invalid evidence, not an old quote to reuse.
        for (String field : List.of("source", "sourceRecordId", "sourceSnapshot", "sourceAsOf", "sourceDigest")) {
            if (!offer.path(field).isString() || offer.path(field).asText().isBlank()) return;
        }
        require(reference.snapshot().equals(offer.path("sourceSnapshot").asText())
                && reference.sourceAsOf().equals(offer.path("sourceAsOf").asText()), "STALE_OR_FOREIGN_OFFER:" + path);
        require(evidence.isArray(), "MISSING_OFFER_EVIDENCE_ARRAY");
        boolean grounded = false;
        for (var item : evidence) {
            if (!supplier.equals(item.path("supplierId").asText()) || !"OFFER".equals(item.path("evidenceType").asText())) continue;
            if (!offer.path("sourceRecordId").equals(item.path("sourceRecordId"))) continue;
            // SupplierEvidence validates its content-derived id; SupplierOffer is deliberately never constructed.
            JSON.treeToValue(item, SupplierEvidence.class);
            for (String field : List.of("source", "sourceSnapshot", "sourceAsOf", "sourceDigest"))
                require(offer.path(field).equals(item.path(field)), "CONFLICTING_OFFER_PROVENANCE:" + path);
            grounded = true;
        }
        if (!grounded) return;
        if (offer.has("quantity")) require(decimal(offer, "quantity").compareTo(reference.quantity()) == 0, "OFFER_QUANTITY_MISMATCH:" + path);
        if (offer.has("unitPrice") && offer.has("quantity") && offer.has("totalPrice"))
            require(decimal(offer, "unitPrice").multiply(decimal(offer, "quantity")).compareTo(decimal(offer, "totalPrice")) == 0,
                    "INCONSISTENT_RAW_OFFER_TOTAL:" + path);
        String currency = offer.path("currency").asText();
        if (currency.matches("[A-Z]{3}")) facts.add(new Fact(supplier, "CURRENCY", currency, currency, path + "/currency"));
        for (String field : List.of("unitPrice", "totalPrice", "leadTimeDays")) {
            if (!offer.has(field)) continue;
            if (field.equals("totalPrice") && !offer.has("quantity")) continue;
            if (!field.equals("leadTimeDays") && !currency.matches("[A-Z]{3}")) continue;
            String property = switch (field) { case "unitPrice" -> "UNIT_PRICE"; case "totalPrice" -> "TOTAL_PRICE"; default -> "LEAD_TIME"; };
            add(facts, supplier, property, decimal(offer, field), field.equals("leadTimeDays") ? "" : currency, path + "/" + field);
        }
    }
    private static void add(List<Fact> facts, String subject, String property, BigDecimal number, String currency, String path) {
        facts.add(new Fact(subject, property, number.stripTrailingZeros().toPlainString(), currency, path));
    }
    private static BigDecimal decimal(JsonNode node, String field) {
        require(node.path(field).isNumber(), "INVALID_NUMERIC_EVIDENCE:" + field);
        return node.path(field).decimalValue();
    }
    private static void bind(JsonNode payload, ExecutionArtifact a, boolean caseIdRequired) {
        require(payload != null && payload.isObject() && payload.path("caseVersion").isIntegralNumber()
                && payload.path("caseVersion").asLong() == a.finalCase().version()
                && (!caseIdRequired || payload.path("caseId").asText().equals(a.finalCase().caseId())), "OBSERVED_CASE_VERSION_MISMATCH");
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
