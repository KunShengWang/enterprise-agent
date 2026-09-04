package com.agent.platform.procurement;

import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentRunBudgetSnapshot;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.tool.ToolCallResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Phase 6C：显式 opt-in 的单模型、单轮 Benchmark v1 观察，不是生产准确率。 */
class ProcurementLiveModelEvalIT {
    private static final String RESOURCE = "/procurement/benchmark/procurement-benchmark-v1.json";
    private static final String OPT_IN = "PROCUREMENT_LIVE_EVAL";

    @Test
    void evaluatesFrozenBenchmarkV1WithNativeToolCalling() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv(OPT_IN)),
                "PROCUREMENT_LIVE_EVAL is not true");
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            fail("DEEPSEEK_API_KEY is required for live eval");
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = loadBenchmark(mapper);
        List<BenchmarkCase> cases = cases(root);
        List<CaseReport> reports = new ArrayList<>();
        try (ProcurementLiveEvalRuntimeHarness harness = ProcurementLiveEvalRuntimeHarness.start(apiKey)) {
            for (BenchmarkCase benchmarkCase : cases) {
                String conversationId = "procurement-live-" + benchmarkCase.caseId() + "-" + UUID.randomUUID();
                try {
                    ProcurementLiveEvalRuntimeHarness.CaseExecution execution = harness.run(
                            conversationId, benchmarkCase.userMessage());
                    reports.add(grade(benchmarkCase, execution, conversationId));
                }
                catch (RuntimeException failure) {
                    reports.add(failedCase(benchmarkCase, conversationId, failure));
                }
            }
        }

        LiveReport report = summarize(root.path("benchmarkVersion").asText(),
                env("DEEPSEEK_CHAT_MODEL", "deepseek-chat"), reports);
        Path reportPath = Path.of("target", "procurement-live-eval", "report.json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
        System.out.printf("Procurement live benchmark: %d/%d cases; requirement=%d/%d, eligibility=%d/%d, recommendation=%d/%d, evidence=%d/%d; report=%s%n",
                report.passedCases(), report.totalCases(), report.requirementExtractionPassed(), report.totalCases(),
                report.eligibilityPassed(), report.totalCases(), report.recommendationOutcomePassed(), report.totalCases(),
                report.evidenceGroundingPassed(), report.evidenceApplicableCases(), reportPath.toAbsolutePath());
        assertEquals(cases.size(), report.totalCases());
        assertEquals(cases.size(), report.passedCases(), "live benchmark case details are in " + reportPath.toAbsolutePath());
    }

    private CaseReport grade(BenchmarkCase benchmarkCase,
                             ProcurementLiveEvalRuntimeHarness.CaseExecution execution,
                             String conversationId) {
        AgentRuntimeResult result = execution.result();
        List<ToolExecutionRecord> records = execution.toolExecutionStore().all();
        List<ToolExecutionRecord> patches = successful(records, ProcurementToolCatalog.CASE_PATCH);
        List<ToolExecutionRecord> searches = successful(records, ProcurementToolCatalog.SUPPLIER_SEARCH);
        List<ToolExecutionRecord> finalizers = successful(records, ProcurementToolCatalog.RECOMMENDATION_FINALIZE);
        ProcurementCase current = execution.caseStore().findByTenantUserAndConversationId(
                ProcurementLiveEvalRuntimeHarness.TENANT_ID, ProcurementLiveEvalRuntimeHarness.USER_ID,
                conversationId).orElse(null);
        boolean requirement = patches.size() == 1 && current != null && matchesExpectedCase(
                current.state(), benchmarkCase.expectedCase());
        JsonNode search = searches.size() == 1 ? readResult(searches.get(0).result()) : null;
        Set<String> actualEligible = search == null ? Set.of() : supplierIds(search.path("eligibleSuppliers"));
        Set<String> expectedEligible = strings(benchmarkCase.expected().path("eligibleSupplierIds"));
        boolean eligibility = searches.size() == 1 && actualEligible.equals(expectedEligible);

        String expectedStatus = benchmarkCase.expected().path("status").asText();
        boolean recommendable = "RECOMMENDABLE".equals(expectedStatus);
        JsonNode finalize = finalizers.size() == 1 ? readResult(finalizers.get(0).result()) : null;
        JsonNode recommendation = finalize == null ? null : finalize.path("recommendation");
        String actualPreferred = recommendation == null || recommendation.isMissingNode()
                ? null : recommendation.path("recommendedSupplier").path("supplierId").asText(null);
        Set<String> recommendationSuppliers = recommendation == null || recommendation.isMissingNode()
                ? Set.of() : recommendationSupplierIds(recommendation);
        String expectedPreferred = benchmarkCase.expected().path("preferredSupplierId").isNull()
                ? null : benchmarkCase.expected().path("preferredSupplierId").asText();
        String selectedOfferSupplierId = recommendation == null || recommendation.isMissingNode()
                ? null : recommendation.path("selectedOffer").path("supplierId").asText(null);
        boolean recommendationOutcome = recommendable
                ? finalizers.size() == 1 && expectedPreferred.equals(actualPreferred)
                    && actualPreferred != null && actualPreferred.equals(selectedOfferSupplierId)
                    && recommendationSuppliers.equals(expectedEligible)
                    && strings(recommendation.path("tradeoffDimensions")).equals(
                            strings(benchmarkCase.expected().path("requiredTradeoffDimensions")))
                : finalizers.isEmpty() && actualEligible.isEmpty();
        boolean evidenceApplicable = recommendable;
        Boolean evidence = recommendable && finalize != null && recommendation != null
                ? evidenceGrounded(search, finalize, recommendation,
                strings(benchmarkCase.expected().path("requiredEvidenceTypes"))) : null;
        CaseUsage usage = usage(execution);
        return new CaseReport(benchmarkCase.caseId(), result.runId(), result.state().name(),
                result.stopReason().name(), requirement, eligibility, recommendationOutcome,
                evidenceApplicable, evidence, expectedEligible, actualEligible, expectedPreferred,
                actualPreferred, usage.modelCalls(), usage.inputTokens(), usage.outputTokens(),
                usage.childRuns(), "");
    }

    private boolean matchesExpectedCase(ProcurementCaseState actual, JsonNode expected) {
        return actual.productCategory().equals(expected.path("productCategory").asText())
                && actual.productDescription().equals(expected.path("productDescription").asText())
                && actual.quantity().equals(expected.path("quantity").asInt())
                && actual.budget().compareTo(expected.path("budget").decimalValue()) == 0
                && actual.currency().equals(expected.path("currency").asText())
                && actual.requiredDeliveryDays().equals(expected.path("requiredDeliveryDays").asInt())
                && actual.hardConstraints().equals(stringMap(expected.path("hardConstraints")))
                && actual.preferences().equals(stringMap(expected.path("preferences")))
                && actual.excludedSuppliers().equals(strings(expected.path("excludedSuppliers")))
                && actual.missingFields().isEmpty();
    }

    private boolean evidenceGrounded(JsonNode search, JsonNode finalize, JsonNode recommendation,
                                     Set<String> requiredTypes) {
        Set<String> searchEvidenceIds = evidenceIds(search.path("evidence"));
        Map<String, JsonNode> finalEvidence = evidenceById(finalize.path("evidence"));
        List<String> refs = values(recommendation.path("evidenceRefs"));
        if (refs.isEmpty() || !refs.stream().allMatch(finalEvidence::containsKey)
                || !refs.stream().allMatch(searchEvidenceIds::contains)) return false;
        String recommendedSupplierId = recommendation.path("recommendedSupplier").path("supplierId").asText(null);
        boolean selectedSupplierOffer = refs.stream()
                .map(finalEvidence::get)
                .anyMatch(value -> value != null
                        && "OFFER".equals(value.path("evidenceType").asText())
                        && recommendedSupplierId != null
                        && recommendedSupplierId.equals(value.path("supplierId").asText(null)));
        Set<String> referencedTypes = new HashSet<>();
        refs.forEach(ref -> referencedTypes.add(finalEvidence.get(ref).path("evidenceType").asText()));
        return selectedSupplierOffer && referencedTypes.containsAll(requiredTypes);
    }

    private CaseReport failedCase(BenchmarkCase benchmarkCase, String conversationId, RuntimeException failure) {
        return new CaseReport(benchmarkCase.caseId(), "", "FAILED", "INTERNAL_ERROR", false, false,
                false, "RECOMMENDABLE".equals(benchmarkCase.expected().path("status").asText()), false,
                strings(benchmarkCase.expected().path("eligibleSupplierIds")), Set.of(),
                benchmarkCase.expected().path("preferredSupplierId").isNull() ? null
                        : benchmarkCase.expected().path("preferredSupplierId").asText(), null,
                0, 0, 0, 0, sanitize(failure));
    }

    private CaseUsage usage(ProcurementLiveEvalRuntimeHarness.CaseExecution execution) {
        AgentRunBudgetSnapshot parent = execution.result().budget();
        int modelCalls = parent == null ? 0 : parent.modelCalls();
        long input = parent == null ? 0 : parent.inputTokens();
        long output = parent == null ? 0 : parent.outputTokens();
        Set<String> childIds = new HashSet<>();
        execution.timelineStore().loadEvents(execution.result().runId(), 10_000).stream()
                .filter(event -> event.type() == AgentEventType.SUB_AGENT_COMPLETED)
                .map(event -> String.valueOf(event.payload().get("childRunId")))
                .filter(id -> !id.isBlank() && !"null".equals(id)).forEach(childIds::add);
        for (String childId : childIds) {
            execution.runStore().find(childId).map(com.agent.platform.runtime.AgentRunRecord::budgetSnapshot)
                    .ifPresent(budget -> {
                        // Accumulation is performed below from the immutable child snapshots.
                    });
        }
        for (String childId : childIds) {
            AgentRunBudgetSnapshot budget = execution.runStore().find(childId)
                    .map(com.agent.platform.runtime.AgentRunRecord::budgetSnapshot).orElse(null);
            if (budget != null) {
                modelCalls += budget.modelCalls();
                input += budget.inputTokens();
                output += budget.outputTokens();
            }
        }
        return new CaseUsage(modelCalls, input, output, childIds.size());
    }

    private LiveReport summarize(String benchmarkVersion, String model, List<CaseReport> cases) {
        return new LiveReport(benchmarkVersion, model, cases.size(),
                (int) cases.stream().filter(CaseReport::overallPass).count(),
                (int) cases.stream().filter(CaseReport::requirementExtractionPass).count(),
                (int) cases.stream().filter(CaseReport::eligibilityPass).count(),
                (int) cases.stream().filter(CaseReport::recommendationOutcomePass).count(),
                (int) cases.stream().filter(CaseReport::evidenceApplicable).count(),
                (int) cases.stream().filter(value -> Boolean.TRUE.equals(value.evidenceGroundingPass())).count(),
                cases.stream().mapToInt(CaseReport::modelCalls).sum(),
                cases.stream().mapToLong(CaseReport::inputTokens).sum(),
                cases.stream().mapToLong(CaseReport::outputTokens).sum(),
                cases.stream().mapToInt(CaseReport::childRuns).sum(), cases);
    }

    private List<ToolExecutionRecord> successful(List<ToolExecutionRecord> records, String name) {
        return records.stream().filter(value -> name.equals(value.toolName())
                && value.state() == ToolExecutionState.SUCCEEDED && value.result() != null
                && value.result().success()).toList();
    }

    private JsonNode readResult(ToolCallResult result) {
        try { return ProcurementLiveEvalRuntimeHarness.MAPPER.readTree(result.content()); }
        catch (Exception failure) { throw new IllegalStateException("invalid canonical tool result", failure); }
    }

    private JsonNode loadBenchmark(ObjectMapper mapper) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("benchmark resource missing: " + RESOURCE);
            return mapper.readTree(input);
        }
    }

    private List<BenchmarkCase> cases(JsonNode root) {
        List<BenchmarkCase> result = new ArrayList<>();
        for (JsonNode value : root.path("cases")) {
            result.add(new BenchmarkCase(value.path("caseId").asText(), value.path("userMessage").asText(),
                    value.path("expectedCase"), value.path("expected")));
        }
        return List.copyOf(result);
    }

    private Set<String> supplierIds(JsonNode node) {
        Set<String> result = new HashSet<>();
        for (JsonNode value : node) result.add(value.path("supplierId").asText());
        return result;
    }

    private Set<String> recommendationSupplierIds(JsonNode recommendation) {
        Set<String> result = new HashSet<>();
        result.add(recommendation.path("recommendedSupplier").path("supplierId").asText());
        for (JsonNode value : recommendation.path("eligibleAlternatives")) result.add(value.path("supplierId").asText());
        return result;
    }

    private Set<String> evidenceIds(JsonNode node) {
        Set<String> result = new HashSet<>();
        for (JsonNode value : node) result.add(value.path("evidenceId").asText());
        return result;
    }

    private Map<String, JsonNode> evidenceById(JsonNode node) {
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode value : node) result.put(value.path("evidenceId").asText(), value);
        return result;
    }

    private Set<String> strings(JsonNode node) {
        Set<String> result = new HashSet<>();
        if (node != null && node.isArray()) for (JsonNode value : node) result.add(value.asText());
        return result;
    }

    private List<String> values(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) for (JsonNode value : node) result.add(value.asText());
        return result;
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return result;
    }

    private String sanitize(Throwable failure) {
        String message = failure == null ? "" : failure.getMessage();
        message = message == null ? "" : message.replaceAll("(?i)DEEPSEEK_API_KEY|authorization|bearer", "<redacted>");
        if (message.length() > 240) message = message.substring(0, 240);
        return failure == null ? "UNKNOWN" : failure.getClass().getSimpleName() + (message.isBlank() ? "" : ": " + message);
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record BenchmarkCase(String caseId, String userMessage, JsonNode expectedCase, JsonNode expected) { }
    private record CaseUsage(int modelCalls, long inputTokens, long outputTokens, int childRuns) { }
    private record LiveReport(String benchmarkVersion, String model, int totalCases, int passedCases,
                              int requirementExtractionPassed, int eligibilityPassed, int recommendationOutcomePassed,
                              int evidenceApplicableCases, int evidenceGroundingPassed, int totalModelCalls,
                              long totalInputTokens, long totalOutputTokens, int totalChildRuns,
                              List<CaseReport> cases) { }
    private record CaseReport(String caseId, String runId, String runState, String stopReason,
                              boolean requirementExtractionPass, boolean eligibilityPass,
                              boolean recommendationOutcomePass, boolean evidenceApplicable,
                              Boolean evidenceGroundingPass, Set<String> expectedEligibleSupplierIds,
                              Set<String> actualEligibleSupplierIds, String expectedPreferredSupplierId,
                              String actualPreferredSupplierId, int modelCalls, long inputTokens,
                              long outputTokens, int childRuns, String error) {
        boolean overallPass() {
            return AgentRunState.COMPLETED.name().equals(runState) && requirementExtractionPass
                    && eligibilityPass && recommendationOutcomePass
                    && (!evidenceApplicable || Boolean.TRUE.equals(evidenceGroundingPass));
        }
    }
}
