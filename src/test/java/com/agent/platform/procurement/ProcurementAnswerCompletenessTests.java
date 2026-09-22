package com.agent.platform.procurement;

import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static com.agent.platform.procurement.ProcurementAnswerCompletenessGrader.Presence;
import static com.agent.platform.procurement.ProcurementAnswerEffectiveCoverage.EffectiveKind;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementAnswerCompletenessTests {
    private static final Path FIXTURE = Path.of("data/procurement/scenarios/complex_workstation_01.json");
    private static final Path POLICY = Path.of("src/test/resources/procurement/evaluation/procurement-answer-policy-v1.json");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementAnswerCompletenessGrader grader = new ProcurementAnswerCompletenessGrader();
    @TempDir Path directory;
    private static final String DELIVERY = "推荐 Supplier D，总价 58 万元，交期 12 天。D 比 B 快 6 天。D 的总价满足本次预算。";
    private static final String PRICE = "推荐 Supplier B，总价 55 万元，交期 18 天。B 比 D 便宜 3 万元。B 的交期满足本次交付期限。";
    private static final String SINGLE = "推荐 Supplier D，总价 58 万元，交期 12 天。当前原始硬约束下仅 D 合格。";
    private static final String NONE = "当前原始硬约束下无合格供应商。";

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void fourPoliciesMatchOriginalTextWithoutWholeAnswerPass(int index) throws Exception {
        String answer = List.of(DELIVERY, PRICE, SINGLE, NONE).get(index);
        var result = grade(index, answer);
        assertEquals(Status.SKIP, result.evaluationStatus(), result.errors().toString());
        assertEquals(Status.PASS, result.completenessStatus());
        assertEquals(List.of(5, 5, 4, 1).get(index), result.elements().size());
        assertTrue(result.elements().stream().allMatch(e -> e.presence() == Presence.PRESENT));
        assertFalse(result.effectiveCoverage().hasUnresolvedContent());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
        var refs = result.elements().stream().flatMap(e -> e.matchedClaimRefs().stream()).toList();
        assertEquals(refs.size(), new HashSet<>(refs).size());
        assertEquals(new ProcurementAnswerCoverage().analyze(answer), result.effectiveCoverage().original());
        assertEquals(result, mapper.readValue(mapper.writeValueAsString(result), ProcurementAnswerCompletenessGrader.Evaluation.class));
    }

    @ParameterizedTest @ValueSource(strings = {"recommendation", "selected_total", "selected_lead_time", "delivery_advantage", "budget_compliance"})
    void deletingElementChangesPresence(String element) throws Exception {
        String answer = switch (element) {
            case "recommendation" -> DELIVERY.replace("推荐 Supplier D", "Supplier D");
            case "selected_total" -> DELIVERY.replace("总价 58 万元，", "");
            case "selected_lead_time" -> DELIVERY.replace("，交期 12 天", "");
            case "delivery_advantage" -> DELIVERY.replace("D 比 B 快 6 天。", "");
            default -> DELIVERY.replace("D 的总价满足本次预算。", "");
        };
        var result = grade(0, answer);
        // A bare supplier is unresolved under the frozen scalar grammar; it cannot prove absence.
        assertEquals(element.equals("recommendation") ? Presence.UNRESOLVED : Presence.MISSING, element(result, element).presence());
        assertNotEquals(Status.PASS, result.completeAnswerStatus());
    }

    @Test void definitelyMissingRecommendationIsMissing() throws Exception {
        var result = grade(0, "采购数量 50 台。");
        assertEquals(Presence.MISSING, element(result, "recommendation").presence());
    }

    @ParameterizedTest @ValueSource(strings = {"amount", "lead", "direction", "difference"})
    void explicitWrongFactsRemainPresent(String mutation) throws Exception {
        String answer = switch (mutation) {
            case "amount" -> DELIVERY.replace("58 万元", "57 万元");
            case "lead" -> DELIVERY.replace("12 天", "18 天");
            case "direction" -> DELIVERY.replace("快 6 天", "慢 6 天");
            default -> DELIVERY.replace("快 6 天", "快 5 天");
        };
        var result = grade(0, answer);
        assertEquals(Status.PASS, result.completenessStatus());
        assertTrue(result.elements().stream().allMatch(e -> e.presence() == Presence.PRESENT));
        assertFalse(result.effectiveCoverage().hasUnresolvedContent());
        assertTrue(result.baseEvaluation().legacyEvaluation().claims().stream().anyMatch(r -> r.factualCorrectness().status() == Status.FAIL)
                || result.relationEvaluation().results().stream().anyMatch(r -> r.factualDirection().status() == Status.FAIL || r.factualDifference().status() == Status.FAIL));
        assertEquals(Status.SKIP, result.completeAnswerStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"alternative", "requiredDeadline", "comparisonOnly", "wrongComparisonSubject", "separateFacts"})
    void rolePropertyAndScopeCannotBeSubstituted(String variant) throws Exception {
        String answer = switch (variant) {
            case "alternative" -> "推荐 Supplier D。备选 Supplier B，总价 55 万元。";
            case "requiredDeadline" -> "推荐 Supplier D，用户要求期限 21 天。";
            case "comparisonOnly" -> "推荐 Supplier D。D 比 B 快 6 天。";
            case "wrongComparisonSubject" -> DELIVERY.replace("D 比 B", "C 比 B");
            default -> DELIVERY.replace("D 比 B 快 6 天。", "Supplier D 的交期 12 天。Supplier B 的交期 18 天。");
        };
        var result = grade(0, answer);
        String element = variant.equals("alternative") ? "selected_total" : Set.of("requiredDeadline", "comparisonOnly").contains(variant) ? "selected_lead_time" : "delivery_advantage";
        assertEquals(Presence.MISSING, element(result, element).presence(), result.toString());
    }

    @Test void independentPricesDoNotExpressPriceAdvantage() throws Exception {
        var result = grade(1, PRICE.replace("B 比 D 便宜 3 万元。", "Supplier D 的总价 58 万元。"));
        assertEquals(Presence.MISSING, element(result, "price_advantage").presence());
    }

    @Test void reversedPairStillExpressesComparisonWithRecommendedCandidate() throws Exception {
        var result = grade(0, DELIVERY.replace("D 比 B 快 6 天", "B 比 D 慢 6 天"));
        assertEquals(Presence.PRESENT, element(result, "delivery_advantage").presence());
    }

    @Test void parsingDoesNotTurnMissingExecutionSupportIntoBusinessPass() throws Exception {
        var a = change(good(0, DELIVERY), n -> n.set("toolExecutions", mapper.createArrayNode()));
        var result = grader.grade(definition(0), a, Files.readAllBytes(FIXTURE), ProcurementAnswerPolicy.resourceBytes());
        assertFalse(result.effectiveCoverage().hasUnresolvedContent());
        assertEquals(Status.PASS, result.completenessStatus());
        assertTrue(result.relationEvaluation().results().stream().anyMatch(r -> r.claim().unresolvedReason().isEmpty() && r.evidenceDirection().status() == Status.SKIP));
        assertEquals(Status.SKIP, result.completeAnswerStatus());
    }

    @Test void missingRecommendationDoesNotAssignIndependentQuoteToExpectedWinner() throws Exception {
        var result = grade(0, "Supplier D 的总价 58 万元。Supplier D 的交期 12 天。");
        assertEquals(Presence.MISSING, element(result, "recommendation").presence());
        assertEquals(Presence.UNRESOLVED, element(result, "selected_total").presence());
        assertEquals(Presence.UNRESOLVED, element(result, "selected_lead_time").presence());
    }

    @ParameterizedTest @ValueSource(strings = {"reference", "identity", "hideUnknown", "duplicateElement", "missingElements", "emptyElements", "policyHash"})
    void inconsistentSerializedCompositionIsRejected(String mutation) throws Exception {
        ObjectNode raw = mapper.valueToTree(grade(0, DELIVERY + "保证绝不延期。"));
        switch (mutation) {
            case "reference" -> ((ObjectNode) raw.path("elements").get(0)).set("matchedClaimRefs", mapper.valueToTree(List.of("relation:999")));
            case "identity" -> ((ObjectNode) raw.path("relationEvaluation")).put("runId", "other");
            case "hideUnknown" -> {
                var coverage = (ObjectNode) raw.path("effectiveCoverage");
                for (var s : coverage.path("segments")) if (s.path("kind").asText().equals("UNRESOLVED_BUSINESS"))
                    ((ObjectNode) s).put("kind", "NON_FACTUAL");
                coverage.put("hasUnresolvedContent", false);
            }
            case "duplicateElement" -> ((ObjectNode) raw.path("elements").get(1)).put("elementId", "recommendation");
            case "missingElements" -> ((tools.jackson.databind.node.ArrayNode) raw.path("elements")).remove(0);
            case "emptyElements" -> { raw.set("elements", mapper.createArrayNode()); raw.put("completenessStatus", "SKIP"); }
            case "policyHash" -> ((ObjectNode) raw.path("baseEvaluation")).put("policySha256", "0".repeat(64));
        }
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(raw, ProcurementAnswerCompletenessGrader.Evaluation.class));
    }

    @Test void coverageCannotConsumeRelationsFromAnotherAnswer() throws Exception {
        var one = grade(0, DELIVERY); var two = grade(0, DELIVERY.replace("快 6 天", "快 5 天"));
        assertThrows(IllegalArgumentException.class, () -> new ProcurementAnswerEffectiveCoverage().analyze(DELIVERY,
                one.baseEvaluation().coverage(), two.relationEvaluation().results()));
    }

    @ParameterizedTest @ValueSource(strings = {"星河璀璨。", "总价五十八万元。", "供应商保证绝不延期。"})
    void unknownTextMakesUnmatchedElementsUnresolved(String text) throws Exception {
        var result = grade(0, "推荐 Supplier D。" + text);
        var total = element(result, "selected_total");
        assertEquals(Presence.UNRESOLVED, total.presence());
        assertFalse(total.candidateFragmentRefs().isEmpty());
        assertTrue(total.matchedClaimRefs().isEmpty());
        assertTrue(result.effectiveCoverage().hasUnresolvedContent());
    }

    @ParameterizedTest @ValueSource(strings = {"但保证绝不延期", "神奇魔法", "供应商信誉全球第一"})
    void appendedPromiseCannotBeCoveredByAdjacentRelation(String suffix) throws Exception {
        var result = grade(0, DELIVERY + suffix + "。");
        assertEquals(Status.PASS, result.completenessStatus());
        assertTrue(result.effectiveCoverage().hasUnresolvedContent());
        var last = result.effectiveCoverage().segments().stream().filter(s -> ProcurementAnswerEffectiveCoverage.unresolved(s.kind())).toList();
        assertFalse(last.isEmpty());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
        result = grade(0, "D 比 B 快 6 天，" + suffix + "。");
        assertTrue(result.effectiveCoverage().hasUnresolvedContent());
        assertTrue(result.effectiveCoverage().segments().stream().anyMatch(s -> s.kind() == EffectiveKind.PARSED_RELATION));
    }

    @ParameterizedTest @ValueSource(strings = {"如果批准，D 比 B 快 6 天。", "D 不比 B 快。", "D 比 B 快 6 天？", "“D 比 B 快 6 天”。", "D 在全部供应商中最快。", "D 比 B 快 6 天且绝不延期。"})
    void qualifiedOrPartialClausesNeverUpgrade(String answer) throws Exception {
        var result = grade(0, answer);
        assertTrue(result.effectiveCoverage().hasUnresolvedContent());
        assertTrue(result.effectiveCoverage().segments().stream().noneMatch(s -> s.kind() == EffectiveKind.PARSED_RELATION));
    }

    @Test void utf16AndReferencesRecoverExactSource() throws Exception {
        String answer = "😀𠮷。  D 比 B 快 6 天  。";
        var result = grade(0, answer); var coverage = result.effectiveCoverage();
        assertEquals(answer, coverage.original().fragments().stream().map(ProcurementAnswerCoverage.Fragment::text).collect(java.util.stream.Collectors.joining()));
        for (var segment : coverage.segments()) {
            var original = coverage.original().fragments().get(segment.originalFragmentIndex());
            assertEquals(original.text(), answer.substring(original.start(), original.end()));
            for (String ref : segment.claimRefs()) if (ref.startsWith("relation:")) {
                var claim = result.relationEvaluation().results().get(Integer.parseInt(ref.substring(9))).claim();
                assertEquals(claim.text(), answer.substring(claim.start(), claim.end()));
                assertEquals(original.text().strip(), claim.text());
            }
        }
    }

    @Test void multipleRecommendationsDoNotChooseSubjectAutomatically() throws Exception {
        var result = grade(0, "推荐 Supplier D，总价 58 万元。推荐 Supplier B，总价 55 万元。");
        assertEquals(Presence.UNRESOLVED, element(result, "recommendation").presence());
        assertEquals(Presence.UNRESOLVED, element(result, "selected_total").presence());
    }

    @ParameterizedTest @ValueSource(strings = {"", "谢谢。", "！！！"})
    void emptyAndNonFactualAnswersDoNotPassCompleteness(String answer) throws Exception {
        var result = grade(0, answer);
        assertNotEquals(Status.PASS, result.completenessStatus());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"policyVersion", "policyContent", "policyDataset", "fixture", "case", "run"})
    void identityAndPolicyChangesFailClosed(String variant) throws Exception {
        byte[] policy = ProcurementAnswerPolicy.resourceBytes(), fixture = Files.readAllBytes(FIXTURE);
        var a = good(0, DELIVERY);
        if (variant.startsWith("policy")) {
            ObjectNode raw = (ObjectNode) mapper.readTree(policy);
            if (variant.equals("policyVersion")) raw.put("policyVersion", "future");
            else if (variant.equals("policyDataset")) raw.put("datasetSha256", "0".repeat(64));
            else ((ObjectNode) raw.path("cases").get(0).path("elements").get(0)).put("rationale", "changed rule rationale");
            policy = mapper.writeValueAsBytes(raw);
        } else if (variant.equals("fixture")) fixture = new byte[0];
        else if (variant.equals("case")) a = change(a, n -> n.put("caseId", "other"));
        else a = change(a, n -> ((ObjectNode) n.path("toolExecutions").get(0)).put("runId", "other"));
        var result = grader.grade(definition(0), a, fixture, policy);
        assertEquals(Status.ERROR, result.evaluationStatus());
        assertTrue(result.elements().isEmpty());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
    }

    @Test void offlineReplayRetainsOldResultsAndCannotOverwriteFiles() throws Exception {
        var c = definition(0); var a = good(0, DELIVERY);
        Path source = directory.resolve("artifact.json"), old = directory.resolve("v2.json"), out = directory.resolve("presence.json");
        var baseline = new ProcurementAnswerCoverageEvaluator().evaluate(c, a, Files.readAllBytes(FIXTURE), ProcurementAnswerPolicy.resourceBytes());
        var relation = new ProcurementAnswerRelationGrader().grade(c, a, Files.readAllBytes(FIXTURE));
        ProcurementEvaluationReports.write(source, a); ProcurementEvaluationReports.write(old, baseline);
        byte[] sourceBytes = Files.readAllBytes(source), oldBytes = Files.readAllBytes(old);
        var result = grader.replay(c, source, FIXTURE, POLICY, out);
        assertEquals(baseline, result.baseEvaluation()); assertEquals(relation, result.relationEvaluation());
        Path again = directory.resolve("again.json"); assertEquals(result, grader.replay(c, source, FIXTURE, POLICY, again));
        assertArrayEquals(Files.readAllBytes(out), Files.readAllBytes(again));
        for (Path path : List.of(source, old, out, POLICY, FIXTURE)) assertThrows(FileAlreadyExistsException.class, () -> grader.replay(c, source, FIXTURE, POLICY, path));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source)); assertArrayEquals(oldBytes, Files.readAllBytes(old));
        ObjectNode raw = mapper.valueToTree(result); raw.put("completeAnswerStatus", "PASS");
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(raw, ProcurementAnswerCompletenessGrader.Evaluation.class));
    }

    @Test @EnabledIfSystemProperty(named = "completeness.artifact", matches = ".+")
    void actualHistoricalArtifactReplay() throws Exception {
        Path source = Path.of(System.getProperty("completeness.artifact")); byte[] original = Files.readAllBytes(source);
        var a = ProcurementEvaluationReports.readArtifact(source);
        var c = ProcurementEvaluationDataset.load().stream().filter(d -> d.caseId().equals(a.caseId())).findFirst().orElseThrow();
        var result = grader.replay(c, source, FIXTURE, POLICY, directory.resolve("historical.json"));
        assertNotEquals(Status.ERROR, result.evaluationStatus(), result.errors().toString());
        assertEquals(Status.SKIP, result.completeAnswerStatus()); assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void ambiguousRecommendationKeepsAllDependentElementsUnresolved() throws Exception {
        var result = grade(0, "推荐 Supplier D。推荐 Supplier B。");
        assertTrue(result.elements().stream().allMatch(e -> e.presence() == Presence.UNRESOLVED));
        assertTrue(result.elements().stream().allMatch(e -> !e.candidateFragmentRefs().isEmpty()));
    }

    @Test void wrongRecommendedSupplierStillOwnsExplicitPriceAndLeadTime() throws Exception {
        var result = grade(0, "推荐 Supplier B，总价 55 万元，交期 18 天。");
        for (String id : List.of("recommendation", "selected_total", "selected_lead_time"))
            assertEquals(Presence.PRESENT, element(result, id).presence());
        assertTrue(result.baseEvaluation().legacyEvaluation().claims().stream().anyMatch(r -> r.claim().property().equals("RECOMMENDATION")
                && r.factualCorrectness().status() == Status.FAIL));
    }

    @ParameterizedTest @ValueSource(strings = {"swap", "hideMissing", "policyDefinition", "datasetVersion"})
    void validButSemanticallyWrongReferencesCannotDeserialize(String mutation) throws Exception {
        ObjectNode raw = mapper.valueToTree(grade(0, DELIVERY));
        switch (mutation) {
            case "swap" -> {
                var total = (ObjectNode) raw.path("elements").get(1); var lead = (ObjectNode) raw.path("elements").get(2);
                var saved = total.path("matchedClaimRefs").deepCopy(); total.set("matchedClaimRefs", lead.path("matchedClaimRefs").deepCopy()); lead.set("matchedClaimRefs", saved);
            }
            case "hideMissing" -> {
                var total = (ObjectNode) raw.path("elements").get(1); total.put("presence", "MISSING");
                total.set("matchedClaimRefs", mapper.createArrayNode()); raw.put("completenessStatus", "FAIL");
            }
            case "policyDefinition" -> ((ObjectNode) raw.path("baseEvaluation").path("requiredAnswerElements").get(0)).put("requirement", "forged requirement");
            case "datasetVersion" -> ((ObjectNode) raw.path("relationEvaluation")).put("datasetVersion", "future");
        }
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(raw, ProcurementAnswerCompletenessGrader.Evaluation.class));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "wrongSpan", "unresolved", "scalar"})
    void standaloneEffectiveCoverageRejectsInvalidRelationReferences(String mutation) throws Exception {
        var result = grade(0, DELIVERY + "保证绝不延期。");
        ObjectNode raw = mapper.valueToTree(result.effectiveCoverage());
        ObjectNode target = null;
        for (var segment : raw.path("segments")) if (segment.path("kind").asText().equals("PARSED_RELATION")) { target = (ObjectNode) segment; break; }
        assertNotNull(target);
        int index = switch (mutation) {
            case "missing" -> 999;
            case "wrongSpan" -> result.relationEvaluation().results().size() - 2;
            default -> result.relationEvaluation().results().size() - 1;
        };
        target.set("claimRefs", mapper.valueToTree(List.of(mutation.equals("scalar") ? "scalar:claim-0" : "relation:" + index)));
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(raw, ProcurementAnswerEffectiveCoverage.Analysis.class));
    }

    private ProcurementAnswerCompletenessGrader.ElementResult element(ProcurementAnswerCompletenessGrader.Evaluation result, String id) {
        return result.elements().stream().filter(e -> e.elementId().equals(id)).findFirst().orElseThrow();
    }
    private ProcurementAnswerCompletenessGrader.Evaluation grade(int i, String answer) throws Exception {
        return grader.grade(definition(i), good(i, answer), Files.readAllBytes(FIXTURE), ProcurementAnswerPolicy.resourceBytes());
    }
    private EvaluationCase definition(int i) throws Exception { return ProcurementEvaluationDataset.load().get(i); }
    // Handcrafted valid search artifact helper follows; never executes production tools.
    private ExecutionArtifact good(int index, String answer) throws Exception {
        var c = definition(index);
        var state = mapper.treeToValue(c.expectedCase(), ProcurementCaseState.class);
        var current = new ProcurementCase("business-case", "tenant", "conversation", "buyer", ProcurementCaseStatus.values()[0], state, TIME, TIME, 1, "input");
        String fact = "Frozen handcrafted OFFER evidence", snapshot = "scenario:complex_workstation_01";
        List<SupplierEvidence> evidence = new ArrayList<>(); var offers = mapper.createArrayNode();
        for (var raw : mapper.readTree(Files.readAllBytes(FIXTURE)).path("offers")) {
            ObjectNode offer = (ObjectNode) raw.deepCopy(); offer.put("quantity", 50);
            offer.put("totalPrice", raw.path("unitPrice").decimalValue().multiply(new java.math.BigDecimal("50")));
            String supplier = raw.path("supplierId").asText(), recordId = "complex_workstation_01:" + raw.path("productId").asText();
            offer.put("sourceRecordId", recordId).put("sourceSnapshot", snapshot).put("sourceAsOf", TIME.toString()).put("sourceDigest", "digest");
            String id = EvidenceIdFactory.id(supplier, "OFFER", snapshot, recordId, snapshot, TIME.toString(), "digest", fact);
            evidence.add(new SupplierEvidence(id, supplier, "OFFER", snapshot, fact, TIME, recordId, snapshot, TIME, "digest")); offers.add(offer);
        }
        List<Map<String, String>> eligible = new ArrayList<>();
        for (var id : c.expected().path("eligibleSupplierIds")) eligible.add(Map.of("supplierId", id.asText()));
        var search = Map.of("caseVersion", 1, "eligibleSuppliers", eligible, "evidence", evidence, "offers", offers);
        var record = new ToolExecutionRecord("search", "run", ProcurementToolCatalog.SUPPLIER_SEARCH, ToolExecutionState.SUCCEEDED,
                new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH, "search", Map.of()),
                new ToolCallResult(ProcurementToolCatalog.SUPPLIER_SEARCH, true, mapper.writeValueAsString(search), "", Map.of()), 1, "", TIME, TIME);
        var runtime = new AgentRuntimeResult("run", "conversation", AgentRunState.COMPLETED, AgentStopReason.COMPLETED, answer, "", null, List.of());
        return ProcurementExecutionArtifacts.capture(c, c.userMessage(), runtime, current, List.of(record), List.of(),
                Map.of("executionMode", "HANDCRAFTED", "fixtureSha256", c.fixtureSha256()));
    }
    private ExecutionArtifact change(ExecutionArtifact a, Consumer<ObjectNode> edit) {
        ObjectNode node = mapper.valueToTree(a); edit.accept(node); return mapper.treeToValue(node, ExecutionArtifact.class);
    }
    private ExecutionArtifact payload(ExecutionArtifact a, Consumer<ObjectNode> edit) {
        return change(a, n -> {
            var result = (ObjectNode) n.path("toolExecutions").get(0).path("result");
            ObjectNode payload = (ObjectNode) mapper.readTree(result.path("content").asText()); edit.accept(payload);
            result.put("content", mapper.writeValueAsString(payload));
        });
    }
}
