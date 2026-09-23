package com.agent.platform.procurement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static com.agent.platform.procurement.ProcurementAnswerEvaluationV3.*;

class ProcurementAnswerV3ReplayTests {
    private static final Path FIXTURE = Path.of("data/procurement/scenarios/complex_workstation_01.json");
    private static final Path POLICY = Path.of("src/test/resources/procurement/evaluation/procurement-answer-policy-v1.json");
    private static final List<String> ANSWERS = List.of(
            "推荐 Supplier D，总价 58 万元，交期 12 天。D 比 B 快 6 天。D 的总价满足本次预算。",
            "推荐 Supplier B，总价 55 万元，交期 18 天。B 比 D 便宜 3 万元。B 的交期满足本次交付期限。",
            "推荐 Supplier D，总价 58 万元，交期 12 天。当前原始硬约束下仅 D 合格。",
            "当前原始硬约束下无合格供应商。");
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final ProcurementAnswerV3Reports reports = new ProcurementAnswerV3Reports();

    @ParameterizedTest @ValueSource(ints = {0,1,2,3})
    void syntheticPassRoundTripIsByteStableAndRevocable(int index) throws Exception {
        var artifact = new ProcurementAnswerAggregationTests().good(index, ANSWERS.get(index));
        Path input = directory.resolve("artifact.json"); ProcurementEvaluationReports.write(input, artifact);
        byte[] original = Files.readAllBytes(input);
        Path one = reports.replay(input, FIXTURE, POLICY, directory.resolve("one"));
        Path two = reports.replay(input, FIXTURE, POLICY, directory.resolve("two"));
        assertEquals(one.getFileName(), two.getFileName()); assertArrayEquals(Files.readAllBytes(one), Files.readAllBytes(two));
        var result = reports.read(one);
        assertEquals(CompleteAnswerStatus.PASS, result.completeAnswerStatus());
        var expected = new ProcurementCompleteAnswerEvaluator().evaluate(ProcurementEvaluationDataset.load().get(index), artifact,
                Files.readAllBytes(FIXTURE), Files.readAllBytes(POLICY));
        assertEquals(expected, result); assertEquals(result, reports.read(two));
        assertEquals(ProcurementEvaluation.Status.SKIP, result.completenessEvaluation().completeAnswerStatus());
        assertEquals(ProcurementEvaluation.Status.SKIP, result.completenessEvaluation().baseEvaluation().completeAnswerStatus());
        assertEquals(ProcurementEvaluation.Status.SKIP, result.completenessEvaluation().baseEvaluation().legacyEvaluation().completeAnswerStatus());
        assertThrows(FileAlreadyExistsException.class, () -> reports.replay(input, FIXTURE, POLICY, one.getParent()));
        assertArrayEquals(original, Files.readAllBytes(input));
        Path mutant = directory.resolve("mutant.json");
        ProcurementEvaluationReports.write(mutant, new ProcurementAnswerAggregationTests().good(index, ANSWERS.get(index) + "保证绝不延期。"));
        var revoked = reports.read(reports.replay(mutant, FIXTURE, POLICY, directory.resolve("one")));
        assertNotEquals(CompleteAnswerStatus.PASS, revoked.completeAnswerStatus());
        assertEquals(result, reports.read(one)); assertArrayEquals(original, Files.readAllBytes(input));
    }

    @ParameterizedTest @ValueSource(strings = {"artifact", "v1", "v2", "relation", "frozen-report", "fixture", "policy"})
    void existingFilesAndAliasesCannotBeOverwritten(String kind) throws Exception {
        Path input = source();
        Path existing = switch (kind) { case "artifact" -> input; case "fixture" -> FIXTURE; case "policy" -> POLICY;
            default -> Files.writeString(directory.resolve(kind + ".json"), "historical " + kind); };
        byte[] before = Files.readAllBytes(existing);
        assertThrows(IOException.class, () -> reports.replay(input, FIXTURE, POLICY, existing));
        Path first = reports.replay(input, FIXTURE, POLICY, directory.resolve("first"));
        Path collisionDir = Files.createDirectory(directory.resolve("collision"));
        // Even an existing input/history hard-link bearing the correct new output name is protected.
        Path linkSource = Set.of("fixture", "policy").contains(kind)
                ? Files.copy(existing, directory.resolve("protected-copy.json")) : existing;
        Files.createLink(collisionDir.resolve(first.getFileName()), linkSource.toAbsolutePath());
        assertThrows(FileAlreadyExistsException.class, () -> reports.replay(input, FIXTURE, POLICY, collisionDir));
        assertArrayEquals(before, Files.readAllBytes(existing));
    }

    @ParameterizedTest @ValueSource(strings = {"partialException", "shortWrite", "publishCollision"})
    void failedWriteNeverPublishesPartialSidecar(String mode) throws Exception {
        Path input = source(), output = directory.resolve("out");
        var failing = new ProcurementAnswerV3Reports((path, bytes) -> {
            if (mode.equals("publishCollision")) {
                Files.write(path, bytes);
                Files.writeString(path.getParent().resolve(name(bytes)), "concurrent writer");
            } else {
                Files.write(path, Arrays.copyOf(bytes, 20));
                if (mode.equals("partialException")) throw new IOException("injected disk failure");
            }
        });
        assertThrows(IOException.class, () -> failing.replay(input, FIXTURE, POLICY, output));
        try (var paths = Files.list(output)) {
            var remaining = paths.toList();
            if (mode.equals("publishCollision")) {
                assertEquals(1, remaining.size()); assertEquals("concurrent writer", Files.readString(remaining.get(0)));
                assertThrows(IOException.class, () -> reports.read(remaining.get(0)));
            } else assertTrue(remaining.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "duplicate", "unknownCase"})
    void invalidRawArtifactCreatesNoOutput(String mode) throws Exception {
        Path input = source(); String text = Files.readString(input);
        if (mode.equals("malformed")) text = "{";
        else if (mode.equals("duplicate")) text = text.replaceFirst("\\{", "{\"caseId\":\"forged\",");
        else { ObjectNode tree = (ObjectNode)json.readTree(text); tree.put("caseId", "other"); text = json.writeValueAsString(tree); }
        Files.writeString(input, text); Path out = directory.resolve("out");
        assertThrows(IOException.class, () -> reports.replay(input, FIXTURE, POLICY, out)); assertFalse(Files.exists(out));
    }

    @ParameterizedTest @ValueSource(strings = {"fixture", "policy", "artifactIdentity"})
    void trustGateErrorsRemainErrorsAfterDiskRoundTrip(String field) throws Exception {
        Path input = source(), fixture = FIXTURE, policy = POLICY;
        if (field.equals("fixture")) fixture = Files.writeString(directory.resolve("fixture.json"), "{}");
        else if (field.equals("policy")) policy = Files.writeString(directory.resolve("policy.json"), "{}");
        else { ObjectNode tree = (ObjectNode)json.readTree(Files.readString(input)); tree.put("datasetSha256", "0".repeat(64)); Files.writeString(input, json.writeValueAsString(tree)); }
        var result = reports.read(reports.replay(input, fixture, policy, directory.resolve("out")));
        assertEquals(CompleteAnswerStatus.ERROR, result.completeAnswerStatus()); assertEquals(Stage.BLOCKED, result.assessmentStage());
    }

    @ParameterizedTest @ValueSource(strings = {"v1", "v2", "rename", "content", "duplicate", "status"})
    void invalidOrOldReportsCannotBeReadAsV3(String mutation) throws Exception {
        Path valid = reports.replay(source(), FIXTURE, POLICY, directory.resolve("out"));
        byte[] bytes = Files.readAllBytes(valid); Path bad;
        if (mutation.equals("rename")) bad = Files.write(directory.resolve("answer-v3.json"), bytes);
        else if (mutation.equals("content")) bad = Files.write(directory.resolve(valid.getFileName()), "{}".getBytes(StandardCharsets.UTF_8));
        else {
            ObjectNode tree = (ObjectNode)json.readTree(bytes);
            if (mutation.equals("v1")) tree = (ObjectNode)tree.path("completenessEvaluation").path("baseEvaluation").path("legacyEvaluation").deepCopy();
            if (mutation.equals("v2")) tree = (ObjectNode)tree.path("completenessEvaluation").path("baseEvaluation").deepCopy();
            tree.put("completeAnswerStatus", mutation.equals("status") ? "FAIL" : "PASS");
            String text = ProcurementEvaluationReports.canonicalJson(tree);
            if (mutation.equals("duplicate")) text = text.replaceFirst("\\{", "{\"completeAnswerStatus\":\"PASS\",");
            bytes = text.getBytes(StandardCharsets.UTF_8); bad = Files.write(directory.resolve(name(bytes)), bytes);
        }
        assertThrows(IOException.class, () -> reports.read(bad)); assertEquals(CompleteAnswerStatus.PASS, reports.read(valid).completeAnswerStatus());
    }

    @Test @EnabledIfSystemProperty(named = "answer.v3.artifact", matches = ".+")
    void savedRuntimeDiskReplayMatchesStep2WithoutChangingInputs() throws Exception {
        Path input = Path.of(System.getProperty("answer.v3.artifact")); byte[] before = Files.readAllBytes(input);
        var artifact = ProcurementEvaluationReports.readArtifact(input);
        var c = ProcurementEvaluationDataset.load().stream().filter(x -> x.caseId().equals(artifact.caseId())).findFirst().orElseThrow();
        var expected = new ProcurementCompleteAnswerEvaluator().evaluate(c, artifact, Files.readAllBytes(FIXTURE), Files.readAllBytes(POLICY));
        Path one = reports.replay(input, FIXTURE, POLICY, directory.resolve("one"));
        Path two = reports.replay(input, FIXTURE, POLICY, directory.resolve("two"));
        assertEquals(expected, reports.read(one)); assertEquals(CompleteAnswerStatus.FAIL, expected.completeAnswerStatus());
        assertArrayEquals(Files.readAllBytes(one), Files.readAllBytes(two)); assertArrayEquals(before, Files.readAllBytes(input));
    }
    private Path source() throws Exception {
        Path path = directory.resolve("artifact.json");
        ProcurementEvaluationReports.write(path, new ProcurementAnswerAggregationTests().good(0, ANSWERS.get(0))); return path;
    }
    @Test void unicodeSpansAndUnresolvedReferencesSurviveDiskRoundTrip() throws Exception {
        String text = ANSWERS.get(0) + "😀𠮷。保证绝不延期。";
        var artifact = new ProcurementAnswerAggregationTests().good(0, text);
        Path input = directory.resolve("unicode.json"); ProcurementEvaluationReports.write(input, artifact);
        var result = reports.read(reports.replay(input, FIXTURE, POLICY, directory.resolve("out")));
        assertNotEquals(CompleteAnswerStatus.PASS, result.completeAnswerStatus());
        for (var fragment : result.assessmentCoverage().fragments())
            assertEquals(fragment.text(), text.substring(fragment.start(), fragment.end()));
        assertEquals(new ProcurementCompleteAnswerEvaluator().evaluate(ProcurementEvaluationDataset.load().get(0), artifact,
                Files.readAllBytes(FIXTURE), Files.readAllBytes(POLICY)), result);
    }
    private String name(byte[] bytes) { return "procurement-answer-v3-" + ProcurementEvaluationReports.sha256(bytes) + ".json"; }
}
