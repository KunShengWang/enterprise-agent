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

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementDeterministicGraderTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcurementDeterministicGrader grader = new ProcurementDeterministicGrader();
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    @TempDir Path directory;

    @Test
    void allFrozenCasesPassStructuredChecksButNeverClaimAnswerCorrectness() throws Exception {
        var cases = ProcurementEvaluationDataset.load();
        assertEquals(4, cases.size());
        List<ExecutionArtifact> artifacts = new ArrayList<>();
        for (var c : cases) {
            var a = good(c);
            artifacts.add(a);
            var result = grader.grade(c, a);
            assertEquals(Status.PASS, result.structuredStatus(), result.toString());
            assertMetric(result, "finalAnswerCorrectness", Status.SKIP);
            assertMetric(result, "finalAnswerFaithfulness", Status.SKIP);
            assertMetric(result, "hitlCompliance", Status.SKIP);
            assertMetric(result, "evidenceGrounding", c.expected().path("status").asText().equals("NO_ELIGIBLE")
                    ? Status.NOT_APPLICABLE : Status.PASS);
        }
        var report = ProcurementEvaluationReports.regrade(cases, artifacts);
        ProcurementEvaluationReports.write(Path.of("target/procurement-evaluation/handcrafted-report.json"), report);
        assertTrue(report.scope().contains("STRUCTURED_CHECKS_ONLY"));
        assertEquals(2L, report.checkCounts().get(Status.NOT_APPLICABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "budget", "currency", "requiredDeliveryDays", "hardConstraints", "preferences", "excludedSuppliers"})
    void rejectsEveryStructuredRequirementMismatch(String field) throws Exception {
        var c = first();
        var bad = change(good(c), node -> {
            ObjectNode state = (ObjectNode) node.path("finalCase").path("state");
            switch (field) {
                case "quantity" -> state.put(field, 49);
                case "budget" -> state.put(field, 599999);
                case "currency" -> state.put(field, "USD");
                case "requiredDeliveryDays" -> state.put(field, 20);
                case "hardConstraints", "preferences" -> state.set(field, mapper.createObjectNode());
                case "excludedSuppliers" -> state.set(field, mapper.createArrayNode());
            }
        });
        assertMetric(grader.grade(c, bad), "requirement." + field, Status.FAIL);
    }

    @Test
    void preservesV2PresenceOnlyTextPolicyWithExplicitSemanticSkip() throws Exception {
        var c = first();
        var altered = change(good(c), node -> ((ObjectNode) node.path("finalCase").path("state"))
                .put("productCategory", "different text"));
        assertMetric(grader.grade(c, altered), "requirement.productCategoryPresent", Status.PASS);
        assertMetric(grader.grade(c, altered), "productTextSemantics", Status.SKIP);
        var blank = change(altered, node -> ((ObjectNode) node.path("finalCase").path("state"))
                .put("productCategory", ""));
        assertMetric(grader.grade(c, blank), "requirement.productCategoryPresent", Status.FAIL);
    }

    @Test
    void rejectsWrongEligibleSupplierRecommendationAndTradeoffs() throws Exception {
        var c = first();
        var a = good(c);
        assertMetric(grader.grade(c, changePayload(a, 0, payload -> payload.set("eligibleSuppliers",
                mapper.valueToTree(List.of(Map.of("supplierId", "supplier-c")))))), "eligibility", Status.FAIL);
        assertMetric(grader.grade(c, changePayload(a, 1, payload ->
                ((ObjectNode) payload.path("recommendation").path("recommendedSupplier")).put("supplierId", "supplier-c"))),
                "recommendationOutcome", Status.FAIL);
        assertMetric(grader.grade(c, changePayload(a, 1, payload ->
                ((ObjectNode) payload.path("recommendation").path("selectedOffer")).put("supplierId", "supplier-b"))),
                "recommendationOutcome", Status.FAIL);
        assertMetric(grader.grade(c, changePayload(a, 1, payload ->
                ((ObjectNode) payload.path("recommendation")).set("tradeoffDimensions", mapper.createArrayNode()))),
                "tradeoffDimensions", Status.FAIL);
    }

    @Test
    void rejectsUnknownEvidenceWrongSupplierAndTamperedProvenance() throws Exception {
        var c = first();
        var a = good(c);
        assertMetric(grader.grade(c, changePayload(a, 1, payload ->
                ((ObjectNode) payload.path("recommendation")).set("evidenceRefs", mapper.valueToTree(List.of("unknown"))))),
                "evidenceGrounding", Status.FAIL);
        for (String field : List.of("supplierId", "sourceSnapshot", "sourceDigest", "fact")) {
            assertMetric(grader.grade(c, changePayload(a, 1, payload ->
                    ((ObjectNode) payload.path("evidence").get(0)).put(field, "tampered"))),
                    "evidenceGrounding", Status.FAIL);
        }
    }

    @Test
    void failedFinalizerIsNotSuccessAndNoEligibleCannotFinalize() throws Exception {
        var c = first();
        var failed = change(good(c), node -> {
            ((ObjectNode) node.path("toolExecutions").get(1)).put("state", "FAILED");
            ((ObjectNode) node.path("toolExecutions").get(1).path("result")).put("success", false);
        });
        assertMetric(grader.grade(c, failed), "recommendationOutcome", Status.FAIL);
        var noEligible = ProcurementEvaluationDataset.load().get(3);
        var original = good(noEligible);
        var tools = new ArrayList<>(original.toolExecutions());
        tools.add(good(c).toolExecutions().get(1));
        var bad = new ExecutionArtifact(SCHEMA, original.caseId(), original.datasetSha256(), original.userMessage(),
                original.runtime(), original.finalCase(), tools, original.observedEvents(), original.metadata(), original.captureLimitations());
        assertMetric(grader.grade(noEligible, bad), "recommendationOutcome", Status.FAIL);
    }

    @Test
    void successfulSearchMultiplicityAndNonCompletedRuntimeRemainHardFailures() throws Exception {
        var c = first();
        var a = good(c);
        var duplicated = change(a, node -> {
            var records = (tools.jackson.databind.node.ArrayNode) node.path("toolExecutions");
            ObjectNode extra = (ObjectNode) records.get(0).deepCopy();
            extra.put("toolCallId", "search-again");
            ((ObjectNode) extra.path("request")).put("requestId", "search-again");
            records.add(extra);
        });
        assertMetric(grader.grade(c, duplicated), "eligibility", Status.FAIL);
        var stopped = change(a, n -> ((ObjectNode) n.path("runtime")).put("state", "FAILED"));
        assertMetric(grader.grade(c, stopped), "runtimeCompletion", Status.FAIL);
    }

    @Test
    void malformedCaseDoesNotPreventOtherCaseResultsAndUnknownSchemaIsRejected() throws Exception {
        var cases = ProcurementEvaluationDataset.load();
        var broken = change(good(cases.get(0)), n -> n.putNull("finalCase"));
        var report = ProcurementEvaluationReports.regrade(cases,
                List.of(broken, good(cases.get(1)), good(cases.get(2)), good(cases.get(3))));
        assertEquals(4, report.results().size());
        assertEquals(Status.ERROR, report.results().get(0).structuredStatus());
        assertEquals(Status.PASS, report.results().get(1).structuredStatus());
        var unknown = change(good(cases.get(0)), n -> n.put("schemaVersion", "future"));
        Path path = directory.resolve("future.json");
        ProcurementEvaluationReports.write(path, unknown);
        assertThrows(java.io.IOException.class, () -> ProcurementEvaluationReports.readArtifact(path));
    }

    @Test
    void missingMalformedAndCrossRunEvidenceAreErrorsNotBusinessFailures() throws Exception {
        var c = first();
        var a = good(c);
        for (var bad : List.of(
                change(a, n -> n.putNull("finalCase")),
                change(a, n -> n.putNull("toolExecutions")),
                change(a, n -> n.put("datasetSha256", "wrong")),
                change(a, n -> n.put("userMessage", "different input")),
                change(a, n -> ((ObjectNode) n.path("toolExecutions").get(0)).put("runId", "other-run")),
                change(a, n -> ((ObjectNode) n.path("toolExecutions").get(0).path("result")).put("content", "not-json")))) {
            assertEquals(Status.ERROR, grader.grade(c, bad).structuredStatus());
        }
        var missing = ProcurementEvaluationReports.regrade(List.of(c), List.of());
        assertEquals(Status.ERROR, missing.results().get(0).structuredStatus());
    }

    @Test
    void immutableSnapshotRoundTripRegradesWithoutAnyExecutionAndRetainsObservations() throws Exception {
        var c = first();
        var original = good(c);
        var events = List.of(
                new AgentEvent("request", "run", "conversation", 1, AgentEventType.TOOL_REQUESTED, "",
                        Map.of("toolCallId", "rejected", "toolName", "forbidden", "arguments", Map.of("quantity", 99)), TIME),
                new AgentEvent("policy", "run", "conversation", 2, AgentEventType.POLICY_DECIDED, "blocked",
                        Map.of("toolCallId", "rejected", "action", "BLOCK"), TIME));
        var a = new ExecutionArtifact(SCHEMA, c.caseId(), c.datasetSha256(), c.userMessage(), original.runtime(),
                original.finalCase(), original.toolExecutions(), events, original.metadata(), original.captureLimitations());
        Path file = directory.resolve("artifact.json");
        ProcurementEvaluationReports.write(file, a);
        var loaded = ProcurementEvaluationReports.readArtifact(file);
        assertEquals(a.runtime().answer(), loaded.runtime().answer());
        assertEquals(events, loaded.observedEvents());
        assertEquals(2, loaded.toolExecutions().size()); // rejected request never turned into an execution
        assertEquals(grader.grade(c, a), grader.grade(c, loaded));
        assertEquals(grader.grade(c, loaded), grader.grade(c, loaded));
        String before = ProcurementEvaluationReports.artifactHash(loaded);
        var report = ProcurementEvaluationReports.regrade(List.of(c), List.of(loaded));
        assertEquals(before, ProcurementEvaluationReports.artifactHash(loaded));
        ProcurementEvaluationReports.write(directory.resolve("report.json"), report);
        var reread = mapper.readValue(java.nio.file.Files.readString(directory.resolve("report.json")), Report.class);
        assertEquals(report, reread);
    }

    @Test
    void graderChangesCannotMasqueradeAsModelChanges() throws Exception {
        var c = first();
        var a = good(c);
        var baseline = ProcurementEvaluationReports.regrade(List.of(c), List.of(a));
        assertEquals("REPLAY_CHECK", ProcurementEvaluationReports.compare(baseline, baseline).kind());
        var row = baseline.results().get(0);
        var upgraded = new EvaluationResult(row.caseId(), row.artifactSha256(), "future-v3", row.structuredStatus(), row.checks());
        var changedGrader = new Report(SCHEMA, baseline.datasetVersion(), baseline.datasetSha256(), baseline.fixtureSha256(), "future-v3",
                baseline.scope(), List.of(upgraded), baseline.checkCounts());
        assertEquals("GRADER_CHANGE_ON_SAME_ARTIFACTS", ProcurementEvaluationReports.compare(baseline, changedGrader).kind());
        var bad = change(a, n -> ((ObjectNode) n.path("finalCase").path("state")).put("quantity", 49));
        var changedArtifact = ProcurementEvaluationReports.regrade(List.of(c), List.of(bad));
        assertTrue(ProcurementEvaluationReports.compare(baseline, changedArtifact).changes().stream()
                .anyMatch(s -> s.contains("requirement.quantity: PASS -> FAIL")));
        assertEquals("INCOMPARABLE", ProcurementEvaluationReports.compare(changedArtifact, changedGrader).kind());
    }

    @Test
    void unorderedBusinessSetsAndReportMapsDoNotChangeSerializedReplay() throws Exception {
        var c = ProcurementEvaluationDataset.load().get(2);
        var a = good(c);
        var reordered = change(a, n -> ((ObjectNode) n.path("finalCase").path("state"))
                .set("excludedSuppliers", mapper.valueToTree(List.of("Supplier B", "Supplier A"))));
        assertEquals(grader.grade(c, a), grader.grade(c, reordered));
        var report = ProcurementEvaluationReports.regrade(List.of(c), List.of(a));
        var reversed = new LinkedHashMap<Status, Long>();
        var statuses = new ArrayList<>(List.of(Status.values()));
        Collections.reverse(statuses);
        statuses.forEach(s -> reversed.put(s, report.checkCounts().get(s)));
        var other = new Report(report.schemaVersion(), report.datasetVersion(), report.datasetSha256(), report.fixtureSha256(),
                report.graderVersion(), report.scope(), report.results(), reversed);
        Path one = directory.resolve("one.json"), two = directory.resolve("two.json");
        ProcurementEvaluationReports.write(one, report);
        ProcurementEvaluationReports.write(two, other);
        assertEquals(java.nio.file.Files.readString(one), java.nio.file.Files.readString(two));
    }

    private EvaluationCase first() throws Exception { return ProcurementEvaluationDataset.load().get(0); }

    @Test
    void missingEmptyAndUnknownInputsNeverPass() throws Exception {
        var c = first();
        assertEquals(Status.ERROR, grader.grade(c, null).structuredStatus());
        assertEquals(Status.ERROR, grader.grade(null, good(c)).structuredStatus());
        for (var bad : List.of(
                change(good(c), n -> ((ObjectNode) n.path("runtime")).putNull("state")),
                change(good(c), n -> ((ObjectNode) n.path("runtime")).put("runId", "")),
                change(good(c), n -> ((ObjectNode) n.path("runtime")).put("stopReason", "MODEL_ERROR")),
                change(good(c), n -> ((ObjectNode) n.path("toolExecutions").get(1)).putNull("result")))) {
            assertEquals(Status.ERROR, grader.grade(c, bad).structuredStatus());
        }
        var single = ProcurementEvaluationDataset.load().get(2);
        var missingDimensions = changePayload(good(single), 1,
                p -> ((ObjectNode) p.path("recommendation")).remove("tradeoffDimensions"));
        assertEquals(Status.ERROR, grader.grade(single, missingDimensions).structuredStatus());
        var blankRef = changePayload(good(c), 1,
                p -> ((ObjectNode) p.path("recommendation")).set("evidenceRefs", mapper.valueToTree(List.of(""))));
        assertEquals(Status.ERROR, grader.grade(c, blankRef).structuredStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"case", "dataset", "fixture", "tenant", "user", "businessCase", "session", "eventRun", "eventSession", "payloadCase", "payloadVersion"})
    void rejectsMixedEvidenceIdentities(String target) throws Exception {
        var c = first();
        var a = good(c);
        ExecutionArtifact bad;
        if (target.startsWith("payload")) {
            bad = changePayload(a, 1, p -> {
                if (target.equals("payloadCase")) p.put("caseId", "other");
                else p.put("caseVersion", 99);
            });
        } else {
            bad = change(a, n -> {
                switch (target) {
                    case "case" -> n.put("caseId", "other");
                    case "dataset" -> n.put("datasetSha256", "0".repeat(64));
                    case "fixture" -> ((ObjectNode) n.path("metadata")).put("fixtureSha256", "0".repeat(64));
                    case "tenant" -> ((ObjectNode) n.path("finalCase")).put("tenantId", "other");
                    case "user" -> ((ObjectNode) n.path("finalCase")).put("userId", "other");
                    case "businessCase" -> ((ObjectNode) n.path("finalCase")).put("caseId", "other");
                    case "session" -> ((ObjectNode) n.path("finalCase")).put("conversationId", "other");
                    default -> n.set("observedEvents", mapper.valueToTree(List.of(new AgentEvent("event",
                            target.equals("eventRun") ? "other" : "run",
                            target.equals("eventSession") ? "other" : "conversation", 1,
                            AgentEventType.TOOL_REQUESTED, "", Map.of(), TIME))));
                }
            });
        }
        assertEquals(Status.ERROR, grader.grade(c, bad).structuredStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"timestamp", "toolState", "unknownToolState", "unknownRunState", "currency", "preferences", "answer", "success", "fractionalQuantity"})
    void rawReaderRejectsDefaultableOrUnknownFieldsBeforeProductionConstructors(String target) throws Exception {
        ObjectNode raw = mapper.valueToTree(good(first()));
        ObjectNode tool = (ObjectNode) raw.path("toolExecutions").get(0);
        ObjectNode state = (ObjectNode) raw.path("finalCase").path("state");
        switch (target) {
            case "timestamp" -> tool.remove("createdAt");
            case "toolState" -> tool.putNull("state");
            case "unknownToolState" -> tool.put("state", "NOT_A_STATE");
            case "unknownRunState" -> ((ObjectNode) raw.path("runtime")).put("state", "NOT_A_STATE");
            case "currency" -> state.remove("currency");
            case "preferences" -> state.putNull("preferences");
            case "answer" -> ((ObjectNode) raw.path("runtime")).remove("answer");
            case "success" -> ((ObjectNode) tool.path("result")).remove("success");
            case "fractionalQuantity" -> state.put("quantity", 50.9);
        }
        Path file = directory.resolve("invalid.json");
        ProcurementEvaluationReports.write(file, raw);
        assertThrows(java.io.IOException.class, () -> ProcurementEvaluationReports.readArtifact(file));
    }

    @Test
    void relocatingSameArtifactDoesNotInjectPathsOrTimestamps() throws Exception {
        var c = first();
        var a = good(c);
        Path one = directory.resolve("one.json"), two = directory.resolve("nested/two.json");
        ProcurementEvaluationReports.write(one, a);
        ProcurementEvaluationReports.write(two, a);
        var first = ProcurementEvaluationReports.readArtifact(one);
        var second = ProcurementEvaluationReports.readArtifact(two);
        assertEquals(grader.grade(c, first), grader.grade(c, second));
        assertEquals(a.toolExecutions().get(0).createdAt(), second.toolExecutions().get(0).createdAt());
        assertEquals(java.nio.file.Files.readString(one), java.nio.file.Files.readString(two));
    }

    @Test
    void comparatorRejectsInvalidCountsAndCannotTurnSkippedChecksIntoPass() throws Exception {
        var c = first();
        var report = ProcurementEvaluationReports.regrade(List.of(c), List.of(good(c)));
        var badCounts = new EnumMap<Status, Long>(Status.class);
        badCounts.putAll(report.checkCounts());
        badCounts.put(Status.PASS, 999L);
        var bad = new Report(SCHEMA, report.datasetVersion(), report.datasetSha256(), report.fixtureSha256(),
                report.graderVersion(), report.scope(), report.results(), badCounts);
        assertThrows(IllegalArgumentException.class, () -> ProcurementEvaluationReports.compare(report, bad));
        assertEquals(Status.SKIP, ProcurementEvaluationReports.aggregate(List.of(new Check("answer", Status.SKIP, "", List.of()))));
        assertEquals(Status.NOT_APPLICABLE, ProcurementEvaluationReports.aggregate(List.of(new Check("evidence", Status.NOT_APPLICABLE, "", List.of()))));
        var row = report.results().get(0);
        var wrongStatus = new EvaluationResult(row.caseId(), row.artifactSha256(), row.graderVersion(), Status.FAIL, row.checks());
        var inconsistent = new Report(SCHEMA, report.datasetVersion(), report.datasetSha256(), report.fixtureSha256(),
                report.graderVersion(), report.scope(), List.of(wrongStatus), report.checkCounts());
        assertThrows(IllegalArgumentException.class, () -> ProcurementEvaluationReports.compare(report, inconsistent));
        var otherFixture = new Report(SCHEMA, report.datasetVersion(), report.datasetSha256(), "0".repeat(64),
                report.graderVersion(), report.scope(), report.results(), report.checkCounts());
        assertEquals("INCOMPARABLE", ProcurementEvaluationReports.compare(report, otherFixture).kind());
        var empty = new Report(SCHEMA, report.datasetVersion(), report.datasetSha256(), report.fixtureSha256(),
                report.graderVersion(), report.scope(), List.of(), report.checkCounts());
        assertThrows(IllegalArgumentException.class, () -> ProcurementEvaluationReports.compare(empty, empty));
    }

    @Test
    void searchEvidenceMustAlsoContainValidCollectionTimestamp() throws Exception {
        var c = first();
        var malformed = changePayload(good(c), 0,
                p -> ((ObjectNode) p.path("evidence").get(0)).remove("collectedAt"));
        assertMetric(grader.grade(c, malformed), "evidenceGrounding", Status.FAIL);
    }

    @Test
    void changedScoresForSameArtifactAndGraderAreAnInconsistentReplay() throws Exception {
        var c = first();
        var baseline = ProcurementEvaluationReports.regrade(List.of(c), List.of(good(c)));
        var row = baseline.results().get(0);
        var checks = new ArrayList<>(row.checks());
        var previous = checks.get(0);
        checks.set(0, new Check(previous.metric(), Status.FAIL, "changed verdict", previous.evidencePaths()));
        var alteredRow = new EvaluationResult(row.caseId(), row.artifactSha256(), row.graderVersion(), Status.FAIL, checks);
        var counts = new EnumMap<Status, Long>(Status.class);
        counts.putAll(baseline.checkCounts());
        counts.merge(Status.PASS, -1L, Long::sum);
        counts.merge(Status.FAIL, 1L, Long::sum);
        var altered = new Report(SCHEMA, baseline.datasetVersion(), baseline.datasetSha256(), baseline.fixtureSha256(),
                baseline.graderVersion(), baseline.scope(), List.of(alteredRow), counts);
        assertEquals("INCONSISTENT_REPLAY", ProcurementEvaluationReports.compare(baseline, altered).kind());
    }

    private ExecutionArtifact good(EvaluationCase c) {
        var state = mapper.treeToValue(c.expectedCase(), ProcurementCaseState.class);
        var current = new ProcurementCase("business-case", "tenant", "conversation", "buyer",
                ProcurementCaseStatus.values()[0], state, TIME, TIME, 1, "input");
        String supplier = c.expected().path("preferredSupplierId").asText("supplier-d");
        String fact = "Frozen handcrafted OFFER evidence";
        String snapshot = "scenario:" + c.providerFixture().path("scenarioId").asText();
        String id = EvidenceIdFactory.id(supplier, "OFFER", "fixture", "record", snapshot, TIME.toString(), "digest", fact);
        var evidence = new SupplierEvidence(id, supplier, "OFFER", "fixture", fact, TIME, "record", snapshot, TIME, "digest");
        List<Map<String, String>> eligible = new ArrayList<>();
        c.expected().path("eligibleSupplierIds").forEach(v -> eligible.add(Map.of("supplierId", v.asText())));
        List<ToolExecutionRecord> tools = new ArrayList<>();
        tools.add(record("search", ProcurementToolCatalog.SUPPLIER_SEARCH,
                Map.of("caseVersion", 1, "eligibleSuppliers", eligible, "evidence", List.of(evidence))));
        if ("RECOMMENDABLE".equals(c.expected().path("status").asText())) {
            tools.add(record("finalize", ProcurementToolCatalog.RECOMMENDATION_FINALIZE,
                    Map.of("caseId", "business-case", "caseVersion", 1, "recommendation", Map.of("recommendedSupplier", Map.of("supplierId", supplier),
                            "selectedOffer", Map.of("supplierId", supplier), "evidenceRefs", List.of(id),
                            "tradeoffDimensions", c.expected().path("requiredTradeoffDimensions")), "evidence", List.of(evidence))));
        }
        var runtime = new AgentRuntimeResult("run", "conversation", AgentRunState.COMPLETED, AgentStopReason.COMPLETED,
                "Deliberately unverified final answer: RFQ already created", "", null, List.of());
        return ProcurementExecutionArtifacts.capture(c, c.userMessage(), runtime, current, tools, List.of(),
                Map.of("executionMode", "HANDCRAFTED", "codeRevision", "test", "fixtureSha256", c.fixtureSha256()));
    }

    private ToolExecutionRecord record(String id, String tool, Object value) {
        return new ToolExecutionRecord(id, "run", tool, ToolExecutionState.SUCCEEDED,
                new ToolCallRequest(tool, id, Map.of()),
                new ToolCallResult(tool, true, mapper.writeValueAsString(value), "", Map.of()), 1, "", TIME, TIME);
    }

    private ExecutionArtifact change(ExecutionArtifact a, Consumer<ObjectNode> edit) {
        ObjectNode tree = mapper.valueToTree(a);
        edit.accept(tree);
        return mapper.treeToValue(tree, ExecutionArtifact.class);
    }

    private ExecutionArtifact changePayload(ExecutionArtifact a, int index, Consumer<ObjectNode> edit) {
        return change(a, tree -> {
            ObjectNode result = (ObjectNode) tree.path("toolExecutions").get(index).path("result");
            ObjectNode payload = (ObjectNode) mapper.readTree(result.path("content").asText());
            edit.accept(payload);
            result.put("content", mapper.writeValueAsString(payload));
        });
    }

    private void assertMetric(EvaluationResult result, String metric, Status status) {
        assertEquals(status, result.checks().stream().filter(c -> metric.equals(c.metric())).findFirst().orElseThrow().status(), result.toString());
    }
}
