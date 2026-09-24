package com.agent.platform.procurement;

import com.agent.platform.procurement.model.*;
import com.agent.platform.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementAnswerPolicyTests {
    private static final Path FIXTURE = Path.of("data/procurement/scenarios/complex_workstation_01.json");
    private static final Path POLICY = Path.of("src/test/resources/procurement/evaluation/procurement-answer-policy-v1.json");
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementAnswerCoverageEvaluator evaluator = new ProcurementAnswerCoverageEvaluator();
    @TempDir Path directory;

    @Test
    void reviewedCaseRequirementsAreSpecificNotAllGroundTruthFields() throws Exception {
        var definitions = ProcurementEvaluationDataset.load();
        var policy = ProcurementAnswerPolicy.load(policy(), definitions.get(0), fixture());
        assertEquals(4, policy.cases().size());
        assertEquals(Set.of("recommendation", "selected_total", "selected_lead_time", "delivery_advantage", "budget_compliance"), ids(policy.cases().get(0)));
        assertEquals(Set.of("recommendation", "selected_total", "selected_lead_time", "price_advantage", "delivery_compliance"), ids(policy.cases().get(1)));
        assertEquals(Set.of("recommendation", "selected_total", "selected_lead_time", "unique_eligible"), ids(policy.cases().get(2)));
        assertEquals(Set.of("no_eligible"), ids(policy.cases().get(3)));
        for (var definition : definitions) {
            var selected = policy.forCase(definition.caseId());
            assertTrue(selected.elements().stream().allMatch(e -> e.caseId().equals(definition.caseId()) && !e.sources().isEmpty()));
            assertTrue(selected.elements().stream().flatMap(e -> e.acceptedClaimTypes().stream())
                    .noneMatch(t -> t.contains("RFQ") || t.contains("APPROVAL") || t.equals("QUANTITY")));
            var result = evaluator.evaluate(definition, artifact(definition, "谢谢。"), fixture(), policy());
            assertEquals(selected.elements(), result.requiredAnswerElements());
            assertEquals(Status.SKIP, result.completenessStatus());
            assertEquals(Status.SKIP, result.completeAnswerStatus());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema", "version", "dataset", "fixture", "missingCase", "duplicateCase", "unknownCase", "emptyElements",
            "duplicateElement", "unknownType", "missingSource", "wrongUserQuote", "wrongBenchmarkPath", "unknownConvention", "wrongConventionQuote", "noEligibleRecommendation", "singleCandidateComparison", "unknownField", "incorrectUnique"})
    void rejectsInvalidOrInapplicablePolicyDefinitions(String mutation) throws Exception {
        byte[] changed = changePolicy(root -> {
            var cases = (tools.jackson.databind.node.ArrayNode) root.path("cases");
            var first = (ObjectNode) cases.get(0);
            var elements = (tools.jackson.databind.node.ArrayNode) first.path("elements");
            var element = (ObjectNode) elements.get(0);
            switch (mutation) {
                case "schema" -> root.put("schemaVersion", "future");
                case "version" -> root.put("policyVersion", "future");
                case "dataset" -> root.put("datasetSha256", "0".repeat(64));
                case "fixture" -> root.put("fixtureSha256", "0".repeat(64));
                case "missingCase" -> cases.remove(3);
                case "duplicateCase" -> cases.add(first.deepCopy());
                case "unknownCase" -> first.put("caseId", "unknown");
                case "emptyElements" -> first.set("elements", mapper.createArrayNode());
                case "duplicateElement" -> elements.add(element.deepCopy());
                case "unknownType" -> element.set("acceptedClaimTypes", mapper.valueToTree(List.of("LLM_FREE_TEXT")));
                case "missingSource" -> element.set("sources", mapper.createArrayNode());
                case "wrongUserQuote" -> ((ObjectNode) elements.get(3).path("sources").get(0)).put("quote", "not in original request");
                case "wrongBenchmarkPath" -> ((ObjectNode) element.path("sources").get(0)).put("reference", "/expected/unknown");
                case "unknownConvention" -> ((ObjectNode) elements.get(1).path("sources").get(0)).put("reference", "unknown-policy");
                case "wrongConventionQuote" -> ((ObjectNode) elements.get(1).path("sources").get(0)).put("quote", "所有业务均已执行成功");
                case "noEligibleRecommendation" -> ((ObjectNode) cases.get(3).path("elements").get(0))
                        .set("acceptedClaimTypes", mapper.valueToTree(List.of("RECOMMENDATION")));
                case "singleCandidateComparison" -> ((ObjectNode) cases.get(2).path("elements").get(0))
                        .set("acceptedClaimTypes", mapper.valueToTree(List.of("PAIRWISE_PRICE_COMPARISON")));
                case "unknownField" -> root.put("completeAnswerStatus", "PASS");
                case "incorrectUnique" -> element.set("acceptedClaimTypes", mapper.valueToTree(List.of("UNIQUE_ELIGIBLE")));
                default -> throw new AssertionError(mutation);
            }
        });
        assertThrows(Exception.class, () -> ProcurementAnswerPolicy.load(changed, first(), fixture()));
        var result = evaluate("推荐 Supplier D，总价 58 万元。", changed);
        assertEquals(Status.ERROR, result.evaluationStatus());
        assertNull(result.coverage());
        assertFalse(result.errors().isEmpty());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
    }

    @Test
    void missingBrokenPolicyAndWrongFixtureFailClosed() throws Exception {
        for (byte[] bytes : new byte[][] {null, new byte[0], "{".getBytes(StandardCharsets.UTF_8)})
            assertEquals(Status.ERROR, evaluate("推荐 Supplier D。", bytes).evaluationStatus());
        var a = artifact(first(), "推荐 Supplier D。");
        assertEquals(Status.ERROR, evaluator.evaluate(first(), a, "{}".getBytes(StandardCharsets.UTF_8), policy()).evaluationStatus());
        assertThrows(java.io.IOException.class, () -> evaluator.replay(first(), directory.resolve("missing-artifact"), FIXTURE, POLICY, directory.resolve("report")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicateKey", "trailingDocument"})
    void ambiguousJsonPolicyIsNotAccepted(String variant) throws Exception {
        String json = new String(policy(), StandardCharsets.UTF_8);
        json = variant.equals("trailingDocument") ? json + "{}"
                : json.replace("\"policyVersion\":", "\"policyVersion\":\"future\",\"policyVersion\":");
        assertEquals(Status.ERROR, evaluate("推荐 Supplier D。", json.getBytes(StandardCharsets.UTF_8)).evaluationStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseId", "datasetSha256", "fixtureSha256", "conversationId"})
    void invalidArtifactIdentityIsRejectedBeforeCoverage(String field) throws Exception {
        var c = first();
        ObjectNode raw = mapper.valueToTree(artifact(c, "推荐 Supplier D。"));
        switch (field) {
            case "fixtureSha256" -> ((ObjectNode) raw.path("metadata")).put(field, "0".repeat(64));
            case "conversationId" -> ((ObjectNode) raw.path("finalCase")).put(field, "other-session");
            default -> raw.put(field, "other-identity");
        }
        var result = evaluator.evaluate(c, mapper.treeToValue(raw, ExecutionArtifact.class), fixture(), policy());
        assertEquals(Status.ERROR, result.evaluationStatus());
        assertNull(result.coverage());
        assertTrue(result.requiredAnswerElements().isEmpty());
    }

    @Test
    void cannotDeserializePrematureCompletenessPass() throws Exception {
        var result = evaluate("推荐 Supplier D。谢谢。", policy());
        for (String field : List.of("evaluationStatus", "completeAnswerStatus", "completenessStatus")) {
            ObjectNode raw = mapper.valueToTree(result); raw.put(field, "PASS");
            assertThrows(RuntimeException.class, () -> mapper.treeToValue(raw, ProcurementAnswerEvaluationV2.class));
        }
    }

    @Test
    void policyFingerprintChangesWithoutDependingOnAgentAnswer() throws Exception {
        byte[] changed = changePolicy(root -> ((ObjectNode) root.path("cases").get(0).path("elements").get(0))
                .put("rationale", "A revised human-reviewed justification"));
        var baseline = evaluate("推荐 Supplier D。", policy());
        var revised = evaluate("推荐 Supplier D。", changed);
        var otherAnswer = evaluate("总价错了，RFQ 已创建。", policy());
        assertNotEquals(baseline.policySha256(), revised.policySha256());
        assertEquals(baseline.policyVersion(), revised.policyVersion());
        assertEquals(baseline.legacyEvaluation(), revised.legacyEvaluation());
        assertEquals(baseline.policySha256(), otherAnswer.policySha256());
        assertEquals(baseline.requiredAnswerElements(), otherAnswer.requiredAnswerElements());
        assertNotEquals(baseline.artifactSha256(), otherAnswer.artifactSha256());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseId", "artifactSha256", "datasetSha256", "fixtureSha256", "answerSha256",
            "extractorVersion", "coverageAnalyzerVersion", "answerGraderVersion", "policyVersion", "policySha256",
            "ruleCase", "missingCoverage", "errors", "status", "claimVerdictIdentity"})
    void rejectsInconsistentV2Bindings(String mutation) throws Exception {
        ObjectNode raw = mapper.valueToTree(evaluate("推荐 Supplier D。", policy()));
        switch (mutation) {
            case "ruleCase" -> ((ObjectNode) raw.path("requiredAnswerElements").get(0)).put("caseId", "other");
            case "missingCoverage" -> raw.putNull("coverage");
            case "errors" -> raw.set("errors", mapper.valueToTree(List.of("infrastructure error")));
            case "status" -> raw.putNull("evaluationStatus");
            case "claimVerdictIdentity" -> ((ObjectNode) raw.path("legacyEvaluation").path("claims").get(0).path("claim")).put("subject", "other");
            default -> raw.put(mutation, "other");
        }
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(raw, ProcurementAnswerEvaluationV2.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gap", "overlap", "text", "reference", "counts", "unresolvedFlag", "emptyFlag", "nonFactual", "claimSpan", "duplicateClaim", "missingFragments"})
    void rejectsCorruptedCoverageAfterSerialization(String mutation) {
        ObjectNode raw = mapper.valueToTree(new ProcurementAnswerCoverage().analyze("推荐 Supplier D。神奇魔法。"));
        var fragments = (tools.jackson.databind.node.ArrayNode) raw.path("fragments");
        var first = (ObjectNode) fragments.get(0);
        switch (mutation) {
            case "gap", "overlap" -> {
                var second = (ObjectNode) fragments.get(1);
                int delta = mutation.equals("gap") ? 1 : -1;
                second.put("start", second.path("start").asInt() + delta);
                second.put("end", second.path("end").asInt() + delta);
            }
            case "text" -> first.put("text", "lost");
            case "reference" -> first.set("claimIds", mapper.valueToTree(List.of("missing")));
            case "counts" -> ((ObjectNode) raw.path("counts")).put("UNKNOWN_CONTENT", 0);
            case "unresolvedFlag" -> raw.put("hasUnresolvedContent", false);
            case "emptyFlag" -> raw.put("emptyAnswer", true);
            case "nonFactual" -> first.put("kind", "NON_FACTUAL");
            case "claimSpan" -> ((ObjectNode) raw.path("claims").get(0).path("claim")).put("start", 1);
            case "duplicateClaim" -> ((tools.jackson.databind.node.ArrayNode) raw.path("claims")).add(raw.path("claims").get(0).deepCopy());
            case "missingFragments" -> raw.set("fragments", mapper.createArrayNode());
            default -> throw new AssertionError(mutation);
        }
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(raw, ProcurementAnswerCoverage.Analysis.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"推荐 Supplier D，总价 58 万元。谢谢。", "推荐 Supplier D，总价 57 万元。", "RFQ 已创建。", "", "谢谢。", "推荐 Supplier D。神奇魔法。"})
    void v2RetainsEveryLegacyVerdictAndNeverAssessesCompleteness(String answer) throws Exception {
        var c = first(); var a = artifact(c, answer);
        var old = new ProcurementAnswerGrader().grade(c, a, fixture());
        var result = evaluator.evaluate(c, a, fixture(), policy());
        assertEquals(old, result.legacyEvaluation());
        assertEquals(old.claims().stream().map(ProcurementAnswerEvaluation.ClaimResult::claim).toList(),
                result.coverage().claims().stream().map(ProcurementAnswerCoverage.Extraction::claim).toList());
        assertNotEquals(Status.PASS, result.evaluationStatus());
        assertEquals(Status.SKIP, result.completenessStatus());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
        assertEquals(c.datasetSha256(), result.datasetSha256());
        assertEquals(c.fixtureSha256(), result.fixtureSha256());
        assertEquals(ProcurementEvaluationReports.artifactHash(a), result.artifactSha256());
    }

    @Test
    void v2ReplayIsStableAndCannotOverwriteOldEvidenceOrSidecars() throws Exception {
        var c = first(); var a = artifact(c, "推荐 Supplier D，总价 58 万元。谢谢。");
        Path artifact = directory.resolve("artifact.json"), old = directory.resolve("old-sidecar.json"), output = directory.resolve("v2.json");
        ProcurementEvaluationReports.write(artifact, a);
        ProcurementEvaluationReports.write(old, new ProcurementAnswerGrader().grade(c, a, fixture()));
        byte[] original = Files.readAllBytes(artifact), oldBytes = Files.readAllBytes(old);
        var one = evaluator.replay(c, artifact, FIXTURE, POLICY, output);
        Path second = directory.resolve("v2-again.json");
        assertEquals(one, evaluator.replay(c, artifact, FIXTURE, POLICY, second));
        assertArrayEquals(Files.readAllBytes(output), Files.readAllBytes(second));
        assertEquals(one, mapper.readValue(Files.readString(output), ProcurementAnswerEvaluationV2.class));
        for (Path protectedPath : List.of(artifact, old, output, POLICY, FIXTURE))
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> evaluator.replay(c, artifact, FIXTURE, POLICY, protectedPath));
        assertArrayEquals(original, Files.readAllBytes(artifact));
        assertArrayEquals(oldBytes, Files.readAllBytes(old));
    }

    @Test
    @EnabledIfSystemProperty(named = "answer.v2.artifact", matches = ".+")
    void explicitHistoricalArtifactReplay() throws Exception {
        Path artifact = Path.of(System.getProperty("answer.v2.artifact"));
        byte[] original = Files.readAllBytes(artifact);
        var saved = ProcurementEvaluationReports.readArtifact(artifact);
        var c = ProcurementEvaluationDataset.load().stream().filter(d -> d.caseId().equals(saved.caseId())).findFirst().orElseThrow();
        Path output = Path.of(System.getProperty("answer.v2.report", directory.resolve("answer-v2.json").toString()));
        var result = evaluator.replay(c, artifact, FIXTURE, POLICY, output);
        assertNotEquals(Status.ERROR, result.evaluationStatus(), result.toString());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
        assertEquals(result, evaluator.replay(c, artifact, FIXTURE, POLICY, directory.resolve("again.json")));
        assertArrayEquals(original, Files.readAllBytes(artifact));
    }

    private Set<String> ids(ProcurementAnswerPolicy.CasePolicy c) {
        var ids = new HashSet<String>(); c.elements().forEach(e -> ids.add(e.elementId())); return ids;
    }
    private EvaluationCase first() throws Exception { return ProcurementEvaluationDataset.load().get(0); }
    private byte[] fixture() throws Exception { return Files.readAllBytes(FIXTURE); }
    private byte[] policy() throws Exception { return ProcurementAnswerPolicy.resourceBytes(); }
    private byte[] changePolicy(Consumer<ObjectNode> edit) throws Exception {
        var root = (ObjectNode) mapper.readTree(policy()); edit.accept(root); return mapper.writeValueAsBytes(root);
    }
    private ProcurementAnswerEvaluationV2 evaluate(String answer, byte[] policy) throws Exception {
        var c = first(); return evaluator.evaluate(c, artifact(c, answer), fixture(), policy);
    }
    private ExecutionArtifact artifact(EvaluationCase c, String answer) {
        var state = mapper.treeToValue(c.expectedCase(), ProcurementCaseState.class);
        var time = Instant.parse("2026-01-01T00:00:00Z");
        var business = new ProcurementCase("business-case", "tenant", "conversation", "buyer", ProcurementCaseStatus.values()[0], state, time, time, 1, "input");
        var runtime = new AgentRuntimeResult("run", "conversation", AgentRunState.COMPLETED, AgentStopReason.COMPLETED, answer, "", null, List.of());
        return ProcurementExecutionArtifacts.capture(c, c.userMessage(), runtime, business, List.of(), List.of(),
                Map.of("fixtureSha256", c.fixtureSha256(), "executionMode", "HANDCRAFTED_NO_TOOL_SUPPORT"));
    }
}
