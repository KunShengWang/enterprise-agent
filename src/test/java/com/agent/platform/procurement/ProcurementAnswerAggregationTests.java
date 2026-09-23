package com.agent.platform.procurement;

import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import java.nio.file.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static com.agent.platform.procurement.ProcurementAnswerEvaluationV3.*;
import static org.junit.jupiter.api.Assertions.*;

/** All PASS fixtures below are handcrafted, not real-model quality measurements. */
class ProcurementAnswerAggregationTests {
    private static final Path FIXTURE = Path.of("data/procurement/scenarios/complex_workstation_01.json");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<String> ANSWERS = List.of(
            "推荐 Supplier D，总价 58 万元，交期 12 天。D 比 B 快 6 天。D 的总价满足本次预算。",
            "推荐 Supplier B，总价 55 万元，交期 18 天。B 比 D 便宜 3 万元。B 的交期满足本次交付期限。",
            "推荐 Supplier D，总价 58 万元，交期 12 天。当前原始硬约束下仅 D 合格。",
            "当前原始硬约束下无合格供应商。");
    private final ObjectMapper json = new ObjectMapper();
    private final ProcurementCompleteAnswerEvaluator evaluator = new ProcurementCompleteAnswerEvaluator();

    @ParameterizedTest @ValueSource(ints = {0,1,2,3})
    void handcraftedCompleteAnswerPassesWithAuditableHandoffs(int index) throws Exception {
        var a = good(index, ANSWERS.get(index)); var r = grade(index, a);
        assertEquals(CompleteAnswerStatus.PASS, r.completeAnswerStatus(), r.findings().toString());
        assertEquals(ExecutionStatus.COMPLETE, r.assessmentExecutionStatus()); assertEquals(Stage.ASSESSED, r.assessmentStage());
        assertTrue(r.findings().isEmpty()); assertEquals(Status.SKIP, r.completenessEvaluation().completeAnswerStatus());
        assertTrue(r.assessmentCoverage().fragments().stream().anyMatch(f -> !f.handoffs().isEmpty()));
        for (var f : r.assessmentCoverage().fragments()) {
            assertEquals(f.text(), a.runtime().answer().substring(f.start(), f.end()));
            for (var h : f.handoffs()) {
                assertTrue(f.candidateClaimRefs().contains(h.unresolvedRef()));
                for (String ref : h.supportingCheckRefs()) {
                    var check = r.assessmentCoverage().checks().stream().filter(c -> c.ref().equals(ref)).findFirst().orElseThrow();
                    assertTrue(check.status() == Status.PASS || check.noDifference());
                }
            }
        }
        assertEquals(r, json.readValue(json.writeValueAsString(r), ProcurementAnswerEvaluationV3.class));
        assertEquals(ProcurementEvaluationReports.canonicalJson(json.valueToTree(r)),
                ProcurementEvaluationReports.canonicalJson(json.valueToTree(grade(index, a))));
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3})
    void everyPassLosesPassWhenEvidenceRemovedOrUnknownAdded(int index) throws Exception {
        var a = good(index, ANSWERS.get(index));
        assertEquals(CompleteAnswerStatus.PASS, grade(index, a).completeAnswerStatus());
        var missing = change(a, n -> n.set("toolExecutions", json.createArrayNode()));
        assertNotEquals(CompleteAnswerStatus.PASS, grade(index, missing).completeAnswerStatus());
        assertNotEquals(CompleteAnswerStatus.PASS, grade(index, answer(a, a.runtime().answer() + "保证绝不延期。" )).completeAnswerStatus());
        assertNotEquals(CompleteAnswerStatus.PASS, grade(index, answer(a, "")).completeAnswerStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"recommendation", "selected_total", "selected_lead_time", "delivery_advantage", "budget_compliance"})
    void deletingEachDeliveryRequiredElementRevokesPass(String id) throws Exception {
        String text = switch (id) {
            case "recommendation" -> ANSWERS.get(0).replace("推荐 Supplier D", "Supplier D");
            case "selected_total" -> ANSWERS.get(0).replace("总价 58 万元，", "");
            case "selected_lead_time" -> ANSWERS.get(0).replace("，交期 12 天", "");
            case "delivery_advantage" -> ANSWERS.get(0).replace("D 比 B 快 6 天。", "");
            default -> ANSWERS.get(0).replace("D 的总价满足本次预算。", "");
        };
        var r = grade(0, good(0, text));
        assertNotEquals(CompleteAnswerStatus.PASS, r.completeAnswerStatus());
        assertNotEquals(ProcurementAnswerCompletenessGrader.Presence.PRESENT,
                r.completenessEvaluation().elements().stream().filter(e -> e.elementId().equals(id)).findFirst().orElseThrow().presence());
        assertElementDiagnostic(r, id);
    }

    @ParameterizedTest @ValueSource(strings = {"amount", "lead", "direction", "difference", "subject", "extra"})
    void falseFactsOrRelationsCauseFailAndNeverHandoffTheirSkip(String variant) throws Exception {
        String text = switch (variant) {
            case "amount" -> ANSWERS.get(0).replace("58 万元", "57 万元");
            case "lead" -> ANSWERS.get(0).replace("12 天", "18 天");
            case "direction" -> ANSWERS.get(0).replace("快 6 天", "慢 6 天");
            case "difference" -> ANSWERS.get(0).replace("快 6 天", "快 5 天");
            case "subject" -> ANSWERS.get(0).replace("D 比 B", "B 比 D");
            default -> ANSWERS.get(0) + "Supplier B 的总价 1 元。";
        };
        var r = grade(0, good(0, text));
        assertEquals(CompleteAnswerStatus.FAIL, r.completeAnswerStatus(), r.findings().toString());
        for (var f : r.assessmentCoverage().fragments()) if (f.blockers().stream().anyMatch(ref -> r.assessmentCoverage().checks().stream()
                .anyMatch(c -> c.ref().equals(ref) && c.status() == Status.FAIL))) assertTrue(f.supersededUnresolvedRefs().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"1:recommendation", "1:selected_total", "1:selected_lead_time", "1:price_advantage", "1:delivery_compliance",
            "2:recommendation", "2:selected_total", "2:selected_lead_time", "2:unique_eligible", "3:no_eligible"})
    void everyOtherPolicyElementHasADeletionMutation(String scenario) throws Exception {
        int index = Integer.parseInt(scenario.substring(0, 1)); String id = scenario.substring(2), text = ANSWERS.get(index);
        text = switch (id) {
            case "recommendation" -> text.replace("推荐 ", "");
            case "selected_total" -> text.replace(index == 1 ? "总价 55 万元，" : "总价 58 万元，", "");
            case "selected_lead_time" -> text.replace(index == 1 ? "，交期 18 天" : "，交期 12 天", "");
            case "price_advantage" -> text.replace("B 比 D 便宜 3 万元。", "");
            case "delivery_compliance" -> text.replace("B 的交期满足本次交付期限。", "");
            case "unique_eligible" -> text.replace("当前原始硬约束下仅 D 合格。", "");
            default -> "";
        };
        var r = grade(index, good(index, text));
        assertNotEquals(CompleteAnswerStatus.PASS, r.completeAnswerStatus());
        assertNotEquals(ProcurementAnswerCompletenessGrader.Presence.PRESENT,
                r.completenessEvaluation().elements().stream().filter(e -> e.elementId().equals(id)).findFirst().orElseThrow().presence());
        assertElementDiagnostic(r, id);
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3})
    void everyPassRejectsAddedFalseFact(int index) throws Exception {
        var r = grade(index, good(index, ANSWERS.get(index) + "Supplier D 的总价 1 元。"));
        assertEquals(CompleteAnswerStatus.FAIL, r.completeAnswerStatus());
    }

    @Test void independentStatementsDoNotSubstituteForComparison() throws Exception {
        var text = ANSWERS.get(0).replace("D 比 B 快 6 天。", "Supplier B 的交期 18 天。");
        var r = grade(0, good(0, text));
        assertEquals(CompleteAnswerStatus.FAIL, r.completeAnswerStatus());
        assertTrue(r.findings().stream().anyMatch(f -> f.sourceRefs().contains("element:delivery_advantage")));
    }

    @Test void goodRelationCannotDischargeAnotherFragmentsWrongRelation() throws Exception {
        var r = grade(0, good(0, ANSWERS.get(0) + "D 比 B 快 5 天。"));
        assertEquals(CompleteAnswerStatus.FAIL, r.completeAnswerStatus());
        var good = r.assessmentCoverage().fragments().stream().filter(f -> f.text().equals("D 比 B 快 6 天")).findFirst().orElseThrow();
        var bad = r.assessmentCoverage().fragments().stream().filter(f -> f.text().equals("D 比 B 快 5 天")).findFirst().orElseThrow();
        assertFalse(good.supersededUnresolvedRefs().isEmpty()); assertTrue(bad.supersededUnresolvedRefs().isEmpty());
        assertTrue(Collections.disjoint(good.supersededUnresolvedRefs(), bad.candidateClaimRefs()));
    }

    @Test void toolArrayReorderChangesArtifactIdentityAndRebindsEvidencePaths() throws Exception {
        var a = good(0, ANSWERS.get(0)); var first = grade(0, a);
        var reordered = change(a, n -> { var old = (ArrayNode)n.path("toolExecutions"); var reversed = json.createArrayNode();
            for (int i = old.size() - 1; i >= 0; i--) reversed.add(old.get(i)); n.set("toolExecutions", reversed); });
        var second = grade(0, reordered);
        assertEquals(CompleteAnswerStatus.PASS, second.completeAnswerStatus());
        assertNotEquals(first.inputBinding().artifactSha256(), second.inputBinding().artifactSha256());
        assertEquals(first.assessmentCoverage().fragments(), second.assessmentCoverage().fragments());
        assertNotEquals(first.assessmentCoverage().checks(), second.assessmentCoverage().checks());
        assertEquals(second, grade(0, reordered));
    }

    @Test void oldStep1VersionCannotLabelAnAggregatedResult() throws Exception {
        ObjectNode n = json.valueToTree(grade(0, good(0, ANSWERS.get(0))));
        n.put("evaluatorVersion", "procurement-complete-answer-step1-v1");
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class));
    }

    @Test void eligibleGrammarDoesNotWaiveRealConditionScope() throws Exception {
        var r = grade(2, good(2, ANSWERS.get(2) + "只有批准才执行。"));
        assertNotEquals(CompleteAnswerStatus.PASS, r.completeAnswerStatus());
        assertTrue(r.assessmentCoverage().fragments().stream().allMatch(f -> f.supersededUnresolvedRefs().isEmpty()));
    }

    @ParameterizedTest @ValueSource(strings = {"保证绝不延期。", "星河璀璨。", "RFQ 已创建。", "本阶段仅完成只读推荐。", "待审批。", "执行失败。",
            "如果批准，D 比 B 快 6 天。", "D 比 B 快 6 天？", "“D 比 B 快 6 天”。"})
    void extraUnverifiedClaimsNeverDisappear(String suffix) throws Exception {
        var r = grade(0, good(0, ANSWERS.get(0) + suffix));
        assertNotEquals(CompleteAnswerStatus.PASS, r.completeAnswerStatus());
        assertTrue(r.findings().stream().anyMatch(f -> f.kind() == FindingKind.NEEDS_REVIEW));
        assertTrue(r.assessmentCoverage().fragments().stream().anyMatch(f -> !f.blockers().isEmpty()));
    }

    @Test void politenessUsesExactNonFactualRuleNotFactualHandoff() throws Exception {
        var r = grade(0, good(0, ANSWERS.get(0) + "谢谢。"));
        assertEquals(CompleteAnswerStatus.PASS, r.completeAnswerStatus(), r.findings().toString());
        var f = r.assessmentCoverage().fragments().stream().filter(x -> x.text().equals("谢谢")).findFirst().orElseThrow();
        assertEquals(ScoringPath.NON_FACTUAL, f.scoringPath()); assertFalse(f.nonFactualClaimRefs().isEmpty());
        assertTrue(f.supersededUnresolvedRefs().isEmpty());
        assertNotEquals(CompleteAnswerStatus.PASS, grade(0, good(0, ANSWERS.get(0) + "谢谢，保证绝不延期。")).completeAnswerStatus());
    }

    @Test void missingOneOperandBlocksRelationHandoff() throws Exception {
        var a = payload(good(0, ANSWERS.get(0)), 0, n -> {
            ArrayNode offers = (ArrayNode)n.path("offers");
            for (int i = offers.size() - 1; i >= 0; i--) if (offers.get(i).path("supplierId").asText().equals("supplier-b")) offers.remove(i);
        });
        var r = grade(0, a); assertEquals(CompleteAnswerStatus.NEEDS_REVIEW, r.completeAnswerStatus(), r.findings().toString());
        var f = r.assessmentCoverage().fragments().stream().filter(x -> x.text().equals("D 比 B 快 6 天")).findFirst().orElseThrow();
        assertTrue(f.supersededUnresolvedRefs().isEmpty());
        assertFalse(f.blockers().isEmpty());
        assertTrue(r.findings().stream().anyMatch(x -> x.sourceRefs().contains("fragment:" + f.originalFragmentIndex())));
        assertTrue(r.completenessEvaluation().relationEvaluation().results().stream().anyMatch(x -> x.evidenceDirection().status() == Status.SKIP));
    }

    @Test void independentErrorAndFactualFailRemainTogether() throws Exception {
        var a = payload(good(0, ANSWERS.get(0).replace("58 万元", "57 万元")), 0, n -> n.put("offers", 42));
        var r = grade(0, a); assertEquals(CompleteAnswerStatus.FAIL, r.completeAnswerStatus(), r.findings().toString());
        assertEquals(ExecutionStatus.ERROR, r.assessmentExecutionStatus());
        assertTrue(r.findings().stream().anyMatch(f -> f.kind() == FindingKind.FAIL));
        assertTrue(r.findings().stream().anyMatch(f -> f.kind() == FindingKind.ERROR));
        var untrusted = grade(0, change(a, n -> n.put("caseId", "other")));
        assertEquals(CompleteAnswerStatus.ERROR, untrusted.completeAnswerStatus());
        assertEquals(Stage.BLOCKED, untrusted.assessmentStage());
        assertNull(untrusted.completenessEvaluation());
        assertTrue(untrusted.findings().stream().allMatch(f -> f.origin() == Origin.FOUNDATION && f.kind() == FindingKind.ERROR));
        var onlyError = grade(0, payload(good(0, ANSWERS.get(0)), 0, n -> n.put("offers", 42)));
        assertEquals(CompleteAnswerStatus.NEEDS_REVIEW, onlyError.completeAnswerStatus());
        assertEquals(ExecutionStatus.ERROR, onlyError.assessmentExecutionStatus());
    }

    @Test void foundationErrorOverridesApparentFalseAnswer() throws Exception {
        var a = change(good(0, ANSWERS.get(0).replace("58 万元", "57 万元")), n -> n.put("caseId", "other"));
        var r = grade(0, a); assertEquals(CompleteAnswerStatus.ERROR, r.completeAnswerStatus()); assertNull(r.completenessEvaluation());
    }

    @ParameterizedTest @ValueSource(strings = {"sameDifferenceWrongOperands", "twoSearches", "twoFinalizes", "missingEligible", "failedSearch"})
    void contradictoryOrUnavailableEvidenceCannotProducePass(String mutation) throws Exception {
        var a = good(0, ANSWERS.get(0));
        switch (mutation) {
            case "sameDifferenceWrongOperands" -> a = payload(a, 0, n -> {
                for (var o : n.path("offers")) if (Set.of("supplier-b", "supplier-d").contains(o.path("supplierId").asText()))
                    ((ObjectNode)o).put("leadTimeDays", o.path("leadTimeDays").asInt() + 1);
            });
            case "twoSearches", "twoFinalizes" -> {
                int index = mutation.equals("twoSearches") ? 0 : 1;
                a = change(a, n -> { ObjectNode duplicate = (ObjectNode)n.path("toolExecutions").get(index).deepCopy(); duplicate.put("toolCallId", "duplicate");
                    ((ObjectNode)duplicate.path("request")).put("requestId", "duplicate"); ((ArrayNode)n.path("toolExecutions")).add(duplicate); });
            }
            case "missingEligible" -> a = payload(a, 0, n -> n.remove("eligibleSuppliers"));
            default -> a = change(a, n -> ((ArrayNode)n.path("toolExecutions")).remove(0));
        }
        assertNotEquals(CompleteAnswerStatus.PASS, grade(0, a).completeAnswerStatus());
    }

    @Test void noEligibleRequiresExplicitEmptySuccessfulSearch() throws Exception {
        var a = good(3, ANSWERS.get(3)); assertEquals(CompleteAnswerStatus.PASS, grade(3, a).completeAnswerStatus());
        for (String field : List.of("eligibleSuppliers", "offers", "evidence"))
            assertNotEquals(CompleteAnswerStatus.PASS, grade(3, payload(a, 0, n -> n.remove(field))).completeAnswerStatus());
    }

    @Test void stableSerializationAndObjectKeyOrderDoNotChangeEvaluation() throws Exception {
        var a = good(0, ANSWERS.get(0));
        var original = grade(0, a);
        var reordered = json.treeToValue(reverseObjectKeys(json.valueToTree(a)), ExecutionArtifact.class);
        assertEquals(original, grade(0, reordered));
        assertEquals(ProcurementEvaluationReports.canonicalJson(json.valueToTree(original)),
                ProcurementEvaluationReports.canonicalJson(json.valueToTree(grade(0, reordered))));
    }

    @ParameterizedTest @ValueSource(strings = {"handoffOtherFragment", "handoffWithoutChecks", "missingBlocker", "changeRun", "skipToPass", "wrongSpan"})
    void forgedAssessmentCannotDeserialize(String mutation) throws Exception {
        var result = grade(0, good(0, ANSWERS.get(0) + "未知承诺。")); ObjectNode n = json.valueToTree(result);
        ObjectNode fragment = null;
        for (var f : n.path("assessmentCoverage").path("fragments")) if (!f.path("handoffs").isEmpty()) { fragment = (ObjectNode)f; break; }
        assertNotNull(fragment);
        switch (mutation) {
            case "handoffOtherFragment" -> ((ObjectNode)fragment.path("handoffs").get(0)).put("unresolvedRef", "relation:999");
            case "handoffWithoutChecks" -> ((ObjectNode)fragment.path("handoffs").get(0)).set("supportingCheckRefs", json.createArrayNode());
            case "missingBlocker" -> n.set("findings", json.createArrayNode());
            case "changeRun" -> ((ObjectNode)n.path("inputBinding")).put("runId", "other");
            case "skipToPass" -> n.put("completeAnswerStatus", "PASS");
            default -> fragment.put("start", 999);
        }
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class));
    }

    @Test @EnabledIfSystemProperty(named = "answer.v3.artifact", matches = ".+")
    void actualSavedScriptedRuntimeAnswerIsReportedHonestly() throws Exception {
        Path path = Path.of(System.getProperty("answer.v3.artifact")); byte[] before = Files.readAllBytes(path);
        var a = ProcurementEvaluationReports.readArtifact(path);
        var c = ProcurementEvaluationDataset.load().stream().filter(x -> x.caseId().equals(a.caseId())).findFirst().orElseThrow();
        var r = evaluator.evaluate(c, a, Files.readAllBytes(FIXTURE), ProcurementAnswerPolicy.resourceBytes());
        assertEquals(CompleteAnswerStatus.FAIL, r.completeAnswerStatus(), r.findings().toString());
        assertTrue(r.findings().stream().anyMatch(f -> f.sourceRefs().contains("element:delivery_advantage")));
        assertTrue(r.assessmentCoverage().fragments().stream().anyMatch(f -> f.scoringPath() == ScoringPath.EXECUTION_STATE));
        assertArrayEquals(before, Files.readAllBytes(path));
        System.out.println("SAVED_SCRIPTED_RUNTIME " + c.caseId() + " => " + r.completeAnswerStatus() + "; " + r.findings());
    }

    private JsonNode reverseObjectKeys(JsonNode n) {
        if (n.isObject()) { ObjectNode out = json.createObjectNode(); var keys = new ArrayList<>(n.properties().stream().map(Map.Entry::getKey).toList());
            keys.sort(Comparator.reverseOrder()); keys.forEach(k -> out.set(k, reverseObjectKeys(n.path(k)))); return out; }
        if (n.isArray()) { ArrayNode out = json.createArrayNode(); n.forEach(v -> out.add(reverseObjectKeys(v))); return out; } return n;
    }
    @Test void sameSpanDifferentRelationMeaningIsRejected() throws Exception {
        ObjectNode n = json.valueToTree(grade(0, good(0, ANSWERS.get(0))));
        for (var item : n.path("completenessEvaluation").path("relationEvaluation").path("results")) {
            ObjectNode claim = (ObjectNode)item.path("claim");
            if (claim.path("scope").asText().equals("PAIRWISE")) claim.put("difference", 5);
        }
        // Text, UTF-16 span, artifact binding and copied PASS scores are deliberately unchanged.
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class));
    }

    @Test void unseparatedExtraPromiseCannotBeConsumedAsCorrectRelationPrefix() throws Exception {
        var r = grade(0, good(0, ANSWERS.get(0).replace("D 比 B 快 6 天。", "D 比 B 快 6 天且保证准时到货。")));
        assertNotEquals(CompleteAnswerStatus.PASS, r.completeAnswerStatus());
        var fragment = r.assessmentCoverage().fragments().stream().filter(f -> f.text().contains("保证")).findFirst().orElseThrow();
        assertTrue(fragment.handoffs().isEmpty()); assertFalse(fragment.blockers().isEmpty());
        assertTrue(r.findings().stream().anyMatch(f -> f.sourceRefs().contains("fragment:" + fragment.originalFragmentIndex())));
    }

    @Test void externalPassLabelsCannotOverrideRawArtifactScoring() throws Exception {
        var raw = good(0, ANSWERS.get(0).replace("58 万元", "57 万元"));
        var expected = grade(0, raw);
        var injected = change(raw, n -> {
            ObjectNode metadata = (ObjectNode)n.path("metadata");
            for (String key : List.of("v1Sidecar", "v2Sidecar", "relationSidecar"))
                metadata.put(key, "{\"completeAnswerStatus\":\"PASS\",\"factualCorrectness\":\"PASS\",\"faithfulness\":\"PASS\"}");
            metadata.put("structuredStatus", "PASS");
        });
        String before = json.writeValueAsString(injected);
        var actual = grade(0, injected);
        assertEquals(CompleteAnswerStatus.FAIL, actual.completeAnswerStatus());
        assertEquals(expected.assessmentCoverage(), actual.assessmentCoverage());
        assertEquals(expected.findings(), actual.findings());
        assertEquals(before, json.writeValueAsString(injected));
        assertEquals(Status.SKIP, actual.completenessEvaluation().completeAnswerStatus());
        var entrypoints = Arrays.stream(ProcurementCompleteAnswerEvaluator.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers())).toList();
        assertEquals(1, entrypoints.size());
        assertArrayEquals(new Class<?>[]{EvaluationCase.class, ExecutionArtifact.class, byte[].class, byte[].class}, entrypoints.get(0).getParameterTypes());
    }

    private void assertElementDiagnostic(ProcurementAnswerEvaluationV3 r, String id) {
        String ref = "element:" + id;
        var check = r.assessmentCoverage().checks().stream().filter(c -> c.ref().equals(ref)).findFirst().orElseThrow();
        assertTrue(check.status() == Status.FAIL || check.status() == Status.SKIP);
        assertTrue(r.findings().stream().anyMatch(f -> f.sourceRefs().contains(ref)));
        assertTrue(r.assessmentCoverage().fragments().stream().noneMatch(f -> f.matchedElementRefs().contains(ref)));
    }
    private ProcurementAnswerEvaluationV3 grade(int i, ExecutionArtifact a) throws Exception { return evaluator.evaluate(definition(i), a, Files.readAllBytes(FIXTURE), ProcurementAnswerPolicy.resourceBytes()); }
    private EvaluationCase definition(int i) throws Exception { return ProcurementEvaluationDataset.load().get(i); }
    private ExecutionArtifact answer(ExecutionArtifact a, String text) { return change(a, n -> ((ObjectNode)n.path("runtime")).put("answer", text)); }
    private ExecutionArtifact change(ExecutionArtifact a, Consumer<ObjectNode> edit) { ObjectNode n = json.valueToTree(a); edit.accept(n); return json.treeToValue(n, ExecutionArtifact.class); }
    private ExecutionArtifact payload(ExecutionArtifact a, int i, Consumer<ObjectNode> edit) {
        return change(a, n -> { var r = (ObjectNode)n.path("toolExecutions").get(i).path("result");
            ObjectNode p = (ObjectNode)json.readTree(r.path("content").asText()); edit.accept(p); r.put("content", json.writeValueAsString(p)); });
    }
    private ExecutionArtifact good(int i, String answer) throws Exception {
        var c = definition(i); var state = json.treeToValue(c.expectedCase(), ProcurementCaseState.class);
        var business = new ProcurementCase("business-case", "tenant", "conversation", "buyer", ProcurementCaseStatus.values()[0], state, TIME, TIME, 1, "input");
        List<SupplierEvidence> evidence = new ArrayList<>(); var offers = json.createArrayNode();
        String preferred = c.expected().path("preferredSupplierId").asText(), selectedRef = "";
        JsonNode selected = null; String snapshot = "scenario:complex_workstation_01", fact = "Frozen handcrafted OFFER evidence";
        for (var raw : json.readTree(Files.readAllBytes(FIXTURE)).path("offers")) {
            ObjectNode offer = (ObjectNode)raw.deepCopy(); BigDecimal quantity = c.expectedCase().path("quantity").decimalValue();
            offer.put("quantity", quantity); offer.put("totalPrice", raw.path("unitPrice").decimalValue().multiply(quantity));
            String supplier = raw.path("supplierId").asText(), recordId = "complex_workstation_01:" + raw.path("productId").asText();
            offer.put("sourceRecordId", recordId).put("sourceSnapshot", snapshot).put("sourceAsOf", TIME.toString()).put("sourceDigest", "digest");
            String id = EvidenceIdFactory.id(supplier, "OFFER", snapshot, recordId, snapshot, TIME.toString(), "digest", fact);
            evidence.add(new SupplierEvidence(id, supplier, "OFFER", snapshot, fact, TIME, recordId, snapshot, TIME, "digest"));
            if (supplier.equals(preferred)) { selected = offer; selectedRef = id; } offers.add(offer);
        }
        List<Map<String, String>> eligible = new ArrayList<>(), alternatives = new ArrayList<>();
        c.expected().path("eligibleSupplierIds").forEach(id -> { var entry = Map.of("supplierId", id.asText()); eligible.add(entry); if (!id.asText().equals(preferred)) alternatives.add(entry); });
        var search = Map.of("caseVersion", 1, "offers", offers, "evidence", evidence, "eligibleSuppliers", eligible);
        List<ToolExecutionRecord> records = new ArrayList<>(); records.add(record("search", ProcurementToolCatalog.SUPPLIER_SEARCH, search));
        if (selected != null) {
            var recommendation = Map.of("recommendedSupplier", Map.of("supplierId", preferred), "selectedOffer", selected,
                    "eligibleAlternatives", alternatives, "evidenceRefs", List.of(selectedRef), "tradeoffDimensions", c.expected().path("requiredTradeoffDimensions"));
            records.add(record("finalize", ProcurementToolCatalog.RECOMMENDATION_FINALIZE,
                    Map.of("caseId", "business-case", "caseVersion", 1, "recommendation", recommendation, "evidence", evidence)));
        }
        var runtime = new AgentRuntimeResult("run", "conversation", AgentRunState.COMPLETED, AgentStopReason.COMPLETED, answer, "", null, List.of());
        return ProcurementExecutionArtifacts.capture(c, c.userMessage(), runtime, business, records, List.of(), Map.of("executionMode", "HANDCRAFTED", "fixtureSha256", c.fixtureSha256()));
    }
    private ToolExecutionRecord record(String id, String tool, Object payload) {
        return new ToolExecutionRecord(id, "run", tool, ToolExecutionState.SUCCEEDED, new ToolCallRequest(tool, id, Map.of()),
                new ToolCallResult(tool, true, json.writeValueAsString(payload), "", Map.of()), 1, "", TIME, TIME);
    }
}
