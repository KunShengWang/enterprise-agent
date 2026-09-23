package com.agent.platform.procurement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.agent.platform.procurement.ProcurementComparisonInputs.*;
import static com.agent.platform.procurement.ProcurementAnswerEvaluationV3.CompleteAnswerStatus;

/** Synthetic experiment inputs only: no real model identity or independence is attested. */
class ProcurementComparisonInputsTests {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final ProcurementComparisonInputs validator = new ProcurementComparisonInputs();
    private static final Path FIXTURE = Path.of("data/procurement/scenarios/complex_workstation_01.json").toAbsolutePath();
    private static final Path POLICY = Path.of("src/test/resources/procurement/evaluation/procurement-answer-policy-v1.json").toAbsolutePath();
    private static final String ANSWER = "推荐 Supplier D，总价 58 万元，交期 12 天。D 比 B 快 6 天。D 的总价满足本次预算。";

    @Test void differentDeclaredModelsAndPassFailAreComparable() throws Exception {
        var m = manifest(1); var result = validate(m);
        assertEquals(Eligibility.COMPARABLE, result.eligibility()); assertEquals(Scope.DECLARED_SUBSET, result.scope());
        assertEquals(Set.of(CompleteAnswerStatus.PASS, CompleteAnswerStatus.FAIL), new HashSet<>(result.records().stream().map(RecordCheck::completeAnswerStatus).toList()));
        assertTrue(result.limitations().contains("DECLARED_EXPERIMENT_LABELS"));
        assertTrue(result.limitations().contains("INDEPENDENCE_NOT_ATTESTED"));
        assertTrue(result.records().stream().allMatch(r -> r.inputBinding() != null));
    }

    @Test void fullFrozenBenchmarkMatchesByCaseAndPreservesAllInputs() throws Exception {
        var m = manifest(4); var before = snapshot(); var result = validate(m);
        assertEquals(Eligibility.COMPARABLE, result.eligibility()); assertEquals(Scope.FULL_FROZEN_BENCHMARK, result.scope());
        assertEquals(4, result.cases().size());
        for (var c : result.cases()) { assertEquals(Eligibility.COMPARABLE, c.eligibility()); assertEquals(Set.of("A", "B"), c.groupRecordIds().keySet()); }
        for (var e : before.entrySet()) assertArrayEquals(e.getValue(), Files.readAllBytes(e.getKey()));
    }

    @Test void missingCaseIsNotInventedAsFailOrSilentlyDropped() throws Exception {
        var m = manifest(4); ((ArrayNode)m.path("records")).remove(7); var result = validate(m);
        assertEquals(Eligibility.NOT_COMPARABLE, result.eligibility()); assertEquals(4, result.cases().size()); assertEquals(7, result.records().size());
        assertHas(result, "MISSING_REQUIRED_RECORDS");
        assertEquals(3, result.cases().stream().filter(c -> c.eligibility() == Eligibility.COMPARABLE).count());
    }

    @ParameterizedTest @ValueSource(strings = {"artifactCopy", "sameRunDifferentArtifact", "duplicateRecordId", "conflictingGroup", "duplicateGroup"})
    void duplicateAndConflictingIdentitiesNeverCountTwice(String kind) throws Exception {
        var m = manifest(1); var records = (ArrayNode)m.path("records"); var a = (ObjectNode)records.get(0); var b = (ObjectNode)records.get(1);
        switch (kind) {
            case "artifactCopy" -> { var replacement = (ObjectNode)a.deepCopy(); replacement.put("recordId", "B-0").put("groupId", "B"); records.set(1, replacement); }
            case "sameRunDifferentArtifact" -> records.set(1, entry("B", 0, "A-0", ANSWER.replace("58 万元", "57 万元"), "reused"));
            case "duplicateRecordId" -> b.put("recordId", a.path("recordId").asText());
            default -> { var group = (ObjectNode)m.path("groups").get(0).deepCopy(); if (kind.equals("conflictingGroup")) group.put("model", "another"); ((ArrayNode)m.path("groups")).add(group); }
        }
        var result = validate(m); assertEquals(Eligibility.NOT_COMPARABLE, result.eligibility());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().contains("DUPLICATE") || d.code().contains("REUSED")));
        assertTrue(result.cases().stream().allMatch(c -> c.eligibility() == Eligibility.NOT_COMPARABLE));
    }

    @Test void distinctCapturedRunsWithIdenticalAnswersAreRetainedWithoutIndependenceClaim() throws Exception {
        var m = manifest(1); var records = (ArrayNode)m.path("records");
        records.set(1, entry("B", 0, "B-new", ANSWER, "same-answer"));
        var result = validate(m); assertEquals(Eligibility.COMPARABLE, result.eligibility()); assertEquals(2, result.records().size());
        assertEquals(result.records().get(0).inputBinding().answerSha256(), result.records().get(1).inputBinding().answerSha256());
        assertNotEquals(result.records().get(0).inputBinding().artifactSha256(), result.records().get(1).inputBinding().artifactSha256());
        assertTrue(result.limitations().contains("INDEPENDENCE_NOT_ATTESTED"));
    }

    @Test void repeatedCaseRequiresExplicitBalancedRecordRequirement() throws Exception {
        var m = manifest(1); var records = (ArrayNode)m.path("records");
        records.add(entry("A", 0, "A-second", ANSWER, "second-A")); records.add(entry("B", 0, "B-second", ANSWER, "second-B"));
        assertHas(validate(m), "UNDECLARED_EXTRA_RECORDS");
        ((ObjectNode)m.path("requiredRecordsPerCase")).put(caseId(0), 2);
        var result = validate(m); assertEquals(Eligibility.COMPARABLE, result.eligibility());
        assertEquals(2, result.cases().get(0).groupRecordIds().get("A").size());
        records.remove(3); assertHas(validate(m), "MISSING_REQUIRED_RECORDS");
    }

    @ParameterizedTest @ValueSource(strings = {"policy", "grader", "dataset", "fakePass", "v1", "v2"})
    void tamperedOrHistoricalSidecarsCannotEnterTrustedCollection(String kind) throws Exception {
        var m = manifest(1); ObjectNode e = (ObjectNode)m.path("records").get(1);
        ObjectNode sidecar = (ObjectNode)json.readTree(Files.readAllBytes(Path.of(e.path("v3Path").asText())));
        switch (kind) {
            case "policy" -> ((ObjectNode)sidecar.path("inputBinding")).put("policySha256", "0".repeat(64));
            case "grader" -> ((ObjectNode)sidecar.path("inputBinding").path("componentVersions")).put("scalarGrader", "future");
            case "dataset" -> ((ObjectNode)sidecar.path("inputBinding")).put("datasetSha256", "0".repeat(64));
            case "v1" -> sidecar = (ObjectNode)sidecar.path("completenessEvaluation").path("baseEvaluation").path("legacyEvaluation").deepCopy();
            case "v2" -> sidecar = (ObjectNode)sidecar.path("completenessEvaluation").path("baseEvaluation").deepCopy();
            default -> sidecar.put("completeAnswerStatus", "PASS");
        }
        if (kind.equals("v1") || kind.equals("v2")) sidecar.put("completeAnswerStatus", "PASS");
        byte[] bytes = ProcurementEvaluationReports.canonicalJson(sidecar).getBytes(StandardCharsets.UTF_8);
        Path forged = Files.write(directory.resolve("procurement-answer-v3-" + ProcurementEvaluationReports.sha256(bytes) + ".json"), bytes);
        e.put("v3Path", forged.toString()); var result = validate(m);
        assertEquals(Eligibility.NOT_COMPARABLE, result.eligibility()); assertHas(result, "V3_READ_REJECTED");
    }

    @ParameterizedTest @ValueSource(strings = {"case", "run", "session", "artifactHash", "missingArtifact", "missingV3", "wrongRawArtifact", "unknownGroup", "outsideScope", "dataset", "fixture", "policy"})
    void manifestCannotOverrideEvaluationIdentity(String kind) throws Exception {
        var m = manifest(1); ObjectNode e = (ObjectNode)m.path("records").get(0);
        switch (kind) {
            case "case" -> e.put("caseId", caseId(1)); case "run" -> e.put("runId", "other"); case "session" -> e.put("sessionId", "other");
            case "artifactHash" -> e.put("artifactSha256", "0".repeat(64)); case "missingArtifact" -> e.put("artifactPath", "missing.json");
            case "missingV3" -> e.put("v3Path", "missing.json");
            case "wrongRawArtifact" -> e.put("artifactPath", m.path("records").get(1).path("artifactPath").asText());
            case "unknownGroup" -> e.put("groupId", "missing");
            case "outsideScope" -> ((ObjectNode)m.path("requiredRecordsPerCase")).removeAll().put(caseId(1), 1);
            case "dataset" -> m.put("datasetSha256", "0".repeat(64));
            case "fixture" -> m.put("fixturePath", Files.writeString(directory.resolve("bad-fixture.json"), "{}").toString());
            default -> m.put("policyPath", Files.writeString(directory.resolve("bad-policy.json"), "{}").toString());
        }
        var result = validate(m); assertEquals(Eligibility.NOT_COMPARABLE, result.eligibility()); assertFalse(result.diagnostics().isEmpty());
    }

    @Test void foundationErrorIsNotAnOrdinaryAnswerFailure() throws Exception {
        var m = manifest(1); var e = (ObjectNode)m.path("records").get(0); Path raw = Path.of(e.path("artifactPath").asText());
        ObjectNode a = (ObjectNode)json.readTree(Files.readString(raw)); a.put("datasetSha256", "0".repeat(64)); Files.writeString(raw, json.writeValueAsString(a));
        e.put("v3Path", new ProcurementAnswerV3Reports().replay(raw, FIXTURE, POLICY, directory.resolve("blocked")).toString());
        var result = validate(m); assertHas(result, "FOUNDATION_ERROR_NOT_AN_ANSWER_FAILURE");
        var check = result.records().stream().filter(r -> r.recordId().equals("A-0")).findFirst().orElseThrow();
        assertEquals(CompleteAnswerStatus.ERROR, check.completeAnswerStatus()); assertNull(check.inputBinding()); assertEquals(Eligibility.NOT_COMPARABLE, check.eligibility());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void internallyConsistentStatusStillRequiresRawReevaluation(boolean alreadyFail) throws Exception {
        var m = manifest(1); ObjectNode e = (ObjectNode)m.path("records").get(alreadyFail ? 1 : 0);
        var reports = new ProcurementAnswerV3Reports();
        ObjectNode forged = json.valueToTree(reports.read(Path.of(e.path("v3Path").asText())));
        Path rawPath = Path.of(e.path("artifactPath").asText());
        var original = ProcurementEvaluationReports.readArtifact(rawPath);
        ObjectNode raw = json.valueToTree(original); raw.set("toolExecutions", json.createArrayNode());
        var changed = json.treeToValue(raw, ProcurementEvaluation.ExecutionArtifact.class);
        ProcurementEvaluationReports.write(rawPath, changed);
        String hash = ProcurementEvaluationReports.artifactHash(changed);
        replaceArtifactHash(forged, hash); e.put("artifactSha256", hash);
        byte[] bytes = ProcurementEvaluationReports.canonicalJson(forged).getBytes(StandardCharsets.UTF_8);
        Path path = Files.write(directory.resolve("procurement-answer-v3-" + ProcurementEvaluationReports.sha256(bytes) + ".json"), bytes);
        var stored = reports.read(path);
        assertEquals(alreadyFail ? CompleteAnswerStatus.FAIL : CompleteAnswerStatus.PASS, stored.completeAnswerStatus());
        var fresh = new ProcurementCompleteAnswerEvaluator().evaluate(ProcurementEvaluationDataset.load().get(0), changed,
                Files.readAllBytes(FIXTURE), Files.readAllBytes(POLICY));
        if (alreadyFail) assertEquals(stored.completeAnswerStatus(), fresh.completeAnswerStatus());
        assertNotEquals(stored.assessmentCoverage(), fresh.assessmentCoverage());
        assertNotEquals(stored.findings(), fresh.findings());
        e.put("v3Path", path.toString()); var result = validate(m);
        assertHas(result, "RAW_REEVALUATION_MISMATCH"); assertEquals(Eligibility.NOT_COMPARABLE, result.eligibility());
    }

    @Test void equalGroupTotalsCannotCompensateForDifferentCaseDistribution() throws Exception {
        var m = manifest(4); var records = (ArrayNode)m.path("records");
        records.set(0, entry("A", 1, "A-extra-case1", ANSWER, "A-extra-case1"));
        assertEquals(8, records.size());
        var result = validate(m);
        assertEquals(Eligibility.NOT_COMPARABLE, result.eligibility());
        assertHas(result, "MISSING_REQUIRED_RECORDS"); assertHas(result, "UNDECLARED_EXTRA_RECORDS");
        for (int i : List.of(0, 1)) {
            String id = caseId(i);
            assertEquals(Eligibility.NOT_COMPARABLE, result.cases().stream().filter(c -> c.caseId().equals(id)).findFirst().orElseThrow().eligibility());
        }
    }

    @Test void copiedFilesAndChangedGroupLabelsStillReferToOneRun() throws Exception {
        var m = manifest(1); ObjectNode a = (ObjectNode)m.path("records").get(0), b = (ObjectNode)a.deepCopy();
        Path artifactCopy = Files.copy(Path.of(a.path("artifactPath").asText()), directory.resolve("copied.artifact.json"));
        Path source = Path.of(a.path("v3Path").asText()); Path copyDir = Files.createDirectory(directory.resolve("copied-v3"));
        Path v3Copy = Files.copy(source, copyDir.resolve(source.getFileName()));
        b.put("recordId", "copy").put("groupId", "B").put("artifactPath", artifactCopy.toString()).put("v3Path", v3Copy.toString());
        ((ArrayNode)m.path("records")).set(1, b);
        var result = validate(m); assertHas(result, "DUPLICATE_ARTIFACT_REFERENCE"); assertHas(result, "RUN_ID_REUSED_INDEPENDENCE_UNPROVEN");
        assertTrue(result.records().stream().allMatch(r -> r.eligibility() == Eligibility.NOT_COMPARABLE));
        assertTrue(result.cases().get(0).groupRecordIds().values().stream().allMatch(List::isEmpty));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void trustedScoringErrorRemainsComparableAndSeparateFromFoundationError(boolean factualFail) throws Exception {
        var m = manifest(1); ObjectNode e = (ObjectNode)m.path("records").get(factualFail ? 1 : 0);
        Path path = Path.of(e.path("artifactPath").asText());
        ObjectNode raw = json.valueToTree(ProcurementEvaluationReports.readArtifact(path));
        ObjectNode toolResult = (ObjectNode)raw.path("toolExecutions").get(0).path("result");
        ObjectNode payload = (ObjectNode)json.readTree(toolResult.path("content").asText()); payload.put("offers", 42);
        toolResult.put("content", json.writeValueAsString(payload));
        var changed = json.treeToValue(raw, ProcurementEvaluation.ExecutionArtifact.class); ProcurementEvaluationReports.write(path, changed);
        e.put("artifactSha256", ProcurementEvaluationReports.artifactHash(changed));
        e.put("v3Path", new ProcurementAnswerV3Reports().replay(path, FIXTURE, POLICY, directory.resolve("scoring-error")).toString());
        var result = validate(m); assertEquals(Eligibility.COMPARABLE, result.eligibility());
        var check = result.records().stream().filter(r -> r.recordId().equals(e.path("recordId").asText())).findFirst().orElseThrow();
        assertEquals(factualFail ? CompleteAnswerStatus.FAIL : CompleteAnswerStatus.NEEDS_REVIEW, check.completeAnswerStatus());
        assertEquals(ProcurementAnswerEvaluationV3.ExecutionStatus.ERROR, check.assessmentExecutionStatus()); assertNotNull(check.inputBinding());
        assertTrue(check.diagnostics().isEmpty());
    }

    @Test void extraInvalidRecordIsNotSilentlyExcludedAfterRequiredSlotsAreFilled() throws Exception {
        var m = manifest(1); ObjectNode extra = (ObjectNode)m.path("records").get(0).deepCopy();
        extra.put("recordId", "bad-extra").put("v3Path", "missing.json"); ((ArrayNode)m.path("records")).add(extra);
        var result = validate(m); assertEquals(3, result.records().size()); assertEquals(Eligibility.NOT_COMPARABLE, result.eligibility());
        assertEquals(Eligibility.NOT_COMPARABLE, result.cases().get(0).eligibility());
        assertEquals(List.of("A-0"), result.cases().get(0).groupRecordIds().get("A"));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.recordId().equals("bad-extra") && d.groupId().equals("A")
                && d.caseId().equals(extra.path("caseId").asText()) && d.code().equals("V3_READ_REJECTED")));
    }

    private void replaceArtifactHash(tools.jackson.databind.JsonNode node, String hash) {
        if (node.isObject()) {
            if (node.has("artifactSha256")) ((ObjectNode)node).put("artifactSha256", hash);
            node.properties().forEach(e -> replaceArtifactHash(e.getValue(), hash));
        } else if (node.isArray()) node.forEach(n -> replaceArtifactHash(n, hash));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inputOrderCannotChangeEligibilityOrDiagnostics(boolean invalid) throws Exception {
        var m = manifest(2);
        if (invalid) { ((ObjectNode)m.path("records").get(1)).put("runId", "wrong"); ((ArrayNode)m.path("records")).add(m.path("records").get(0).deepCopy()); }
        var one = validate(m);
        for (String key : List.of("groups", "records")) { var reversed = json.createArrayNode(); var arr = m.path(key); for (int i=arr.size()-1;i>=0;i--) reversed.add(arr.get(i)); m.set(key, reversed); }
        var two = validate(m); assertEquals(one, two);
        assertEquals(ProcurementEvaluationReports.canonicalJson(json.valueToTree(one)), ProcurementEvaluationReports.canonicalJson(json.valueToTree(two)));
    }

    @ParameterizedTest @ValueSource(strings = {"version", "missing", "unknown", "duplicate", "trailing", "null"})
    void malformedManifestIsRejected(String kind) throws Exception {
        var m = manifest(1);
        switch(kind) { case "version" -> m.put("schemaVersion", "future"); case "missing" -> m.remove("groups"); case "unknown" -> m.put("completeAnswerStatus", "PASS"); default -> { } }
        String text = json.writeValueAsString(m);
        if (kind.equals("duplicate")) text = text.replaceFirst("\\{", "{\"experimentId\":\"forged\",");
        if (kind.equals("trailing")) text += " {}";
        if (kind.equals("null")) text = "null";
        Path path = Files.writeString(directory.resolve("manifest.json"), text);
        assertThrows(IOException.class, () -> validator.validate(path));
    }

    private ObjectNode manifest(int cases) throws Exception {
        var c = ProcurementEvaluationDataset.load().get(0); ObjectNode m = json.createObjectNode();
        m.put("schemaVersion", ProcurementExperimentManifest.VERSION).put("experimentId", "SYNTHETIC_TEST_ONLY")
                .put("datasetVersion", c.datasetVersion()).put("datasetSha256", c.datasetSha256()).put("fixturePath", FIXTURE.toString()).put("policyPath", POLICY.toString());
        var scope = m.putObject("requiredRecordsPerCase"); var groups = m.putArray("groups"); var entries = m.putArray("records");
        for (String group : List.of("A", "B")) {
            var g = groups.addObject(); g.put("groupId", group).put("model", "declared-model-" + group).put("promptVersion", "prompt-" + group).put("agentVersion", "agent-v1");
            g.putObject("configuration").put("dataOrigin", "SYNTHETIC");
            for(int i=0;i<cases;i++) { scope.put(caseId(i),1); entries.add(entry(group,i,group+"-"+i,group.equals("A") ? ANSWER : ANSWER.replace("58 万元","57 万元"),group+"-"+i)); }
        }
        return m;
    }
    private ObjectNode entry(String group, int i, String run, String answer, String fileId) throws Exception {
        var original = new ProcurementAnswerAggregationTests().good(i, answer); ObjectNode raw = json.valueToTree(original);
        ((ObjectNode)raw.path("runtime")).put("runId", run);
        for(var tool:raw.path("toolExecutions")) ((ObjectNode)tool).put("runId", run);
        var artifact = json.treeToValue(raw, ProcurementEvaluation.ExecutionArtifact.class);
        Path path = directory.resolve(fileId+".artifact.json"); ProcurementEvaluationReports.write(path, artifact);
        Path v3 = new ProcurementAnswerV3Reports().replay(path, FIXTURE, POLICY, directory.resolve(fileId+"-v3"));
        return json.valueToTree(new ProcurementExperimentManifest.Entry(fileId,group,caseId(i),run,artifact.runtime().sessionId(),
                ProcurementEvaluationReports.artifactHash(artifact),path.toString(),v3.toString()));
    }
    private String caseId(int i) throws Exception { return ProcurementEvaluationDataset.load().get(i).caseId(); }
    private Result validate(ObjectNode m) throws Exception { return validator.validate(Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(m))); }
    private void assertHas(Result r,String code) { assertTrue(r.diagnostics().stream().anyMatch(d->d.code().equals(code)),r.diagnostics().toString()); }
    private Map<Path,byte[]> snapshot() throws Exception { var saved=new HashMap<Path,byte[]>(); try(var files=Files.walk(directory)) { for(var p:files.filter(Files::isRegularFile).toList()) saved.put(p,Files.readAllBytes(p)); } return saved; }
}
