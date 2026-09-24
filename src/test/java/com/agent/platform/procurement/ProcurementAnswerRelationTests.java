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
import tools.jackson.databind.node.ArrayNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementAnswerRelationTests {
    private static final Path FIXTURE = Path.of("data/procurement/scenarios/complex_workstation_01.json");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementAnswerRelationGrader grader = new ProcurementAnswerRelationGrader();
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"D 比 B 快 6 天。", "Supplier D 比 Supplier B 更快。", "D 的报价交期比 B 的报价交期快 6 天。",
            "D 比 B 贵 3 万元。", "D 的总价比 B 的总价高 30000 CNY。", "B 的总价比 D 低 3 万人民币。",
            "D 的总价满足本次预算。", "D 的总价在本次预算内。", "D 的报价交期满足本次交付期限。"})
    void correctRelationsHaveIndependentFactAndSupportPass(String answer) throws Exception {
        var result = grade(0, good(0, answer));
        assertEquals(Status.PASS, result.relationStatus(), result.toString());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
        var row = result.results().get(0);
        assertEquals(Status.PASS, row.factualDirection().status());
        assertEquals(Status.PASS, row.evidenceDirection().status());
        assertFalse(row.evidenceDirection().evidencePaths().isEmpty());
        assertEquals(answer.substring(row.claim().start(), row.claim().end()), row.claim().text());
    }

    @Test void directionAndMagnitudeAreIndependent() throws Exception {
        var row = grade(0, good(0, "D 比 B 快 5 天。")).results().get(0);
        assertEquals(Status.PASS, row.factualDirection().status());
        assertEquals(Status.FAIL, row.factualDifference().status());
        assertEquals(Status.PASS, row.evidenceDirection().status());
        assertEquals(Status.FAIL, row.evidenceDifference().status());
        row = grade(0, good(0, "D 比 B 便宜 3 万元。")).results().get(0);
        assertEquals(Status.FAIL, row.factualDirection().status());
        assertEquals(Status.PASS, row.factualDifference().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"D 比 B 更慢。", "B 比 D 快 6 天。", "D 比 B 便宜 3 万元。", "D 比 C 快 6 天。",
            "D 比 B 贵 600 元。", "D 比 B 贵 3 万美元。", "D 的总价超出本次预算。", "D 的交期超出本次交付期限。"})
    void wrongRelationsCannotPass(String answer) throws Exception {
        assertEquals(Status.FAIL, grade(0, good(0, answer)).relationStatus());
    }

    @Test void amountNormalizationUsesDecimalNotFloatingPoint() throws Exception {
        var extractor = new ProcurementAnswerRelationExtractor();
        assertEquals(extractor.extract("D 比 B 贵 58 万元").get(0).difference(), extractor.extract("D 比 B 贵 580000 元").get(0).difference());
        assertEquals(Status.FAIL, grade(0, good(0, "D 比 B 贵 58 万元")).relationStatus());
        assertEquals(Status.FAIL, grade(0, good(0, "D 比 B 贵 580000 元")).relationStatus());
        assertEquals(Status.FAIL, grade(0, good(0, "D 比 B 贵 30000.000000000000001 元")).relationStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"D 的单价比 B 高 600 元。", "它比 B 快 6 天。", "D 在全部供应商中最快。",
            "如果批准，D 比 B 快 6 天。", "D 比 B 快 6 天？", "“D 比 B 快 6 天”。", "D 比 B 快 6 天且绝不延期。",
            "D 比 B 快 6 天。以上是引用。", "D 比 B 快 6 天。前提是放宽约束。", "D 比 B 快 6 周。", "D 的用户要求期限比 B 快 6 天。",
            "", "谢谢。", "😀𠮷。", "D 比 B 快 6 天，不过这是猜测。"})
    void unsupportedTextIsRetainedNotCompletePass(String answer) throws Exception {
        var result = grade(0, good(0, answer));
        assertEquals(Status.SKIP, result.relationStatus(), result.toString());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
        assertTrue(result.results().stream().anyMatch(r -> !r.claim().unresolvedReason().isEmpty()));
        for (var row : result.results()) assertEquals(row.claim().text(), answer.substring(row.claim().start(), row.claim().end()));
    }

    @Test void unicodeAndAppendedPromiseKeepBothClauses() throws Exception {
        String answer = "😀𠮷。D 比 B 快 6 天。供应商保证绝不延期。";
        var result = grade(0, good(0, answer));
        assertEquals(3, result.results().size());
        assertEquals(5, result.results().get(1).claim().start());
        assertEquals(Status.PASS, result.results().get(1).factualDirection().status());
        assertEquals(Status.SKIP, result.relationStatus());
        for (var row : result.results()) assertEquals(row.claim().text(), answer.substring(row.claim().start(), row.claim().end()));
    }

    @Test void uniqueAndEmptyUseFrozenCaseNotRanking() throws Exception {
        var single = grade(2, good(2, "当前原始硬约束下仅 D 合格。"));
        assertEquals(Status.PASS, single.relationStatus(), single.toString());
        assertEquals(Status.FAIL, grade(0, good(0, "当前原始硬约束下仅 D 合格。")).relationStatus());
        assertEquals(Status.PASS, grade(3, good(3, "当前原始硬约束下无合格供应商。")).relationStatus());
        assertEquals(Status.FAIL, grade(0, good(0, "当前原始硬约束下无合格供应商。")).relationStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"noSearch", "failedSearch", "failedResult", "missingSet", "wrongSetType", "nullSet", "duplicateSet", "wrongVersion", "wrongRun", "wrongQuantity", "stale", "invalidTotal", "wrongCurrency", "missingSide", "missingProvenance", "wrongOffer", "wrongConstraints", "wrongSet", "foreignCase", "missingOffers", "wrongEvidenceType"})
    void evidenceMutationsNeverBecomeSupportPass(String mutation) throws Exception {
        boolean empty = Set.of("noSearch", "failedSearch", "failedResult", "missingSet", "wrongSetType", "nullSet", "wrongConstraints", "wrongSet", "missingOffers", "wrongEvidenceType").contains(mutation);
        int index = empty ? 3 : 0;
        String answer = empty ? "当前原始硬约束下无合格供应商。" : "D 比 B 贵 3 万元。";
        var a = good(index, answer);
        a = switch (mutation) {
            case "noSearch" -> change(a, n -> n.set("toolExecutions", mapper.createArrayNode()));
            case "failedSearch" -> change(a, n -> {
                ((ObjectNode) n.path("toolExecutions").get(0)).put("state", "FAILED");
                ((ObjectNode) n.path("toolExecutions").get(0).path("result")).put("success", false);
            });
            case "failedResult" -> change(a, n -> ((ObjectNode) n.path("toolExecutions").get(0).path("result")).put("success", false));
            case "wrongRun" -> change(a, n -> ((ObjectNode) n.path("toolExecutions").get(0)).put("runId", "other"));
            case "wrongConstraints" -> change(a, n -> ((ObjectNode) n.path("finalCase").path("state")).set("excludedSuppliers", mapper.valueToTree(List.of())));
            default -> payload(a, p -> {
                var offer = (ObjectNode) p.path("offers").get(1);
                switch (mutation) {
                    case "missingSet" -> p.remove("eligibleSuppliers");
                    case "wrongSetType" -> p.put("eligibleSuppliers", "empty");
                    case "nullSet" -> p.putNull("eligibleSuppliers");
                    case "duplicateSet" -> ((ArrayNode) p.path("eligibleSuppliers")).add(p.path("eligibleSuppliers").get(0).deepCopy());
                    case "wrongVersion" -> p.put("caseVersion", 999);
                    case "wrongQuantity" -> offer.put("quantity", 49);
                    case "stale" -> offer.put("sourceAsOf", "stale");
                    case "invalidTotal" -> offer.put("totalPrice", 1);
                    case "wrongCurrency" -> offer.put("currency", "USD");
                    case "missingSide" -> ((ArrayNode) p.path("offers")).remove(1);
                    case "missingProvenance" -> offer.remove("sourceDigest");
                    case "wrongOffer" -> offer.put("unitPrice", 10000).put("totalPrice", 500000);
                    case "wrongSet" -> p.set("eligibleSuppliers", mapper.valueToTree(List.of(Map.of("supplierId", "supplier-d"))));
                    case "foreignCase" -> p.put("caseId", "other-case");
                    case "missingOffers" -> p.remove("offers");
                    case "wrongEvidenceType" -> p.put("evidence", "invalid");
                    default -> throw new AssertionError(mutation);
                }
            });
        };
        var result = grade(index, a);
        if (Set.of("wrongRun", "failedResult").contains(mutation)) { assertEquals(Status.ERROR, result.relationStatus()); return; }
        var row = result.results().get(0);
        assertEquals(Status.PASS, row.factualDirection().status(), result.toString());
        Status expected = Set.of("noSearch", "failedSearch", "failedResult", "missingSet", "missingSide", "missingProvenance", "missingOffers").contains(mutation)
                ? Status.SKIP : Set.of("wrongCurrency", "wrongOffer", "wrongSet").contains(mutation) ? Status.FAIL : Status.ERROR;
        assertEquals(expected, row.evidenceDirection().status(), result.toString());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
    }

    @Test void truthFailureSurvivesEvidenceError() throws Exception {
        var a = payload(good(0, "D 比 B 慢 6 天。"), p -> ((ObjectNode) p.path("offers").get(1)).put("sourceAsOf", "stale"));
        var result = grade(0, a);
        assertEquals(Status.ERROR, result.relationStatus());
        assertEquals(Status.FAIL, result.results().get(0).factualDirection().status());
        assertEquals(Status.ERROR, result.results().get(0).evidenceDirection().status());
    }

    @Test void matchingWrongToolDifferenceIsNotGroundTruth() throws Exception {
        var a = payload(good(0, "D 比 B 快 5 天。"), p -> ((ObjectNode) p.path("offers").get(1)).put("leadTimeDays", 17));
        var row = grade(0, a).results().get(0);
        assertEquals(Status.FAIL, row.factualDifference().status());
        assertEquals(Status.FAIL, row.evidenceDifference().status());
    }

    @Test void equalDifferenceFromTwoWrongOperandsStillLacksValidSupport() throws Exception {
        var a = payload(good(0, "D 比 B 快 6 天。"), p -> {
            ((ObjectNode) p.path("offers").get(1)).put("leadTimeDays", 19);
            ((ObjectNode) p.path("offers").get(3)).put("leadTimeDays", 13);
        });
        var row = grade(0, a).results().get(0);
        assertEquals(Status.PASS, row.factualDifference().status());
        assertEquals(Status.FAIL, row.evidenceDifference().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicateKey", "trailingDocument"})
    void ambiguousToolJsonDoesNotSupportRelations(String variant) throws Exception {
        var a = change(good(0, "D 比 B 快 6 天。"), n -> {
            var result = (ObjectNode) n.path("toolExecutions").get(0).path("result");
            String json = result.path("content").asText();
            result.put("content", variant.equals("trailingDocument") ? json + "{}"
                    : json.replace("\"caseVersion\":", "\"caseVersion\":1,\"caseVersion\":"));
        });
        var result = grade(0, a);
        assertEquals(Status.ERROR, result.relationStatus());
        assertEquals(Status.PASS, result.results().get(0).factualDirection().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"completeAnswerStatus", "relationStatus", "extractorVersion", "graderVersion"})
    void serializedContractCannotUpgradeStatusOrChangeVersions(String field) throws Exception {
        ObjectNode raw = mapper.valueToTree(grade(0, good(0, "D 比 B 快 5 天。")));
        raw.put(field, field.endsWith("Status") ? "PASS" : "future");
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(raw, ProcurementAnswerRelationGrader.Evaluation.class));
    }

    @Test void identityAndFixtureAreValidated() throws Exception {
        var c = definition(0); var a = good(0, "D 比 B 快 6 天。");
        assertEquals(Status.ERROR, grader.grade(c, a, new byte[0]).relationStatus());
        assertEquals(Status.ERROR, grade(1, a).relationStatus());
        assertEquals(Status.ERROR, grade(0, change(a, n -> n.put("datasetSha256", "wrong"))).relationStatus());
    }

    @Test void offlineReplayIsStableAndPreservesOldContracts() throws Exception {
        var c = definition(0); var a = good(0, "D 比 B 快 6 天。");
        Path artifact = directory.resolve("artifact.json"), oldPath = directory.resolve("old.json"), out = directory.resolve("relations.json");
        var old = new ProcurementAnswerGrader().grade(c, a, Files.readAllBytes(FIXTURE));
        var v2 = new ProcurementAnswerCoverageEvaluator().evaluate(c, a, Files.readAllBytes(FIXTURE), ProcurementAnswerPolicy.resourceBytes());
        Path v2Path = directory.resolve("v2.json"); ProcurementEvaluationReports.write(v2Path, v2);
        byte[] v2Bytes = Files.readAllBytes(v2Path);
        var coverage = new ProcurementAnswerCoverage().analyze(a.runtime().answer());
        ProcurementEvaluationReports.write(artifact, a); ProcurementEvaluationReports.write(oldPath, old);
        byte[] original = Files.readAllBytes(artifact), oldBytes = Files.readAllBytes(oldPath);
        var one = grader.replay(c, artifact, FIXTURE, out);
        Path two = directory.resolve("again.json");
        assertEquals(one, grader.replay(c, artifact, FIXTURE, two));
        assertArrayEquals(Files.readAllBytes(out), Files.readAllBytes(two));
        assertEquals(one, mapper.readValue(Files.readString(out), ProcurementAnswerRelationGrader.Evaluation.class));
        assertEquals(old, new ProcurementAnswerGrader().grade(c, a, Files.readAllBytes(FIXTURE)));
        assertEquals(coverage, new ProcurementAnswerCoverage().analyze(a.runtime().answer()));
        assertEquals(v2, new ProcurementAnswerCoverageEvaluator().evaluate(c, a, Files.readAllBytes(FIXTURE), ProcurementAnswerPolicy.resourceBytes()));
        for (Path path : List.of(artifact, oldPath, v2Path, out, FIXTURE)) assertThrows(FileAlreadyExistsException.class, () -> grader.replay(c, artifact, FIXTURE, path));
        assertArrayEquals(original, Files.readAllBytes(artifact)); assertArrayEquals(oldBytes, Files.readAllBytes(oldPath));
        assertArrayEquals(v2Bytes, Files.readAllBytes(v2Path));
    }

    @Test
    @EnabledIfSystemProperty(named = "relation.artifact", matches = ".+")
    void replayActualHistoricalArtifactWithoutExecutingAnything() throws Exception {
        Path source = Path.of(System.getProperty("relation.artifact")); byte[] before = Files.readAllBytes(source);
        var a = ProcurementEvaluationReports.readArtifact(source);
        var c = ProcurementEvaluationDataset.load().stream().filter(d -> d.caseId().equals(a.caseId())).findFirst().orElseThrow();
        var result = grader.replay(c, source, FIXTURE, directory.resolve("history.json"));
        assertNotEquals(Status.ERROR, result.relationStatus(), result.toString());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
        assertEquals(result, grader.replay(c, source, FIXTURE, directory.resolve("history-again.json")));
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "budget", "currency", "requiredDeliveryDays", "hardConstraints", "preferences", "excludedSuppliers", "productCategory"})
    void missingEligibilityCannotBypassCaseRequirementGate(String field) throws Exception {
        var a = payload(good(0, "D 比 B 快 6 天。"), p -> p.remove("eligibleSuppliers"));
        a = change(a, n -> {
            var state = (ObjectNode) n.path("finalCase").path("state");
            switch (field) {
                case "quantity" -> state.put(field, 49);
                case "budget" -> state.put(field, 1);
                case "currency" -> state.put(field, "USD");
                case "requiredDeliveryDays" -> state.put(field, 1);
                case "hardConstraints", "preferences" -> state.set(field, mapper.createObjectNode());
                case "excludedSuppliers" -> state.set(field, mapper.createArrayNode());
                default -> state.put(field, "");
            }
        });
        var result = grade(0, a);
        assertEquals(Status.ERROR, result.relationStatus(), result.toString());
        assertEquals(Status.PASS, result.results().get(0).factualDirection().status());
        assertEquals(Status.ERROR, result.results().get(0).evidenceDirection().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unresolvedPass", "directionNA", "differencePass", "span"})
    void malformedClaimResultsCannotDeserializeAsPassing(String mutation) throws Exception {
        var result = grade(0, good(0, mutation.equals("unresolvedPass") ? "供应商保证绝不延期。" : "D 比 B 更快。"));
        ObjectNode raw = mapper.valueToTree(result);
        var row = (ObjectNode) raw.path("results").get(0);
        switch (mutation) {
            case "unresolvedPass" -> {
                for (String key : List.of("factualDirection", "factualDifference", "evidenceDirection", "evidenceDifference"))
                    ((ObjectNode) row.path(key)).put("status", "PASS");
                raw.put("relationStatus", "PASS");
            }
            case "directionNA" -> ((ObjectNode) row.path("factualDirection")).put("status", "NOT_APPLICABLE");
            case "differencePass" -> ((ObjectNode) row.path("factualDifference")).put("status", "PASS");
            case "span" -> ((ObjectNode) row.path("claim")).put("start", 1);
        }
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(raw, ProcurementAnswerRelationGrader.Evaluation.class));
    }

    @Test void inconsistentMultipleSearchesAreErrorsNotEmptySet() throws Exception {
        var a = change(good(3, "当前原始硬约束下无合格供应商。"), n -> {
            var records = (ArrayNode) n.path("toolExecutions");
            var second = (ObjectNode) records.get(0).deepCopy();
            second.put("toolCallId", "search-2"); ((ObjectNode) second.path("request")).put("requestId", "search-2");
            var result = (ObjectNode) second.path("result");
            var payload = (ObjectNode) mapper.readTree(result.path("content").asText());
            payload.set("eligibleSuppliers", mapper.valueToTree(List.of(Map.of("supplierId", "supplier-d"))));
            result.put("content", mapper.writeValueAsString(payload)); records.add(second);
        });
        var result = grade(3, a);
        assertEquals(Status.PASS, result.results().get(0).factualDirection().status());
        assertEquals(Status.ERROR, result.results().get(0).evidenceDirection().status());
    }

    private EvaluationCase definition(int i) throws Exception { return ProcurementEvaluationDataset.load().get(i); }
    private ProcurementAnswerRelationGrader.Evaluation grade(int i, ExecutionArtifact a) throws Exception { return grader.grade(definition(i), a, Files.readAllBytes(FIXTURE)); }
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
