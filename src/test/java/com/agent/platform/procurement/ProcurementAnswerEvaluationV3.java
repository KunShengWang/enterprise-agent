package com.agent.platform.procurement;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.Status;

/** Step 1 wire contract. A parsed report is not an authenticity proof. */
public record ProcurementAnswerEvaluationV3(
        String schemaVersion, String evaluatorVersion, String assessmentCoverageVersion,
        @JsonProperty(required = true) InputBinding inputBinding,
        @JsonProperty(required = true) ProcurementAnswerCompletenessGrader.Evaluation completenessEvaluation,
        List<Prerequisite> prerequisiteChecks, AssessmentCoverage assessmentCoverage,
        Stage assessmentStage,
        @JsonProperty(required = true) CompleteAnswerStatus completeAnswerStatus,
        ExecutionStatus assessmentExecutionStatus, List<Finding> findings, String scope) {
    public static final String SCHEMA = "procurement-answer-v3";
    public static final String VERSION = "procurement-complete-answer-step1-v1";
    public static final String COVERAGE_VERSION = "procurement-assessment-coverage-pending-v1";
    public static final String SCOPE = "PREREQUISITES_ONLY; whole-answer assessment NOT_IMPLEMENTED; frozen procurement policy and captured evidence only; not task success, external-effect proof or authenticity signature";
    public enum CompleteAnswerStatus { PASS, FAIL, NEEDS_REVIEW, ERROR }
    public enum ExecutionStatus { NOT_IMPLEMENTED, ERROR }
    public enum Stage { NOT_IMPLEMENTED, BLOCKED }
    public enum FindingKind { FAIL, NEEDS_REVIEW, ERROR }
    public enum Origin { FOUNDATION, SCORING }
    public enum ScoringPath { PENDING }

    public record InputBinding(String caseId, String runId, String sessionId, String businessCaseId, Long businessVersion,
                               String datasetVersion, String datasetSha256, String fixtureSha256,
                               String artifactSha256, String answerSha256, String policyVersion, String policySha256,
                               Map<String, String> componentVersions) {
        public InputBinding {
            for (String value : List.of(caseId, runId, sessionId, businessCaseId, datasetVersion, policyVersion)) nonblank(value);
            require(businessVersion != null && businessVersion > 0, "INVALID_BUSINESS_VERSION");
            for (String hash : List.of(datasetSha256, fixtureSha256, artifactSha256, answerSha256, policySha256))
                require(hash.matches("[a-f0-9]{64}"), "INVALID_INPUT_HASH");
            componentVersions = Map.copyOf(componentVersions);
            require(componentVersions.equals(ProcurementCompleteAnswerEvaluator.supportedVersions()), "UNSUPPORTED_COMPONENT_VERSIONS");
            require(policyVersion.equals(ProcurementAnswerPolicy.VERSION)
                    && policySha256.equals(ProcurementAnswerCompletenessGrader.POLICY_SHA256), "UNSUPPORTED_POLICY_BINDING");
        }
    }
    public record Prerequisite(String name, Status status, String reason) {
        public Prerequisite {
            nonblank(name); nonblank(reason);
            require(status == Status.PASS || status == Status.ERROR, "INVALID_PREREQUISITE_STATUS");
        }
    }
    public record Finding(Origin origin, FindingKind kind, String reason, List<String> sourceRefs) {
        public Finding {
            Objects.requireNonNull(origin); Objects.requireNonNull(kind); nonblank(reason);
            sourceRefs = List.copyOf(sourceRefs); require(!sourceRefs.isEmpty(), "FINDING_SOURCE_REQUIRED");
            sourceRefs.forEach(ProcurementAnswerEvaluationV3::nonblank);
            require(origin != Origin.FOUNDATION || kind == FindingKind.ERROR, "UNTRUSTED_FOUNDATION_FINDING");
        }
    }
    /** Candidate refs are not assignments. No claim is superseded in this version. */
    public record PendingFragment(Integer originalFragmentIndex, String text, Integer start, Integer end,
                                  ScoringPath scoringPath, List<String> candidateClaimRefs,
                                  List<String> supersededUnresolvedRefs, List<String> blockers, String reason) {
        public PendingFragment {
            require(originalFragmentIndex != null && originalFragmentIndex >= 0 && start != null && end != null
                    && start >= 0 && end >= start && text != null && text.length() == end - start, "INVALID_PENDING_SPAN");
            candidateClaimRefs = List.copyOf(candidateClaimRefs); supersededUnresolvedRefs = List.copyOf(supersededUnresolvedRefs);
            blockers = List.copyOf(blockers);
            require(scoringPath == ScoringPath.PENDING && supersededUnresolvedRefs.isEmpty()
                    && blockers.equals(List.of("APPLICABILITY_NOT_IMPLEMENTED"))
                    && "NO_SCORING_HANDOFF_PERFORMED".equals(reason), "STEP1_CANNOT_HAND_OFF_CLAIMS");
        }
    }
    public record AssessmentCoverage(String version, List<PendingFragment> fragments) {
        public AssessmentCoverage {
            require(COVERAGE_VERSION.equals(version), "UNSUPPORTED_ASSESSMENT_COVERAGE"); fragments = List.copyOf(fragments);
        }
    }
    public ProcurementAnswerEvaluationV3 {
        require(SCHEMA.equals(schemaVersion) && VERSION.equals(evaluatorVersion)
                && COVERAGE_VERSION.equals(assessmentCoverageVersion) && SCOPE.equals(scope), "V3_VERSION_OR_SCOPE_MISMATCH");
        prerequisiteChecks = List.copyOf(prerequisiteChecks); findings = List.copyOf(findings);
        Objects.requireNonNull(assessmentCoverage); Objects.requireNonNull(assessmentStage); Objects.requireNonNull(assessmentExecutionStatus);
        boolean foundationError = prerequisiteChecks.stream().anyMatch(p -> p.status() == Status.ERROR);
        require(!prerequisiteChecks.isEmpty(), "PREREQUISITES_REQUIRED");
        if (foundationError) {
            require(inputBinding == null && completenessEvaluation == null && assessmentCoverage.fragments().isEmpty(), "UNTRUSTED_RESULTS_RETAINED");
            require(findings.size() == 1 && findings.get(0).origin() == Origin.FOUNDATION
                    && findings.get(0).kind() == FindingKind.ERROR
                    && findings.get(0).sourceRefs().equals(List.of("raw-inputs")), "FOUNDATION_ERROR_REQUIRED");
            require(prerequisiteChecks.equals(List.of(new Prerequisite("foundation", Status.ERROR, findings.get(0).reason()))), "FOUNDATION_CHECK_MISMATCH");
        } else {
            Objects.requireNonNull(inputBinding);
            require(prerequisiteChecks.equals(ProcurementCompleteAnswerEvaluator.passedPrerequisites(inputBinding)), "INCOMPLETE_OR_INCONSISTENT_PREREQUISITES");
            if (completenessEvaluation != null) {
                var b = completenessEvaluation.baseEvaluation(); var r = completenessEvaluation.relationEvaluation();
                require(inputBinding.caseId().equals(b.caseId()) && inputBinding.runId().equals(b.runId())
                        && inputBinding.datasetSha256().equals(b.datasetSha256()) && inputBinding.fixtureSha256().equals(b.fixtureSha256())
                        && inputBinding.artifactSha256().equals(b.artifactSha256()) && inputBinding.answerSha256().equals(b.answerSha256())
                        && inputBinding.policyVersion().equals(b.policyVersion()) && inputBinding.policySha256().equals(b.policySha256())
                        && inputBinding.datasetVersion().equals(r.datasetVersion()), "V3_NESTED_IDENTITY_MISMATCH");
                require(ProcurementAnswerGrader.VERSION.equals(b.legacyEvaluation().graderVersion()), "LEGACY_GRADER_VERSION_MISMATCH");
                require(findings.equals(ProcurementCompleteAnswerEvaluator.findings(completenessEvaluation)), "FINDINGS_SUPPRESSED_OR_FORGED");
            } else {
                require(findings.size() == 1 && findings.get(0).origin() == Origin.SCORING
                        && findings.get(0).kind() == FindingKind.ERROR
                        && findings.get(0).sourceRefs().equals(List.of("completenessEvaluation")), "MISSING_SCORING_RESULT");
            }
            require(assessmentCoverage.equals(pending(completenessEvaluation)), "PENDING_COVERAGE_MISMATCH");
        }
        boolean error = findings.stream().anyMatch(f -> f.kind() == FindingKind.ERROR);
        // Even trustworthy local FAILs are retained as findings, not aggregated before applicability exists.
        require(error ? assessmentStage == Stage.BLOCKED && completeAnswerStatus == CompleteAnswerStatus.ERROR
                        && assessmentExecutionStatus == ExecutionStatus.ERROR
                : assessmentStage == Stage.NOT_IMPLEMENTED && completeAnswerStatus == null
                        && assessmentExecutionStatus == ExecutionStatus.NOT_IMPLEMENTED, "STEP1_FULL_ASSESSMENT_FORBIDDEN");
    }
    static AssessmentCoverage pending(ProcurementAnswerCompletenessGrader.Evaluation result) {
        List<PendingFragment> fragments = new ArrayList<>();
        if (result != null && result.baseEvaluation().coverage() != null) {
            var original = result.baseEvaluation().coverage();
            String answer = original.fragments().stream().map(ProcurementAnswerCoverage.Fragment::text)
                    .collect(java.util.stream.Collectors.joining());
            require(original.equals(new ProcurementAnswerCoverage().analyze(answer)), "ORIGINAL_COVERAGE_SOURCE_MISMATCH");
            require(result.relationEvaluation().results().stream().map(ProcurementAnswerRelationGrader.Result::claim).toList()
                    .equals(new ProcurementAnswerRelationExtractor().extract(answer)), "RELATIONS_SOURCE_MISMATCH");
            var raw = result.baseEvaluation().coverage().fragments(); var relations = result.relationEvaluation().results();
            for (int i = 0; i < raw.size(); i++) {
                var f = raw.get(i); List<String> refs = new ArrayList<>();
                f.claimIds().forEach(id -> refs.add("scalar:" + id));
                for (int j = 0; j < relations.size(); j++) {
                    var c = relations.get(j).claim();
                    if (c.start() >= f.start() && c.end() <= f.end()) refs.add("relation:" + j);
                }
                fragments.add(new PendingFragment(i, f.text(), f.start(), f.end(), ScoringPath.PENDING, refs,
                        List.of(), List.of("APPLICABILITY_NOT_IMPLEMENTED"), "NO_SCORING_HANDOFF_PERFORMED"));
            }
        }
        return new AssessmentCoverage(COVERAGE_VERSION, fragments);
    }
    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
    static void nonblank(String value) { require(value != null && !value.isBlank(), "REQUIRED_TEXT_MISSING"); }
}
