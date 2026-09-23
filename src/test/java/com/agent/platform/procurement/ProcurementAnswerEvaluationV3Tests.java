package com.agent.platform.procurement;

import com.agent.platform.procurement.model.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static com.agent.platform.procurement.ProcurementAnswerEvaluationV3.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementAnswerEvaluationV3Tests {
    private final ObjectMapper json = new ObjectMapper();
    private final ProcurementCompleteAnswerEvaluator evaluator = new ProcurementCompleteAnswerEvaluator();
    private static final Path FIXTURE = Path.of("data/procurement/scenarios/complex_workstation_01.json");
    private static final String ANSWER = "推荐 Supplier D，总价 58 万元，交期 12 天。D 比 B 快 6 天。D 的总价满足本次预算。";

    @Test void rawInputProducesPendingContractAndStableJsonWithoutWholeAnswerVerdict() throws Exception {
        var c = definition(); var a = artifact(ANSWER);
        var old = new ProcurementAnswerCompletenessGrader().grade(c, a, fixture(), policy());
        String artifactBefore = json.writeValueAsString(a), oldBefore = json.writeValueAsString(old);
        var result = evaluator.evaluate(c, a, fixture(), policy());
        assertEquals(Stage.NOT_IMPLEMENTED, result.assessmentStage(), result.findings().toString());
        assertNull(result.completeAnswerStatus()); assertEquals(ExecutionStatus.NOT_IMPLEMENTED, result.assessmentExecutionStatus());
        assertEquals(old, result.completenessEvaluation());
        assertEquals(result, json.readValue(json.writeValueAsString(result), ProcurementAnswerEvaluationV3.class));
        assertEquals(ProcurementEvaluationReports.canonicalJson(json.valueToTree(result)),
                ProcurementEvaluationReports.canonicalJson(json.valueToTree(evaluator.evaluate(c, a, fixture(), policy()))));
        assertEquals(artifactBefore, json.writeValueAsString(a)); assertEquals(oldBefore, json.writeValueAsString(old));
        assertTrue(result.assessmentCoverage().fragments().stream().allMatch(f -> f.supersededUnresolvedRefs().isEmpty()
                && f.scoringPath() == ScoringPath.PENDING && !f.blockers().isEmpty()));
        assertTrue(result.findings().stream().anyMatch(f -> f.kind() == FindingKind.NEEDS_REVIEW));
    }

    @ParameterizedTest @ValueSource(strings = {"schemaVersion", "evaluatorVersion", "assessmentCoverageVersion", "inputBinding",
            "completenessEvaluation", "prerequisiteChecks", "assessmentCoverage", "assessmentStage", "completeAnswerStatus",
            "assessmentExecutionStatus", "findings", "scope"})
    void everyTopLevelFieldIsRequired(String field) throws Exception {
        ObjectNode n = json.valueToTree(evaluate(ANSWER)); n.remove(field);
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class), field);
    }

    @ParameterizedTest @ValueSource(strings = {"caseId", "runId", "sessionId", "businessCaseId", "businessVersion", "datasetVersion",
            "datasetSha256", "fixtureSha256", "artifactSha256", "answerSha256", "policyVersion", "policySha256", "componentVersions"})
    void everyBindingFieldIsRequired(String field) throws Exception {
        ObjectNode n = json.valueToTree(evaluate(ANSWER)); ((ObjectNode)n.path("inputBinding")).remove(field);
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class), field);
    }

    @ParameterizedTest @ValueSource(strings = {"schema", "version", "coverageVersion", "enum", "stage", "execution", "pass", "review",
            "scope", "identity", "component", "legacyVersion", "deleteFindings", "handoff", "fragment", "prerequisite"})
    void forgedContractCannotUpgradeOrSuppressResults(String mutation) throws Exception {
        ObjectNode n = json.valueToTree(evaluate(ANSWER));
        switch (mutation) {
            case "schema" -> n.put("schemaVersion", "future");
            case "version" -> n.put("evaluatorVersion", "future");
            case "coverageVersion" -> n.put("assessmentCoverageVersion", "future");
            case "enum" -> n.put("completeAnswerStatus", "UNKNOWN");
            case "stage" -> n.put("assessmentStage", "COMPLETE");
            case "execution" -> n.put("assessmentExecutionStatus", "ERROR");
            case "pass" -> n.put("completeAnswerStatus", "PASS");
            case "review" -> n.put("completeAnswerStatus", "NEEDS_REVIEW");
            case "scope" -> n.put("scope", "all business operations verified");
            case "identity" -> ((ObjectNode)n.path("inputBinding")).put("runId", "another");
            case "component" -> ((ObjectNode)n.path("inputBinding").path("componentVersions")).put("matcher", "future");
            case "legacyVersion" -> ((ObjectNode)n.path("completenessEvaluation").path("baseEvaluation").path("legacyEvaluation")).put("graderVersion", "future");
            case "deleteFindings" -> n.set("findings", json.createArrayNode());
            case "handoff" -> ((ObjectNode)n.path("assessmentCoverage").path("fragments").get(0)).set("supersededUnresolvedRefs", json.valueToTree(List.of("scalar:claim-0")));
            case "fragment" -> ((ObjectNode)n.path("assessmentCoverage").path("fragments").get(0)).put("start", 1);
            case "prerequisite" -> n.set("prerequisiteChecks", json.createArrayNode());
        }
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class), mutation);
    }

    @ParameterizedTest @ValueSource(strings = {"case", "dataset", "fixture", "policy", "policyVersion", "run", "session", "business",
            "businessVersion", "quantity", "payloadCase", "payloadVersion", "duplicatePayloadKey", "nullArtifact"})
    void rawFoundationFailuresStopScoring(String mutation) throws Exception {
        var c = definition(); var a = artifact(ANSWER); byte[] fixture = fixture(), policy = policy();
        switch (mutation) {
            case "case" -> a = change(a, n -> n.put("caseId", "other"));
            case "dataset" -> { ObjectNode n = json.valueToTree(c); n.put("datasetSha256", "0".repeat(64)); c = json.treeToValue(n, EvaluationCase.class); }
            case "fixture" -> fixture = new byte[0];
            case "policy" -> policy = (new String(policy, java.nio.charset.StandardCharsets.UTF_8) + " ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case "policyVersion" -> { ObjectNode n = (ObjectNode)json.readTree(policy); n.put("policyVersion", "future"); policy = json.writeValueAsBytes(n); }
            case "run" -> a = change(withSearch(a, "{}"), n -> ((ObjectNode)n.path("toolExecutions").get(0)).put("runId", "other"));
            case "session" -> a = change(a, n -> ((ObjectNode)n.path("runtime")).put("sessionId", "other"));
            case "business" -> a = change(a, n -> ((ObjectNode)n.path("metadata")).put("businessCaseId", "other"));
            case "businessVersion" -> a = change(a, n -> ((ObjectNode)n.path("finalCase")).put("version", 0));
            case "quantity" -> a = change(a, n -> ((ObjectNode)n.path("finalCase").path("state")).put("quantity", 51));
            case "payloadCase" -> a = withSearch(a, "{\"caseId\":\"other\",\"caseVersion\":1}");
            case "payloadVersion" -> a = withSearch(a, "{\"caseVersion\":2}");
            case "duplicatePayloadKey" -> a = withSearch(a, "{\"caseVersion\":2,\"caseVersion\":1}");
            case "nullArtifact" -> a = null;
        }
        var r = evaluator.evaluate(c, a, fixture, policy);
        assertEquals(CompleteAnswerStatus.ERROR, r.completeAnswerStatus()); assertEquals(Stage.BLOCKED, r.assessmentStage());
        assertNull(r.inputBinding()); assertNull(r.completenessEvaluation());
        assertEquals(Origin.FOUNDATION, r.findings().get(0).origin());
        assertEquals(r, json.readValue(json.writeValueAsString(r), ProcurementAnswerEvaluationV3.class));
    }

    @Test void scoringErrorIsDistinctAndRetainsIndependentFactualFailure() throws Exception {
        var a = withSearch(artifact(ANSWER.replace("58 万元", "57 万元")), "{\"caseVersion\":1,\"offers\":42,\"eligibleSuppliers\":[]}");
        var r = evaluator.evaluate(definition(), a, fixture(), policy());
        assertNotNull(r.inputBinding()); assertNotNull(r.completenessEvaluation());
        assertTrue(r.prerequisiteChecks().stream().allMatch(p -> p.status() == Status.PASS));
        assertTrue(r.findings().stream().anyMatch(f -> f.kind() == FindingKind.FAIL));
        assertTrue(r.findings().stream().anyMatch(f -> f.kind() == FindingKind.ERROR && f.origin() == Origin.SCORING));
        assertEquals(ExecutionStatus.ERROR, r.assessmentExecutionStatus());
    }

    @Test void existingRuntimeNullNormalizationIsTreatedAsEmptyNotInventedCaptureFailure() throws Exception {
        var a = change(artifact(ANSWER), n -> ((ObjectNode)n.path("runtime")).putNull("answer"));
        assertEquals("", a.runtime().answer());
        var r = evaluator.evaluate(definition(), a, fixture(), policy());
        assertNull(r.completeAnswerStatus());
        assertTrue(r.completenessEvaluation().baseEvaluation().coverage().emptyAnswer());
        assertTrue(r.findings().stream().anyMatch(f -> f.kind() == FindingKind.FAIL));
    }

    @Test void missingAndUnresolvedElementsAreRetainedWithoutPrematureAggregation() throws Exception {
        var missing = evaluate("推荐 Supplier D。");
        assertTrue(missing.findings().stream().anyMatch(f -> f.kind() == FindingKind.FAIL && f.sourceRefs().contains("element:selected_total")));
        var unresolved = evaluate("推荐 Supplier D。总价五十八万元。");
        assertTrue(unresolved.findings().stream().anyMatch(f -> f.kind() == FindingKind.NEEDS_REVIEW && f.sourceRefs().contains("element:selected_total")));
        assertNull(missing.completeAnswerStatus()); assertNull(unresolved.completeAnswerStatus());
    }

    @Test void isolatedDecisionTableUsesTrustedApplicabilityAndPreservesErrors() {
        var d = ProcurementCompleteAnswerDecision.decide(false, true, List.of(Status.FAIL));
        assertEquals(CompleteAnswerStatus.ERROR, d.status()); assertTrue(d.executionError());
        d = ProcurementCompleteAnswerDecision.decide(true, true, List.of(Status.FAIL, Status.ERROR, Status.PASS));
        assertEquals(CompleteAnswerStatus.FAIL, d.status()); assertTrue(d.executionError());
        assertEquals(CompleteAnswerStatus.ERROR, ProcurementCompleteAnswerDecision.decide(true, true, List.of(Status.ERROR)).status());
        assertEquals(CompleteAnswerStatus.FAIL, ProcurementCompleteAnswerDecision.decide(true, true, List.of(Status.FAIL)).status());
        assertEquals(CompleteAnswerStatus.NEEDS_REVIEW, ProcurementCompleteAnswerDecision.decide(true, true, List.of(Status.PASS, Status.SKIP)).status());
        assertEquals(CompleteAnswerStatus.NEEDS_REVIEW, ProcurementCompleteAnswerDecision.decide(true, false, List.of(Status.PASS)).status());
        assertEquals(CompleteAnswerStatus.ERROR, ProcurementCompleteAnswerDecision.decide(true, true, List.of(Status.NOT_APPLICABLE)).status());
        assertEquals(CompleteAnswerStatus.NEEDS_REVIEW, ProcurementCompleteAnswerDecision.decide(true, true, List.of(Status.NOT_APPLICABLE), Set.of(0)).status());
        assertEquals(CompleteAnswerStatus.NEEDS_REVIEW, ProcurementCompleteAnswerDecision.decide(true, true, List.of()).status());
        assertEquals(CompleteAnswerStatus.ERROR, ProcurementCompleteAnswerDecision.decide(true, true, List.of(Status.PASS, Status.NOT_APPLICABLE)).status());
        assertEquals(CompleteAnswerStatus.PASS, ProcurementCompleteAnswerDecision.decide(true, true, List.of(Status.PASS, Status.NOT_APPLICABLE), Set.of(1)).status());
        assertThrows(NullPointerException.class, () -> ProcurementCompleteAnswerDecision.decide(true, true, Arrays.asList(Status.PASS, null)));
    }

    @Test void noPublicIntermediateCompositionOrReplayEntryPoint() {
        assertEquals(List.of("evaluate"), Arrays.stream(ProcurementCompleteAnswerEvaluator.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName).toList());
    }

    @ParameterizedTest @ValueSource(strings = {"caseId", "datasetVersion", "datasetSha256", "fixtureSha256", "artifactSha256", "answerSha256", "policySha256"})
    void topBindingCannotDifferFromNestedBinding(String field) throws Exception {
        ObjectNode n = json.valueToTree(evaluate(ANSWER));
        ((ObjectNode)n.path("inputBinding")).put(field, field.endsWith("Sha256") ? "0".repeat(64) : "other");
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class));
    }

    @Test void forgedExpectedFactWithUnchangedHashLabelIsRejected() throws Exception {
        ObjectNode c = json.valueToTree(definition()); ((ObjectNode)c.path("expectedCase")).put("budget", 999999);
        var r = evaluator.evaluate(json.treeToValue(c, EvaluationCase.class), artifact(ANSWER), fixture(), policy());
        assertEquals(CompleteAnswerStatus.ERROR, r.completeAnswerStatus());
        assertEquals(Origin.FOUNDATION, r.findings().get(0).origin());
        assertNull(r.completenessEvaluation());
    }

    @Test void noDifferenceRemainsNotApplicableAndIsNotCountedAsPass() throws Exception {
        var r = evaluate(ANSWER.replace("快 6 天", "更快"));
        var relation = r.completenessEvaluation().relationEvaluation().results().stream()
                .filter(x -> x.claim().scope().equals("PAIRWISE")).findFirst().orElseThrow();
        assertEquals(Status.NOT_APPLICABLE, relation.factualDifference().status());
        assertEquals(Status.NOT_APPLICABLE, relation.evidenceDifference().status());
        assertNull(r.completeAnswerStatus());
    }

    @Test void unicodePendingSpansAndAllOldUnresolvedReferencesRemainIntact() throws Exception {
        String answer = "😀𠮷。D 比 B 快 6 天，但保证绝不延期。";
        var r = evaluate(answer);
        assertEquals(answer, r.assessmentCoverage().fragments().stream().map(PendingFragment::text).collect(java.util.stream.Collectors.joining()));
        for (var f : r.assessmentCoverage().fragments()) {
            assertEquals(f.text(), answer.substring(f.start(), f.end())); assertTrue(f.supersededUnresolvedRefs().isEmpty());
        }
        assertNull(r.completeAnswerStatus());
    }

    @Test void diagnosticScalarReferencesUseTheSameClaimNamespaceAsCoverage() throws Exception {
        var r = evaluate(ANSWER);
        var ids = r.completenessEvaluation().baseEvaluation().coverage().claims().stream()
                .map(x -> "scalar:" + x.claimId()).toList();
        var refs = r.findings().stream().flatMap(f -> f.sourceRefs().stream()).filter(ref -> ref.startsWith("scalar:")).toList();
        assertFalse(refs.isEmpty());
        assertTrue(refs.stream().allMatch(ref -> ids.contains(ref.substring(0, ref.indexOf('/')))));
    }

    @ParameterizedTest @ValueSource(strings = {"sessionId", "businessCaseId", "businessVersion"})
    void additionalIdentityFieldsMustMatchRawGateObservation(String field) throws Exception {
        ObjectNode n = json.valueToTree(evaluate(ANSWER)); var b = (ObjectNode)n.path("inputBinding");
        if (field.equals("businessVersion")) b.put(field, 2); else b.put(field, "foreign");
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class));
    }

    @ParameterizedTest @ValueSource(strings = {"18446744073709551617", "-18446744073709551615"})
    void overflowingPayloadVersionCannotAliasCurrentVersion(String version) throws Exception {
        var a = withSearch(artifact(ANSWER), "{\"caseVersion\":" + version + "}");
        var r = evaluator.evaluate(definition(), a, fixture(), policy());
        assertEquals(CompleteAnswerStatus.ERROR, r.completeAnswerStatus());
        assertNull(r.completenessEvaluation()); assertEquals(Origin.FOUNDATION, r.findings().get(0).origin());
    }

    @Test void invalidNotApplicableDeclarationCannotBeUsedAsExemption() {
        for (var declared : List.of(Set.of(-1), Set.of(2), Set.of(0)))
            assertEquals(CompleteAnswerStatus.ERROR, ProcurementCompleteAnswerDecision.decide(true, true,
                    List.of(Status.PASS, Status.NOT_APPLICABLE), declared).status());
        var d = ProcurementCompleteAnswerDecision.decide(true, true, List.of(Status.FAIL, Status.NOT_APPLICABLE));
        assertEquals(CompleteAnswerStatus.FAIL, d.status()); assertTrue(d.executionError());
    }

    @ParameterizedTest @ValueSource(strings = {"PASS", "FAIL", "NEEDS_REVIEW", "ERROR"})
    void allNonNullWholeAnswerStatesAreRejectedForUnimplementedResult(String status) throws Exception {
        ObjectNode n = json.valueToTree(evaluate(ANSWER)); n.put("completeAnswerStatus", status);
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class));
    }

    @ParameterizedTest @ValueSource(strings = {"claimRef", "reason", "text", "delete"})
    void pendingCoverageCannotForgeReferencesOrHandoff(String change) throws Exception {
        ObjectNode n = json.valueToTree(evaluate(ANSWER));
        var fragments = (tools.jackson.databind.node.ArrayNode)n.path("assessmentCoverage").path("fragments");
        var f = (ObjectNode)fragments.get(0);
        switch (change) {
            case "claimRef" -> f.set("candidateClaimRefs", json.valueToTree(List.of("relation:999")));
            case "reason" -> f.put("reason", "ALL_SKIPS_IGNORED");
            case "text" -> f.put("text", "X".repeat(f.path("text").asText().length()));
            default -> fragments.remove(0);
        }
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class));
    }

    @Test void errorPathWithoutEffectiveCoverageStillVerifiesRelationAgainstAnswer() throws Exception {
        var a = withSearch(artifact(ANSWER), "{\"caseVersion\":1,\"offers\":42,\"eligibleSuppliers\":[]}");
        var r = evaluator.evaluate(definition(), a, fixture(), policy());
        ObjectNode n = json.valueToTree(r.completenessEvaluation());
        n.putNull("effectiveCoverage"); n.set("elements", json.createArrayNode()); n.put("completenessStatus", "SKIP");
        for (var item : n.path("relationEvaluation").path("results")) {
            var claim = (ObjectNode)item.path("claim");
            if (claim.path("scope").asText().equals("PAIRWISE")) claim.put("difference", 5);
        }
        var modified = json.treeToValue(n, ProcurementAnswerCompletenessGrader.Evaluation.class);
        assertThrows(IllegalArgumentException.class, () -> ProcurementAnswerEvaluationV3.pending(modified));
    }

    @Test void foundationErrorCannotCiteANonexistentScoredClaim() throws Exception {
        ObjectNode n = json.valueToTree(evaluator.evaluate(definition(), null, fixture(), policy()));
        ((ObjectNode)n.path("findings").get(0)).set("sourceRefs", json.valueToTree(List.of("scalar:claim-0")));
        assertThrows(RuntimeException.class, () -> json.treeToValue(n, ProcurementAnswerEvaluationV3.class));
    }

    private ProcurementAnswerEvaluationV3 evaluate(String answer) throws Exception { return evaluator.evaluate(definition(), artifact(answer), fixture(), policy()); }
    private EvaluationCase definition() throws Exception { return ProcurementEvaluationDataset.load().get(0); }
    private byte[] fixture() throws Exception { return Files.readAllBytes(FIXTURE); }
    private byte[] policy() throws Exception { return ProcurementAnswerPolicy.resourceBytes(); }
    private ExecutionArtifact artifact(String answer) throws Exception {
        var c = definition(); var t = Instant.parse("2026-01-01T00:00:00Z");
        var state = json.treeToValue(c.expectedCase(), ProcurementCaseState.class);
        var business = new ProcurementCase("business", "tenant", "session", "user", ProcurementCaseStatus.values()[0], state, t, t, 1, "input");
        var runtime = new AgentRuntimeResult("run", "session", AgentRunState.COMPLETED, AgentStopReason.COMPLETED, answer, "", null, List.of());
        return ProcurementExecutionArtifacts.capture(c, c.userMessage(), runtime, business, List.of(), List.of(), Map.of("fixtureSha256", c.fixtureSha256()));
    }
    private ExecutionArtifact change(ExecutionArtifact a, Consumer<ObjectNode> edit) {
        ObjectNode n = json.valueToTree(a); edit.accept(n); return json.treeToValue(n, ExecutionArtifact.class);
    }
    private ExecutionArtifact withSearch(ExecutionArtifact a, String content) {
        var t = Instant.parse("2026-01-01T00:00:00Z"); String tool = "procurement_supplier_search";
        var r = new ToolExecutionRecord("search", "run", tool, ToolExecutionState.SUCCEEDED,
                new ToolCallRequest(tool, "search", Map.of()), new ToolCallResult(tool, true, content, "", Map.of()), 1, "", t, t);
        return change(a, n -> n.set("toolExecutions", json.valueToTree(List.of(r))));
    }
}
