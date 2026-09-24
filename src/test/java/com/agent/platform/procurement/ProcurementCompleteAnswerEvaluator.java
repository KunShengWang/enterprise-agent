package com.agent.platform.procurement;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static com.agent.platform.procurement.ProcurementAnswerEvaluationV3.*;
import static com.agent.platform.procurement.ProcurementAnswerEvaluationV3.SCHEMA;

/** Raw-input-only entry point. No intermediate JSON composition or file replay API. */
public final class ProcurementCompleteAnswerEvaluator {
    private static final tools.jackson.databind.json.JsonMapper STRICT_JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> REQUIREMENTS = Set.of("requirement.productCategoryPresent", "requirement.productDescriptionPresent",
            "requirement.quantity", "requirement.budget", "requirement.currency", "requirement.requiredDeliveryDays",
            "requirement.hardConstraints", "requirement.preferences", "requirement.excludedSuppliers");

    public ProcurementAnswerEvaluationV3 evaluate(EvaluationCase c, ExecutionArtifact a, byte[] fixtureBytes, byte[] policyBytes) {
        InputBinding binding;
        try {
            // Pin actual source bytes, not caller-supplied labels. load also validates the frozen case and fixture.
            require(Arrays.equals(policyBytes, ProcurementAnswerPolicy.resourceBytes()), "POLICY_SOURCE_MISMATCH");
            var policy = ProcurementAnswerPolicy.load(policyBytes, c, fixtureBytes);
            var structured = new ProcurementDeterministicGrader().grade(c, a);
            require("procurement-deterministic-v2".equals(structured.graderVersion()), "UNSUPPORTED_PHASE7A_VERSION");
            // Under this pinned grader, all requirement checks can only exist after identity validation.
            // Outcome parsing errors occur later and must not masquerade as a successful identity gate.
            for (String metric : new TreeSet<>(REQUIREMENTS)) {
                var checks = structured.checks().stream().filter(check -> check.metric().equals(metric)).toList();
                require(checks.size() == 1 && checks.get(0).status() == Status.PASS,
                        "IDENTITY_OR_REQUIREMENT_SCOPE_UNTRUSTED:" + metric);
            }
            require(a.finalCase().version() > 0, "INVALID_BUSINESS_VERSION");
            require(a.runtime().answer() != null, "ANSWER_CAPTURE_MISSING");
            // Payload identity/version is a foundation dependency even if its numeric facts are wrong.
            for (var record : a.toolExecutions()) {
                if (record.state() != com.agent.platform.runtime.ToolExecutionState.SUCCEEDED || record.result() == null) continue;
                if (!Set.of("procurement_supplier_search", "procurement_recommendation_finalize").contains(record.toolName())) continue;
                var payload = STRICT_JSON.readTree(record.result().content());
                require(payload != null && payload.isObject(), "INVALID_BUSINESS_PAYLOAD");
                require(payload.path("caseVersion").isIntegralNumber()
                        && payload.path("caseVersion").canConvertToLong()
                        && payload.path("caseVersion").asLong() == a.finalCase().version(), "PAYLOAD_VERSION_MISMATCH");
                if (payload.has("caseId")) require(payload.path("caseId").isString()
                        && payload.path("caseId").asText().equals(a.finalCase().caseId()), "PAYLOAD_CASE_MISMATCH");
                if (record.toolName().equals("procurement_recommendation_finalize"))
                    require(payload.path("caseId").asText().equals(a.finalCase().caseId()), "FINALIZE_CASE_REQUIRED");
            }
            binding = new InputBinding(c.caseId(), a.runtime().runId(), a.runtime().sessionId(), a.finalCase().caseId(),
                    a.finalCase().version(), c.datasetVersion(), c.datasetSha256(), c.fixtureSha256(),
                    ProcurementEvaluationReports.artifactHash(a), ProcurementEvaluationReports.sha256(a.runtime().answer().getBytes(StandardCharsets.UTF_8)),
                    policy.policyVersion(), policy.policySha256(), supportedVersions());
        } catch (Exception invalid) {
            String reason = message(invalid);
            return new ProcurementAnswerEvaluationV3(SCHEMA, VERSION, COVERAGE_VERSION, null, null,
                    List.of(new Prerequisite("foundation", Status.ERROR, reason)), emptyCoverage(), Stage.BLOCKED,
                    CompleteAnswerStatus.ERROR, ExecutionStatus.ERROR,
                    List.of(new Finding(Origin.FOUNDATION, FindingKind.ERROR, reason, List.of("raw-inputs"))), SCOPE);
        }
        ProcurementAnswerCompletenessGrader.Evaluation result = null;
        List<Finding> findings;
        ProcurementAnswerAssessment.Output assessment = null;
        try {
            result = new ProcurementAnswerCompletenessGrader().grade(c, a, fixtureBytes, policyBytes);
            assessment = ProcurementAnswerAssessment.analyze(result);
            findings = assessment.findings();
        } catch (Exception invalid) {
            findings = List.of(new Finding(Origin.SCORING, FindingKind.ERROR, message(invalid), List.of("completenessEvaluation")));
        }
        boolean error = findings.stream().anyMatch(f -> f.kind() == FindingKind.ERROR);
        // A constructor/assessment invariant violation is not a license to manufacture a scored result.
        if (assessment == null) result = null;
        return new ProcurementAnswerEvaluationV3(SCHEMA, VERSION, COVERAGE_VERSION, binding, result, passedPrerequisites(binding),
                assessment == null ? emptyCoverage() : assessment.coverage(), Stage.ASSESSED,
                assessment == null ? CompleteAnswerStatus.NEEDS_REVIEW : assessment.decision().status(),
                error ? ExecutionStatus.ERROR : ExecutionStatus.COMPLETE, findings, SCOPE);
    }

    static List<Prerequisite> passedPrerequisites(InputBinding binding) {
        // Preserve the raw gate's identity observation where old nested sidecars have no such fields.
        String identity = ProcurementEvaluationReports.canonicalJson(ProcurementAnswerEvidence.JSON.valueToTree(Map.of(
                "sessionId", binding.sessionId(), "businessCaseId", binding.businessCaseId(), "businessVersion", binding.businessVersion())));
        return List.of("frozenInputs", "artifactIdentity", "requirementScope", "payloadIdentity", "componentVersions", "answerBinding")
                .stream().map(name -> new Prerequisite(name, Status.PASS, "VERIFIED_FROM_RAW_INPUTS"
                        + (name.equals("artifactIdentity") ? ":" + identity : ""))).toList();
    }
    static Map<String, String> supportedVersions() {
        Map<String, String> actual = Map.ofEntries(
                Map.entry("structured", ProcurementDeterministicGrader.VERSION), Map.entry("scalarExtractor", ProcurementAnswerClaimExtractor.VERSION),
                Map.entry("scalarGrader", ProcurementAnswerGrader.VERSION), Map.entry("coverage", ProcurementAnswerCoverage.VERSION),
                Map.entry("coverageEvaluator", ProcurementAnswerCoverageEvaluator.VERSION), Map.entry("relationExtractor", ProcurementAnswerRelationExtractor.VERSION),
                Map.entry("relationGrader", ProcurementAnswerRelationGrader.VERSION), Map.entry("matcher", ProcurementAnswerCompletenessGrader.VERSION),
                Map.entry("effectiveCoverage", ProcurementAnswerEffectiveCoverage.VERSION), Map.entry("decision", ProcurementCompleteAnswerDecision.VERSION));
        Map<String, String> pinned = Map.ofEntries(
                Map.entry("structured", "procurement-deterministic-v2"), Map.entry("scalarExtractor", "procurement-claims-v1.1"),
                Map.entry("scalarGrader", "procurement-answer-grader-v1.1"), Map.entry("coverage", "procurement-answer-coverage-v1"),
                Map.entry("coverageEvaluator", "procurement-answer-coverage-evaluator-v1"), Map.entry("relationExtractor", "procurement-relations-v1"),
                Map.entry("relationGrader", "procurement-relation-grader-v1.1"), Map.entry("matcher", "procurement-completeness-v1.1"),
                Map.entry("effectiveCoverage", "procurement-effective-coverage-v1"), Map.entry("decision", "procurement-complete-answer-decision-v2"));
        require(actual.equals(pinned), "UNSUPPORTED_COMPONENT_COMBINATION"); return pinned;
    }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
