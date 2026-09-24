package com.agent.platform.procurement;

import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static com.agent.platform.procurement.ProcurementAnswerEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementAnswerGraderTests {
    private static final Path FIXTURE = Path.of("data/procurement/scenarios/complex_workstation_01.json");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementAnswerGrader grader = new ProcurementAnswerGrader();
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {
            "推荐 Supplier D，总价 580000 元，交期 12 天。",
            "推荐 Supplier D，总价 58 万元，交期 12 天。",
            "推荐 Supplier D：总价 580000，交期 12 天；备选 Supplier B：总价 550000，交期 18 天。",
            "推荐供应商 Supplier D，单价为 11600 CNY，总价是 58 万人民币，采购数量 50 台，用户要求期限 21 天。",
            "Supplier D 的单价 11600 元，报价交期 12 天。", "推荐 Supplier D，币种 CNY。"
    })
    void supportedFactsAreIndividuallyCorrectButNotCompleteAnswerPass(String answer) throws Exception {
        var result = grade(answer);
        assertFalse(result.claims().isEmpty());
        assertEquals(Status.PASS, result.structuredStatus());
        for (var claim : result.claims()) {
            assertEquals(Status.PASS, claim.factualCorrectness().status(), claim.toString());
            assertEquals(Status.PASS, claim.faithfulness().status(), claim.toString());
            assertEquals(answer.substring(claim.claim().start(), claim.claim().end()), claim.claim().text());
        }
        assertEquals(Status.SKIP, result.completeAnswerStatus());
        assertEquals(Status.SKIP, result.evaluationStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "推荐 Supplier B。", "推荐 Supplier Z。", "备选 Supplier A。",
            "推荐 Supplier D，总价 57 万元。", "推荐 Supplier D，单价 580000 元。",
            "推荐 Supplier D，总价 11600 元。", "推荐 Supplier D，总价 580000 USD。",
            "采购数量 49 台。", "推荐 Supplier D，交期 21 天。",
            "推荐 Supplier D，总价 550000 元，交期 18 天。",
            "推荐 Supplier D，总价 580000.000000000000001 元。",
            "推荐 Supplier D，总价 58 万欧元。", "推荐 Supplier D，币种 USD。"
    })
    void rejectsWrongFactsThroughExtraction(String answer) throws Exception {
        var result = grade(answer);
        assertEquals(Status.PASS, result.structuredStatus(), "structured result remains correct");
        assertEquals(Status.FAIL, result.evaluationStatus(), result.toString());
        assertTrue(result.claims().stream().anyMatch(r -> r.factualCorrectness().status() == Status.FAIL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RFQ 已创建。", "RFQ 创建成功。", "待审批。", "执行失败。", "本阶段仅完成只读推荐。"})
    void stateAssertionsNeedStateEvidenceNotFinalizeOrCompleted(String answer) throws Exception {
        var result = grade(answer);
        assertEquals(Status.PASS, result.structuredStatus());
        assertEquals(Status.SKIP, result.claims().get(0).factualCorrectness().status());
        assertEquals(Status.SKIP, result.claims().get(0).faithfulness().status());
        assertTrue(result.claims().get(0).faithfulness().reason().contains("INSUFFICIENT_STATE_EVIDENCE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "如果批准，RFQ 已创建。", "RFQ 未创建。", "RFQ 没有创建。", "将创建 RFQ。",
            "之前 RFQ 已创建。", "推荐 Supplier D，总价约 58 万元。", "供应商承诺终身保修。",
            "Supplier B 或 Supplier D 总价 550000 元。", "推荐 Supplier D，总价五十八万元。",
            "推荐 Supplier D，交期 12 周。", "总价 58 万元。", "采购数量 50 天。", "", "！！！"
    })
    void unsupportedOrQualifiedClaimsNeverSilentlyPass(String answer) throws Exception {
        var result = grade(answer);
        assertEquals(Status.SKIP, result.evaluationStatus(), result.toString());
        assertTrue(result.claims().stream().anyMatch(r -> !r.claim().unresolvedReason().isBlank()
                && r.factualCorrectness().status() == Status.SKIP && r.faithfulness().status() == Status.SKIP));
        assertNotEquals(Status.PASS, result.completeAnswerStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"推荐 Supplier D，总价 58 万元，如果获得折扣。", "推荐 Supplier D？", "如果批准，推荐 Supplier D，总价 58 万元。"})
    void qualifiersApplyToTheWholeSentence(String answer) throws Exception {
        var result = grade(answer);
        assertTrue(result.claims().stream().allMatch(r -> r.factualCorrectness().status() == Status.SKIP));
    }

    @Test
    void unknownClauseCannotSilentlyTransferSupplierBinding() throws Exception {
        var result = grade("推荐 Supplier D，另一家供应商，总价 550000 元。");
        assertEquals("AMBIGUOUS_SUBJECT", property(result, "TOTAL_PRICE").claim().unresolvedReason());
    }

    @Test
    void mismatchedProductAndFailedToolCannotProvidePriceSupport() throws Exception {
        var a = good("Supplier B 总价 550000 元。");
        a = payload(a, 0, p -> ((ObjectNode) p.path("offers").get(1)).put("productId", "different-product"));
        assertEquals(Status.SKIP, property(grade(a), "TOTAL_PRICE").faithfulness().status());
        a = change(good("Supplier B 总价 550000 元。"), n -> {
            var tool = (ObjectNode) n.path("toolExecutions").get(0);
            tool.put("state", "FAILED");
            ((ObjectNode) tool.path("result")).put("success", false);
        });
        assertEquals(Status.SKIP, property(grade(a), "TOTAL_PRICE").faithfulness().status());
    }

    @Test
    void trueFactWithoutCapturedOfferIsNotFaithfulByDefault() throws Exception {
        var a = good("推荐 Supplier D，总价 58 万元。");
        a = payload(a, 0, p -> p.remove("offers"));
        a = payload(a, 1, p -> ((ObjectNode) p.path("recommendation")).set("selectedOffer", mapper.valueToTree(Map.of("supplierId", "supplier-d"))));
        var result = grade(a);
        var price = property(result, "TOTAL_PRICE");
        assertEquals(Status.PASS, price.factualCorrectness().status());
        assertEquals(Status.SKIP, price.faithfulness().status());
    }

    @Test
    void rawToolTotalIsNotRecomputedAndCannotBecomeGroundTruth() throws Exception {
        var a = good("推荐 Supplier D，总价 57 万元。");
        a = payload(a, 0, p -> ((ObjectNode) p.path("offers").get(3)).put("totalPrice", 570000));
        a = payload(a, 1, p -> ((ObjectNode) p.path("recommendation").path("selectedOffer")).put("totalPrice", 570000));
        var result = grade(a);
        assertEquals(Status.PASS, result.structuredStatus());
        assertEquals(Status.FAIL, property(result, "TOTAL_PRICE").factualCorrectness().status());
        assertEquals(Status.ERROR, property(result, "TOTAL_PRICE").faithfulness().status());
        assertTrue(property(result, "TOTAL_PRICE").faithfulness().reason().contains("INCONSISTENT_RAW_OFFER_TOTAL"));
        Path saved = directory.resolve("raw.json");
        ProcurementEvaluationReports.write(saved, a);
        assertEquals(result, grade(ProcurementEvaluationReports.readArtifact(saved)));
    }

    @Test
    void correctAnswerWithContradictingToolsHasSeparateVerdicts() throws Exception {
        var a = payload(good("推荐 Supplier D，总价 58 万元。"), 0,
                p -> ((ObjectNode) p.path("offers").get(3)).put("totalPrice", 570000));
        var result = grade(a);
        assertEquals(Status.PASS, property(result, "TOTAL_PRICE").factualCorrectness().status());
        assertEquals(Status.ERROR, property(result, "TOTAL_PRICE").faithfulness().status());
    }

    @Test
    void malformedRawNumericEvidenceIsInfrastructureError() throws Exception {
        var a = payload(good("推荐 Supplier D，总价 58 万元。"), 0,
                p -> ((ObjectNode) p.path("offers").get(3)).put("totalPrice", "garbage"));
        var result = grade(a);
        assertEquals(Status.ERROR, result.evaluationStatus());
        assertEquals(Status.PASS, property(result, "TOTAL_PRICE").factualCorrectness().status());
        assertEquals(Status.ERROR, property(result, "TOTAL_PRICE").faithfulness().status());
    }

    @Test
    void fixtureAndCaseTamperingFailClosed() throws Exception {
        assertEquals(Status.ERROR, grader.grade(first(), good("推荐 Supplier D。"), "{}".getBytes()).evaluationStatus());
        var c = first();
        ObjectNode expected = (ObjectNode) c.expected().deepCopy(); expected.put("preferredSupplierId", "supplier-b");
        var tampered = new EvaluationCase(c.schemaVersion(), c.datasetVersion(), c.datasetSha256(), c.fixtureSha256(),
                c.caseId(), c.userMessage(), c.providerFixture(), c.expectedCase(), expected);
        assertEquals(Status.ERROR, grader.grade(tampered, good("推荐 Supplier B。"), Files.readAllBytes(FIXTURE)).evaluationStatus());
        var a = change(good("推荐 Supplier D。"), n -> n.put("caseId", "other"));
        assertEquals(Status.ERROR, grade(a).evaluationStatus());
        a = change(good("推荐 Supplier D。"), n -> ((ObjectNode) n.path("toolExecutions").get(0)).put("runId", "other"));
        assertEquals(Status.ERROR, grade(a).evaluationStatus());
    }

    @Test
    void multipleSuccessfulSearchesStillValidateEveryPayloadIdentity() throws Exception {
        var a = change(good("推荐 Supplier D，总价 58 万元。"), n -> {
            var records = (tools.jackson.databind.node.ArrayNode) n.path("toolExecutions");
            ObjectNode extra = (ObjectNode) records.get(0).deepCopy();
            extra.put("toolCallId", "extra");
            ((ObjectNode) extra.path("request")).put("requestId", "extra");
            var result = (ObjectNode) extra.path("result");
            ObjectNode payload = (ObjectNode) mapper.readTree(result.path("content").asText());
            payload.put("caseVersion", 999);
            result.put("content", mapper.writeValueAsString(payload));
            records.add(extra);
        });
        var result = grade(a);
        assertEquals(Status.ERROR, result.evaluationStatus());
        assertEquals(Status.ERROR, property(result, "TOTAL_PRICE").faithfulness().status());
    }

    @Test
    void sameArtifactDiskReplayIsStableAndDoesNotOverwriteInputs() throws Exception {
        var a = good("推荐 Supplier D：总价 580000，交期 12 天；备选 Supplier B：总价 550000，交期 18 天。本阶段仅完成只读推荐。");
        Path artifact = directory.resolve("artifact.json"), one = directory.resolve("one.json"), two = directory.resolve("two.json");
        ProcurementEvaluationReports.write(artifact, a);
        byte[] before = Files.readAllBytes(artifact);
        var first = grader.replay(first(), artifact, FIXTURE, one);
        var second = grader.replay(first(), artifact, FIXTURE, two);
        assertEquals(first, second);
        assertArrayEquals(Files.readAllBytes(one), Files.readAllBytes(two));
        assertArrayEquals(before, Files.readAllBytes(artifact));
        assertEquals(first, mapper.readValue(Files.readString(one), AnswerEvaluation.class));
        assertThrows(java.io.IOException.class, () -> grader.replay(first(), artifact, FIXTURE, artifact));
        ProcurementEvaluationReports.write(Path.of("target/procurement-evaluation/answer-replay-report.json"), first);
    }

    private ClaimResult property(AnswerEvaluation result, String name) {
        return result.claims().stream().filter(r -> r.claim().property().equals(name)).findFirst().orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"推荐 Supplier D 的方案是否合适", "推荐 Supplier D 吗", "推荐 Supplier D 的保修服务",
            "推荐 Supplier D 为备选方案", "只有批准，推荐 Supplier D", "除非取消，推荐 Supplier D", "难道推荐 Supplier D",
            "如果审批通过；推荐 Supplier D。", "推荐 Supplier D。前提是获得批准。", "推荐 Supplier D，并非如此"})
    void unsupportedRecommendationPrefixCannotPass(String answer) throws Exception {
        var result = grade(answer);
        assertTrue(result.claims().stream().allMatch(r -> r.factualCorrectness().status() == Status.SKIP), result.toString());
        assertTrue(result.claims().stream().allMatch(r -> !r.claim().unresolvedReason().isBlank()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Supplier B 数量 50 台", "Supplier B 用户要求期限 21 天"})
    void explicitSupplierCannotBeSilentlyReboundToCase(String answer) throws Exception {
        var result = grade(answer);
        var claim = result.claims().get(0);
        assertEquals("supplier-b", claim.claim().subject());
        assertEquals("AMBIGUOUS_CASE_VS_SUPPLIER_SUBJECT", claim.claim().unresolvedReason());
        assertEquals(Status.SKIP, claim.factualCorrectness().status());
        assertEquals(Status.SKIP, claim.faithfulness().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"😀𠮷；推荐 Supplier D，总价 58 万元。另有🛰️质保承诺。",
            "推荐 Supplier D 的😀附加服务，总价 58 万元。", "谢谢🙂", " ", "！！", "推荐 Supplier D，总价五十八万元，交期很快。"})
    void utf16SpansAndUnresolvedBusinessTextArePreserved(String answer) throws Exception {
        var result = grade(answer);
        assertEquals(Status.SKIP, result.completeAnswerStatus());
        assertFalse(result.claims().isEmpty());
        for (var row : result.claims()) {
            var claim = row.claim();
            assertTrue(claim.start() >= 0 && claim.end() <= answer.length());
            assertEquals(answer.substring(claim.start(), claim.end()), claim.text());
            assertFalse(claim.start() > 0 && claim.start() < answer.length()
                    && Character.isHighSurrogate(answer.charAt(claim.start() - 1)) && Character.isLowSurrogate(answer.charAt(claim.start())));
            assertFalse(claim.end() > 0 && claim.end() < answer.length()
                    && Character.isHighSurrogate(answer.charAt(claim.end() - 1)) && Character.isLowSurrogate(answer.charAt(claim.end())));
        }
        if (answer.contains("另有")) assertTrue(result.claims().stream().anyMatch(r -> r.claim().text().contains("另有") && r.factualCorrectness().status() == Status.SKIP));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceSnapshot", "sourceAsOf", "sourceDigest"})
    void staleOrConflictingOfferProvenanceCannotSupportCorrectAnswer(String field) throws Exception {
        var a = payload(good("Supplier B 总价 550000 元。"), 0,
                p -> ((ObjectNode) p.path("offers").get(1)).put(field, "wrong-snapshot-or-digest"));
        var result = grade(a);
        assertEquals(Status.PASS, property(result, "TOTAL_PRICE").factualCorrectness().status());
        assertEquals(Status.ERROR, property(result, "TOTAL_PRICE").faithfulness().status());
        assertEquals(Status.ERROR, result.evaluationStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceSnapshot", "sourceDigest", "sourceRecordId", "quantity"})
    void missingOfferBindingDoesNotCountAsSupport(String field) throws Exception {
        var a = payload(good("Supplier B 总价 550000 元。"), 0,
                p -> ((ObjectNode) p.path("offers").get(1)).remove(field));
        var result = grade(a);
        assertEquals(Status.PASS, property(result, "TOTAL_PRICE").factualCorrectness().status());
        assertEquals(Status.SKIP, property(result, "TOTAL_PRICE").faithfulness().status());
    }

    @Test
    void incorrectQuantityAndMissingEvidenceCannotSupportTotal() throws Exception {
        var a = payload(good("Supplier B 总价 550000 元。"), 0,
                p -> ((ObjectNode) p.path("offers").get(1)).put("quantity", 49));
        assertEquals(Status.ERROR, property(grade(a), "TOTAL_PRICE").faithfulness().status());
        a = payload(good("Supplier B 总价 550000 元。"), 0,
                p -> ((tools.jackson.databind.node.ArrayNode) p.path("evidence")).remove(1));
        assertEquals(Status.SKIP, property(grade(a), "TOTAL_PRICE").faithfulness().status());
    }

    @Test
    void internallyConsistentWrongToolFactsStillCannotBecomeGroundTruth() throws Exception {
        var a = good("推荐 Supplier D，总价 57 万元。");
        a = payload(a, 0, p -> ((ObjectNode) p.path("offers").get(3)).put("unitPrice", 11400).put("totalPrice", 570000));
        a = payload(a, 1, p -> ((ObjectNode) p.path("recommendation").path("selectedOffer")).put("unitPrice", 11400).put("totalPrice", 570000));
        var result = grade(a);
        assertEquals(Status.FAIL, property(result, "TOTAL_PRICE").factualCorrectness().status());
        assertEquals("CAPTURED_MATCH_CONFLICTS_WITH_REFERENCE", property(result, "TOTAL_PRICE").faithfulness().reason());
        assertEquals(Status.FAIL, result.evaluationStatus());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
    }

    @Test
    void invalidRecommendationReferencesCannotProvideSupport() throws Exception {
        var a = payload(good("推荐 Supplier D。"), 1, p -> ((ObjectNode) p.path("recommendation"))
                .set("evidenceRefs", mapper.valueToTree(List.of("missing-reference"))));
        var result = grade(a);
        assertEquals(Status.PASS, property(result, "RECOMMENDATION").factualCorrectness().status());
        assertEquals(Status.SKIP, property(result, "RECOMMENDATION").faithfulness().status());
    }

    @Test
    void errorSummaryDoesNotErasePerClaimFailureOrAllowFullPass() throws Exception {
        var a = payload(good("推荐 Supplier D，总价 57 万元。"), 0,
                p -> ((ObjectNode) p.path("offers").get(3)).put("sourceAsOf", "stale"));
        var result = grade(a);
        assertEquals(Status.ERROR, result.evaluationStatus());
        assertEquals(Status.FAIL, property(result, "TOTAL_PRICE").factualCorrectness().status());
        assertEquals(Status.ERROR, property(result, "TOTAL_PRICE").faithfulness().status());
        assertEquals(Status.SKIP, result.completeAnswerStatus());
    }
    private EvaluationCase first() throws Exception { return ProcurementEvaluationDataset.load().get(0); }
    private AnswerEvaluation grade(String answer) throws Exception { return grade(good(answer)); }
    private AnswerEvaluation grade(ExecutionArtifact a) throws Exception { return grader.grade(first(), a, Files.readAllBytes(FIXTURE)); }
    private ExecutionArtifact good(String answer) throws Exception {
        var c = first();
        var state = mapper.treeToValue(c.expectedCase(), ProcurementCaseState.class);
        var current = new ProcurementCase("business-case", "tenant", "conversation", "buyer", ProcurementCaseStatus.values()[0], state, TIME, TIME, 1, "input");
        String fact = "Frozen handcrafted OFFER evidence", snapshot = "scenario:complex_workstation_01";
        List<SupplierEvidence> evidence = new ArrayList<>();
        String selectedEvidenceId = "";
        var offers = mapper.createArrayNode();
        for (var raw : mapper.readTree(Files.readAllBytes(FIXTURE)).path("offers")) {
            ObjectNode offer = (ObjectNode) raw.deepCopy(); offer.put("quantity", 50);
            offer.put("totalPrice", raw.path("unitPrice").decimalValue().multiply(new java.math.BigDecimal("50")));
            String supplier = raw.path("supplierId").asText(), recordId = "complex_workstation_01:" + raw.path("productId").asText();
            offer.put("sourceRecordId", recordId).put("sourceSnapshot", snapshot).put("sourceAsOf", TIME.toString()).put("sourceDigest", "digest");
            String id = EvidenceIdFactory.id(supplier, "OFFER", snapshot, recordId, snapshot, TIME.toString(), "digest", fact);
            evidence.add(new SupplierEvidence(id, supplier, "OFFER", snapshot, fact, TIME, recordId, snapshot, TIME, "digest"));
            if (supplier.equals("supplier-d")) selectedEvidenceId = id;
            offers.add(offer);
        }
        var eligible = List.of(Map.of("supplierId", "supplier-b"), Map.of("supplierId", "supplier-d"));
        var search = Map.of("caseVersion", 1, "eligibleSuppliers", eligible, "evidence", evidence, "offers", offers);
        var recommendation = Map.of("recommendedSupplier", Map.of("supplierId", "supplier-d"), "selectedOffer", offers.get(3),
                "eligibleAlternatives", List.of(Map.of("supplierId", "supplier-b")), "evidenceRefs", List.of(selectedEvidenceId), "tradeoffDimensions", List.of("PRICE", "DELIVERY"));
        var finish = Map.of("caseId", "business-case", "caseVersion", 1, "recommendation", recommendation, "evidence", evidence);
        var runtime = new AgentRuntimeResult("run", "conversation", AgentRunState.COMPLETED, AgentStopReason.COMPLETED, answer, "", null, List.of());
        return ProcurementExecutionArtifacts.capture(c, c.userMessage(), runtime, current,
                List.of(record("search", ProcurementToolCatalog.SUPPLIER_SEARCH, search), record("finalize", ProcurementToolCatalog.RECOMMENDATION_FINALIZE, finish)),
                List.of(), Map.of("executionMode", "HANDCRAFTED", "codeRevision", "test", "fixtureSha256", c.fixtureSha256()));
    }
    private ToolExecutionRecord record(String id, String tool, Object value) {
        return new ToolExecutionRecord(id, "run", tool, ToolExecutionState.SUCCEEDED, new ToolCallRequest(tool, id, Map.of()),
                new ToolCallResult(tool, true, mapper.writeValueAsString(value), "", Map.of()), 1, "", TIME, TIME);
    }
    private ExecutionArtifact change(ExecutionArtifact a, Consumer<ObjectNode> edit) {
        ObjectNode node = mapper.valueToTree(a); edit.accept(node); return mapper.treeToValue(node, ExecutionArtifact.class);
    }
    private ExecutionArtifact payload(ExecutionArtifact a, int index, Consumer<ObjectNode> edit) {
        return change(a, n -> {
            var result = (ObjectNode) n.path("toolExecutions").get(index).path("result");
            ObjectNode payload = (ObjectNode) mapper.readTree(result.path("content").asText()); edit.accept(payload);
            result.put("content", mapper.writeValueAsString(payload));
        });
    }
}
