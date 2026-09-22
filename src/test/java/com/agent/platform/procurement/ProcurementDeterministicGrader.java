package com.agent.platform.procurement;

import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.*;

/** Pure artifact grader: no execution dependencies and no calls to the Live IT. */
public final class ProcurementDeterministicGrader {
    public static final String VERSION = "procurement-deterministic-v2";
    private final ObjectMapper mapper = new ObjectMapper();

    public EvaluationResult grade(EvaluationCase definition, ExecutionArtifact artifact) {
        List<Check> checks = new ArrayList<>();
        String hash = "";
        try {
            require(definition != null && artifact != null, "missing case/artifact");
            hash = ProcurementEvaluationReports.artifactHash(artifact);
            validate(definition, artifact);
            check(checks, "runtimeCompletion", artifact.runtime().state() == AgentRunState.COMPLETED,
                    "expected COMPLETED; actual=" + artifact.runtime().state(), "/runtime/state");
            requirements(definition, artifact, checks);
            outcomes(definition, artifact, checks);
        } catch (RuntimeException failure) {
            checks.add(new Check("artifactValidity", Status.ERROR,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                    List.of("/")));
        }
        for (String metric : List.of("finalAnswerCorrectness", "finalAnswerFaithfulness",
                "productTextSemantics", "toolRequestArgumentsAndOrder", "hitlCompliance",
                "permissionSafety", "externalSideEffects", "performanceThresholds", "offerFactCorrectness")) {
            checks.add(new Check(metric, Status.SKIP, "Not evaluated by phase-one deterministic grader",
                    List.of(metric.startsWith("finalAnswer") ? "/runtime/answer" : "/captureLimitations")));
        }
        Status status = ProcurementEvaluationReports.aggregate(checks);
        return new EvaluationResult(definition == null ? "UNKNOWN" : definition.caseId(), hash, VERSION, status, checks);
    }

    private void validate(EvaluationCase d, ExecutionArtifact a) {
        require(SCHEMA.equals(d.schemaVersion()) && SCHEMA.equals(a.schemaVersion()), "unsupported schemaVersion");
        require(nonblank(d.caseId()) && nonblank(d.datasetVersion()) && nonblank(d.userMessage())
                && d.datasetSha256() != null && d.datasetSha256().matches("[a-f0-9]{64}"), "invalid case identity");
        require(d.caseId().equals(a.caseId()) && d.datasetSha256().equals(a.datasetSha256()), "case/dataset mismatch");
        require(d.fixtureSha256() != null && d.fixtureSha256().matches("[a-f0-9]{64}")
                && d.fixtureSha256().equals(a.metadata().get("fixtureSha256")), "fixture missing/mismatch");
        require(d.userMessage().equals(a.userMessage()), "input does not match evaluation case");
        require(a.runtime() != null, "missing runtime result");
        require(nonblank(a.runtime().runId()) && nonblank(a.runtime().sessionId())
                && a.runtime().state() != null && a.runtime().stopReason() != null, "missing runtime identity/state");
        require(a.runtime().state() != AgentRunState.COMPLETED
                || a.runtime().stopReason() == com.agent.platform.runtime.AgentStopReason.COMPLETED,
                "inconsistent completion state/stopReason");
        require(a.toolExecutions() != null, "tool execution capture unavailable");
        require(d.expectedCase().isObject() && d.expected().isObject(), "invalid ground truth");
        require(Set.of("RECOMMENDABLE", "NO_ELIGIBLE").contains(d.expected().path("status").asText()),
                "unknown expected status");
        for (String field : List.of("productCategory", "productDescription", "currency")) {
            require(d.expectedCase().path(field).isString() && !d.expectedCase().path(field).asText().isBlank(),
                    "missing ground truth: " + field);
        }
        require(d.expectedCase().path("quantity").isIntegralNumber()
                && d.expectedCase().path("quantity").asInt() > 0
                && d.expectedCase().path("budget").isNumber()
                && d.expectedCase().path("budget").decimalValue().signum() >= 0
                && d.expectedCase().path("requiredDeliveryDays").isIntegralNumber()
                && d.expectedCase().path("requiredDeliveryDays").asInt() > 0
                && d.expectedCase().path("hardConstraints").isObject()
                && d.expectedCase().path("preferences").isObject(), "invalid requirement ground truth");
        strings(d.expectedCase().path("excludedSuppliers"));
        var eligible = strings(d.expected().path("eligibleSupplierIds"));
        var dimensions = strings(d.expected().path("requiredTradeoffDimensions"));
        strings(d.expected().path("requiredEvidenceTypes"));
        require(Set.of("PRICE", "DELIVERY").containsAll(dimensions), "unknown ground truth dimension");
        boolean recommendable = "RECOMMENDABLE".equals(d.expected().path("status").asText());
        require(recommendable ? !eligible.isEmpty() && eligible.contains(d.expected().path("preferredSupplierId").asText())
                : eligible.isEmpty() && d.expected().path("preferredSupplierId").isNull() && dimensions.isEmpty(),
                "inconsistent outcome ground truth");
        require(nonblank(d.providerFixture().path("scenarioId").asText())
                && nonblank(d.providerFixture().path("sourceAsOf").asText()), "missing fixture definition");
        validateEvents(a.observedEvents(), a);
        validateEvents(a.runtime().events(), a);
        Set<String> ids = new HashSet<>();
        for (var record : a.toolExecutions()) {
            require(nonblank(record.toolCallId()) && nonblank(record.toolName()) && record.attempt() > 0,
                    "missing tool identity/attempt");
            require(a.runtime().state() != AgentRunState.COMPLETED || record.state() != ToolExecutionState.RUNNING,
                    "completed run contains unfinished tool execution");
            require(record.runId().equals(a.runtime().runId()), "tool record belongs to another run");
            require(ids.add(record.toolCallId()), "duplicate toolCallId");
            require(record.request() != null && record.toolCallId().equals(record.request().requestId())
                    && record.toolName().equals(record.request().toolName()), "tool request identity mismatch");
            if (record.result() != null) require(record.toolName().equals(record.result().toolName()), "tool result identity mismatch");
            require(record.state() != ToolExecutionState.SUCCEEDED
                    || record.result() != null && record.result().success() && nonblank(record.result().content()),
                    "successful tool missing successful result");
            require(record.result() == null || !record.result().success() || record.state() == ToolExecutionState.SUCCEEDED,
                    "successful result with non-successful execution state");
        }
    }

    private void validateEvents(List<com.agent.platform.runtime.AgentEvent> events, ExecutionArtifact a) {
        Set<String> ids = new HashSet<>();
        for (var event : events) {
            require(a.runtime().runId().equals(event.runId()) && a.runtime().sessionId().equals(event.sessionId()),
                    "event belongs to another run/session");
            require(nonblank(event.eventId()) && ids.add(event.eventId()) && event.type() != null
                    && event.createdAt() != null, "invalid/duplicate event");
        }
    }

    private void requirements(EvaluationCase d, ExecutionArtifact a, List<Check> checks) {
        require(a.finalCase() != null, "missing final business state");
        require(a.finalCase().conversationId().equals(a.runtime().sessionId()), "business state session mismatch");
        require(a.finalCase().tenantId().equals(a.metadata().get("tenantId"))
                && a.finalCase().userId().equals(a.metadata().get("userId"))
                && a.finalCase().caseId().equals(a.metadata().get("businessCaseId")), "business identity mismatch");
        JsonNode actual = mapper.valueToTree(a.finalCase().state());
        for (String field : List.of("productCategory", "productDescription")) {
            check(checks, "requirement." + field + "Present", !actual.path(field).asText().isBlank(),
                    "Nonblank only; exactTextMatch=" + actual.path(field).equals(d.expectedCase().path(field)),
                    "/finalCase/state/" + field);
        }
        for (String field : List.of("quantity", "budget", "currency", "requiredDeliveryDays",
                "hardConstraints", "preferences", "excludedSuppliers")) {
            JsonNode expected = d.expectedCase().path(field), value = actual.path(field);
            require(!expected.isMissingNode(), "missing ground truth field: " + field);
            boolean match = "excludedSuppliers".equals(field) ? strings(expected).equals(strings(value))
                    : expected.isNumber() && value.isNumber()
                    ? expected.decimalValue().compareTo(value.decimalValue()) == 0 : expected.equals(value);
            String expectedText = "excludedSuppliers".equals(field) ? strings(expected).toString()
                    : ProcurementEvaluationReports.canonicalJson(expected);
            String actualText = "excludedSuppliers".equals(field) ? strings(value).toString()
                    : ProcurementEvaluationReports.canonicalJson(value);
            check(checks, "requirement." + field, match, "expected=" + expectedText + "; actual=" + actualText,
                    "/finalCase/state/" + field);
        }
    }

    private void outcomes(EvaluationCase d, ExecutionArtifact a, List<Check> checks) {
        var searches = successful(a, ProcurementToolCatalog.SUPPLIER_SEARCH);
        var finals = successful(a, ProcurementToolCatalog.RECOMMENDATION_FINALIZE);
        JsonNode search = searches.size() == 1 ? payload(searches.get(0)) : null;
        if (search != null) bindPayload(search, a, false);
        Set<String> actualEligible = search == null ? Set.of() : supplierIds(search.path("eligibleSuppliers"));
        Set<String> expectedEligible = strings(d.expected().path("eligibleSupplierIds"));
        check(checks, "eligibility", searches.size() == 1 && actualEligible.equals(expectedEligible),
                "successful searches=" + searches.size() + "; expected=" + expectedEligible + "; actual=" + actualEligible,
                "/toolExecutions");
        if ("NO_ELIGIBLE".equals(d.expected().path("status").asText())) {
            check(checks, "recommendationOutcome", finals.isEmpty(), "Expected no successful finalization", "/toolExecutions");
            checks.add(new Check("evidenceGrounding", Status.NOT_APPLICABLE,
                    "Frozen NO_ELIGIBLE case does not require recommendation evidence", List.of("/toolExecutions")));
            checks.add(new Check("tradeoffDimensions", Status.NOT_APPLICABLE,
                    "No recommendation expected", List.of("/toolExecutions")));
            return;
        }
        String preferred = d.expected().path("preferredSupplierId").asText();
        JsonNode finalResult = finals.size() == 1 ? payload(finals.get(0)) : null;
        if (finalResult != null) bindPayload(finalResult, a, true);
        JsonNode recommendation = finalResult == null ? mapper.createObjectNode() : finalResult.path("recommendation");
        check(checks, "recommendationOutcome", finals.size() == 1
                        && preferred.equals(recommendation.path("recommendedSupplier").path("supplierId").asText())
                        && preferred.equals(recommendation.path("selectedOffer").path("supplierId").asText()),
                "Expected one finalization selecting " + preferred, "/toolExecutions");
        Set<String> requiredDimensions = strings(d.expected().path("requiredTradeoffDimensions"));
        Set<String> actualDimensions = finalResult == null ? Set.of()
                : strings(recommendation.path("tradeoffDimensions"));
        check(checks, "tradeoffDimensions", finalResult != null && actualDimensions.equals(requiredDimensions),
                "expected=" + requiredDimensions + "; actual=" + actualDimensions, "/toolExecutions");
        boolean grounded = search != null && finalResult != null
                && grounded(search, finalResult, recommendation, strings(d.expected().path("requiredEvidenceTypes")), d);
        check(checks, "evidenceGrounding", grounded,
                "References must resolve in Search and Finalize, preserve provenance and include selected supplier OFFER",
                "/toolExecutions");
    }

    private void bindPayload(JsonNode payload, ExecutionArtifact a, boolean hasCaseId) {
        require((!hasCaseId || payload.path("caseId").asText().equals(a.finalCase().caseId()))
                && payload.path("caseVersion").isIntegralNumber()
                && payload.path("caseVersion").asLong() == a.finalCase().version(), "tool payload case/version mismatch");
    }

    private boolean grounded(JsonNode search, JsonNode finish, JsonNode recommendation, Set<String> requiredTypes, EvaluationCase d) {
        Map<String, JsonNode> searched = evidence(search.path("evidence"));
        Map<String, JsonNode> finalized = evidence(finish.path("evidence"));
        Set<String> refs = strings(recommendation.path("evidenceRefs"));
        if (refs.isEmpty()) return false;
        Set<String> types = new HashSet<>();
        boolean selectedOffer = false;
        for (String ref : refs) {
            JsonNode found = finalized.get(ref), original = searched.get(ref);
            if (found == null || original == null) return false;
            if (!("scenario:" + d.providerFixture().path("scenarioId").asText()).equals(found.path("sourceSnapshot").asText())
                    || !d.providerFixture().path("sourceAsOf").asText().equals(found.path("sourceAsOf").asText())) return false;
            // Validate only already captured evidence; never reconstruct evidence via Provider.
            try {
                mapper.treeToValue(found, SupplierEvidence.class);
                mapper.treeToValue(original, SupplierEvidence.class);
            }
            catch (RuntimeException invalidEvidence) { return false; }
            for (String field : List.of("supplierId", "evidenceType", "source", "fact", "sourceRecordId",
                    "sourceSnapshot", "sourceAsOf", "sourceDigest")) {
                if (!found.path(field).equals(original.path(field))) return false;
            }
            String type = found.path("evidenceType").asText();
            types.add(type);
            selectedOffer |= "OFFER".equals(type) && found.path("supplierId").asText().equals(
                    recommendation.path("recommendedSupplier").path("supplierId").asText());
        }
        return selectedOffer && types.containsAll(requiredTypes);
    }

    private Map<String, JsonNode> evidence(JsonNode values) {
        require(values.isArray(), "evidence must be an array");
        Map<String, JsonNode> result = new TreeMap<>();
        for (JsonNode value : values) {
            String id = value.path("evidenceId").asText();
            require(!id.isBlank() && result.putIfAbsent(id, value) == null, "missing/duplicate evidence id");
        }
        return result;
    }

    private JsonNode payload(ToolExecutionRecord record) {
        JsonNode value = mapper.readTree(record.result().content());
        require(value != null && value.isObject(), "invalid tool result JSON object");
        return value;
    }

    private List<ToolExecutionRecord> successful(ExecutionArtifact a, String tool) {
        return a.toolExecutions().stream().filter(r -> tool.equals(r.toolName())
                && r.state() == ToolExecutionState.SUCCEEDED && r.result() != null && r.result().success()).toList();
    }

    private static Set<String> supplierIds(JsonNode values) {
        require(values.isArray(), "eligibleSuppliers must be an array");
        Set<String> result = new TreeSet<>();
        for (var value : values) {
            String id = value.path("supplierId").asText();
            require(!id.isBlank() && result.add(id), "invalid/duplicate eligible supplier");
        }
        return result;
    }

    private static Set<String> strings(JsonNode values) {
        require(values.isArray(), "expected string array");
        Set<String> result = new TreeSet<>();
        for (var value : values) {
            require(value.isString(), "expected string value");
            require(!value.asText().isBlank() && result.add(value.asText()), "blank/duplicate array entry");
        }
        return result;
    }

    private static void check(List<Check> checks, String metric, boolean pass, String reason, String path) {
        checks.add(new Check(metric, pass ? Status.PASS : Status.FAIL, reason, List.of(path)));
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
}
