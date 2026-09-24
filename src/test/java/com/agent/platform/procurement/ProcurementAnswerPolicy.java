package com.agent.platform.procurement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.EvaluationCase;

/** Reviewed requirements only. Loading these definitions does not grade answer completeness. */
public final class ProcurementAnswerPolicy {
    public static final String RESOURCE = "/procurement/evaluation/procurement-answer-policy-v1.json";
    public static final String SCHEMA = "procurement-answer-policy-v1";
    public static final String VERSION = "procurement-answer-requirements-v1";
    public record Source(String kind, String reference, String quote) { }
    public record Element(String elementId, String caseId, String requirement, List<Source> sources,
                          String rationale, List<String> acceptedClaimTypes) {
        public Element { sources = List.copyOf(sources); acceptedClaimTypes = List.copyOf(acceptedClaimTypes); }
    }
    public record CasePolicy(String caseId, List<Element> elements) {
        public CasePolicy { elements = List.copyOf(elements); }
    }
    public record Policy(String schemaVersion, String policyVersion, String policySha256,
                         String datasetSha256, String fixtureSha256, List<CasePolicy> cases) {
        public Policy { cases = List.copyOf(cases); }
        public CasePolicy forCase(String caseId) {
            return cases.stream().filter(c -> c.caseId().equals(caseId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("POLICY_CASE_MISSING: " + caseId));
        }
    }
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> TYPES = Set.of("RECOMMENDATION", "TOTAL_PRICE", "LEAD_TIME", "NO_ELIGIBLE", "UNIQUE_ELIGIBLE",
            "PAIRWISE_LEAD_TIME_COMPARISON", "PAIRWISE_PRICE_COMPARISON", "WITHIN_BUDGET", "WITHIN_DELIVERY_LIMIT");
    private static final Map<String, String> CONVENTIONS = Map.of(
            "policy:decision-summary-v1", "采购决策摘要应说明选定供应商的总价和报价交期。",
            "policy:preference-explanation-v1", "双候选场景需要说明主要偏好的取舍依据。");
    private ProcurementAnswerPolicy() { }

    public static byte[] resourceBytes() throws IOException {
        try (var input = ProcurementAnswerPolicy.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("ANSWER_POLICY_MISSING");
            return input.readAllBytes();
        }
    }

    public static Policy load(byte[] bytes, EvaluationCase definition, byte[] fixtureBytes) throws IOException {
        require(bytes != null && bytes.length > 0, "ANSWER_POLICY_MISSING");
        // Reuse the frozen content binding, never infer policy from a response or tool result.
        ProcurementAnswerEvidence.reference(definition, fixtureBytes);
        JsonNode root = JSON.readTree(bytes);
        require(root != null && root.isObject(), "INVALID_POLICY_OBJECT");
        fields(root, "schemaVersion", "policyVersion", "datasetSha256", "fixtureSha256", "cases");
        require(SCHEMA.equals(text(root, "schemaVersion")), "UNSUPPORTED_POLICY_SCHEMA");
        require(VERSION.equals(text(root, "policyVersion")), "UNSUPPORTED_POLICY_VERSION");
        require(definition.datasetSha256().equals(text(root, "datasetSha256")), "POLICY_DATASET_HASH_MISMATCH");
        require(definition.fixtureSha256().equals(text(root, "fixtureSha256")), "POLICY_FIXTURE_HASH_MISMATCH");
        JsonNode benchmark;
        try (var input = ProcurementAnswerPolicy.class.getResourceAsStream("/procurement/benchmark/procurement-benchmark-v1.json")) {
            require(input != null, "BENCHMARK_MISSING"); benchmark = JSON.readTree(input.readAllBytes());
        }
        Map<String, JsonNode> frozenCases = new LinkedHashMap<>();
        for (var c : benchmark.path("cases")) frozenCases.put(c.path("caseId").asText(), c);
        require(root.path("cases").isArray(), "POLICY_CASES_REQUIRED");
        Set<String> seen = new HashSet<>();
        List<CasePolicy> cases = new ArrayList<>();
        for (var c : root.path("cases")) {
            fields(c, "caseId", "elements");
            String id = text(c, "caseId");
            require(frozenCases.containsKey(id) && seen.add(id), "UNKNOWN_OR_DUPLICATE_POLICY_CASE");
            require(c.path("elements").isArray() && !c.path("elements").isEmpty(), "EMPTY_POLICY_ELEMENTS");
            JsonNode frozen = frozenCases.get(id);
            List<Element> elements = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (var e : c.path("elements")) {
                fields(e, "elementId", "requirement", "sources", "rationale", "acceptedClaimTypes");
                String elementId = text(e, "elementId");
                require(ids.add(elementId), "DUPLICATE_ELEMENT_ID");
                List<String> types = new ArrayList<>();
                require(e.path("acceptedClaimTypes").isArray() && !e.path("acceptedClaimTypes").isEmpty(), "CLAIM_TYPES_REQUIRED");
                for (var t : e.path("acceptedClaimTypes")) {
                    require(t.isString() && TYPES.contains(t.asText()) && !types.contains(t.asText()), "UNKNOWN_OR_DUPLICATE_CLAIM_TYPE");
                    types.add(t.asText());
                }
                boolean noEligible = frozen.path("expected").path("status").asText().equals("NO_ELIGIBLE");
                require(!noEligible || types.equals(List.of("NO_ELIGIBLE")), "INAPPLICABLE_REQUIREMENT_FOR_NO_ELIGIBLE");
                require(noEligible || !types.contains("NO_ELIGIBLE"), "INAPPLICABLE_NO_ELIGIBLE_REQUIREMENT");
                require(frozen.path("expected").path("eligibleSupplierIds").size() > 1
                        || types.stream().noneMatch(t -> t.startsWith("PAIRWISE_")), "PAIRWISE_REQUIREMENT_WITHOUT_ALTERNATIVE");
                require(!types.contains("UNIQUE_ELIGIBLE") || frozen.path("expected").path("eligibleSupplierIds").size() == 1,
                        "UNIQUE_REQUIREMENT_WITHOUT_SINGLE_CANDIDATE");
                require(e.path("sources").isArray() && !e.path("sources").isEmpty(), "REQUIREMENT_SOURCE_MISSING");
                List<Source> sources = new ArrayList<>();
                for (var s : e.path("sources")) {
                    fields(s, "kind", "reference", "quote");
                    String kind = text(s, "kind"), ref = text(s, "reference"), quote = text(s, "quote");
                    if (kind.equals("USER_REQUEST")) {
                        require(ref.equals("/userMessage") && frozen.path("userMessage").asText().contains(quote), "UNTRACEABLE_USER_SOURCE");
                    } else if (kind.equals("BENCHMARK")) {
                        require(ref.startsWith("/expected/") || ref.startsWith("/expectedCase/"), "INVALID_BENCHMARK_SOURCE_PATH");
                        JsonNode value = frozen.at(ref);
                        require(!value.isMissingNode() && value.isValueNode() && value.asText().equals(quote), "UNTRACEABLE_BENCHMARK_SOURCE");
                    } else {
                        require(kind.equals("SCENARIO_POLICY") && quote.equals(CONVENTIONS.get(ref)), "UNKNOWN_OR_MISQUOTED_SCENARIO_CONVENTION");
                    }
                    sources.add(new Source(kind, ref, quote));
                }
                elements.add(new Element(elementId, id, text(e, "requirement"), sources, text(e, "rationale"), types));
            }
            cases.add(new CasePolicy(id, elements));
        }
        require(seen.equals(frozenCases.keySet()), "POLICY_CASE_COVERAGE_MISMATCH");
        return new Policy(SCHEMA, VERSION, ProcurementEvaluationReports.sha256(bytes), definition.datasetSha256(), definition.fixtureSha256(), cases);
    }
    private static String text(JsonNode node, String field) {
        require(node.path(field).isString() && !node.path(field).asText().isBlank(), "MISSING_POLICY_FIELD: " + field);
        return node.path(field).asText();
    }
    private static void fields(JsonNode node, String... expected) {
        require(node.isObject() && node.properties().stream().map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()).equals(Set.of(expected)), "UNKNOWN_OR_MISSING_POLICY_FIELDS");
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
