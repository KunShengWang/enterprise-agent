package com.agent.platform.procurement;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.Status;

/** Step 2 wire contract. A parsed report is not an authenticity proof. */
public record ProcurementAnswerEvaluationV3(
        String schemaVersion, String evaluatorVersion, String assessmentCoverageVersion,
        @JsonProperty(required = true) InputBinding inputBinding,
        @JsonProperty(required = true) ProcurementAnswerCompletenessGrader.Evaluation completenessEvaluation,
        List<Prerequisite> prerequisiteChecks, AssessmentCoverage assessmentCoverage,
        Stage assessmentStage,
        @JsonProperty(required = true) CompleteAnswerStatus completeAnswerStatus,
        ExecutionStatus assessmentExecutionStatus, List<Finding> findings, String scope) {
    public static final String SCHEMA = "procurement-answer-v3";
    public static final String VERSION = "procurement-complete-answer-step2-v1";
    public static final String COVERAGE_VERSION = "procurement-assessment-coverage-v1";
    public static final String SCOPE = "DETERMINISTIC_SUPPORTED_PROCUREMENT_ANSWER; frozen policy and captured evidence only; not arbitrary natural-language correctness, task success, model-visible evidence, external-effect proof or authenticity signature";
    public enum CompleteAnswerStatus { PASS, FAIL, NEEDS_REVIEW, ERROR }
    public enum ExecutionStatus { COMPLETE, ERROR }
    public enum Stage { ASSESSED, BLOCKED }
    public enum FindingKind { FAIL, NEEDS_REVIEW, ERROR }
    public enum Origin { FOUNDATION, SCORING }
    public enum ScoringPath { SCALAR_FACT, RELATION_FACT, NON_FACTUAL, EXECUTION_STATE, QUALIFIED, UNKNOWN_BUSINESS, UNKNOWN_CONTENT }

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
    public record Handoff(String unresolvedRef, List<String> supportingCheckRefs, String reason) {
        public Handoff {
            nonblank(unresolvedRef); nonblank(reason); supportingCheckRefs = List.copyOf(supportingCheckRefs);
            require(!supportingCheckRefs.isEmpty(), "HANDOFF_SUPPORT_REQUIRED");
        }
    }
    public record ApplicableCheck(String ref, Status status, String reason, List<String> evidencePaths, Boolean noDifference) {
        public ApplicableCheck {
            nonblank(ref); nonblank(reason); Objects.requireNonNull(status); Objects.requireNonNull(noDifference);
            evidencePaths = List.copyOf(evidencePaths);
            require(!noDifference || status == Status.NOT_APPLICABLE && ref.matches("relation:[0-9]+/(factualDifference|evidenceDifference)")
                    && reason.equals("NO_DIFFERENCE_ASSERTED"), "INVALID_NO_DIFFERENCE_EXEMPTION");
            require(status != Status.NOT_APPLICABLE || noDifference, "UNDECLARED_NOT_APPLICABLE");
        }
    }
    public record AssessedFragment(Integer originalFragmentIndex, String text, Integer start, Integer end,
                                  ScoringPath scoringPath, List<String> candidateClaimRefs,
                                  List<String> scoredClaimRefs, List<String> supersededUnresolvedRefs, List<Handoff> handoffs,
                                  List<String> nonFactualClaimRefs, List<String> matchedElementRefs, List<String> blockers, String reason) {
        public AssessedFragment {
            require(originalFragmentIndex != null && originalFragmentIndex >= 0 && start != null && end != null
                    && start >= 0 && end >= start && text != null && text.length() == end - start, "INVALID_PENDING_SPAN");
            candidateClaimRefs = List.copyOf(candidateClaimRefs); supersededUnresolvedRefs = List.copyOf(supersededUnresolvedRefs);
            scoredClaimRefs = List.copyOf(scoredClaimRefs); handoffs = List.copyOf(handoffs);
            nonFactualClaimRefs = List.copyOf(nonFactualClaimRefs); matchedElementRefs = List.copyOf(matchedElementRefs);
            blockers = List.copyOf(blockers); Objects.requireNonNull(scoringPath);
            require(supersededUnresolvedRefs.equals(handoffs.stream().map(Handoff::unresolvedRef).toList())
                    && candidateClaimRefs.containsAll(scoredClaimRefs) && candidateClaimRefs.containsAll(supersededUnresolvedRefs)
                    && candidateClaimRefs.containsAll(nonFactualClaimRefs)
                    && "EXACT_FRAGMENT_AND_CURRENT_SCORE_BINDING".equals(reason), "INVALID_FRAGMENT_ASSESSMENT");
        }
    }
    public record AssessmentCoverage(String version, List<AssessedFragment> fragments, List<ApplicableCheck> checks) {
        public AssessmentCoverage {
            require(COVERAGE_VERSION.equals(version), "UNSUPPORTED_ASSESSMENT_COVERAGE"); fragments = List.copyOf(fragments); checks = List.copyOf(checks);
            require(checks.stream().map(ApplicableCheck::ref).distinct().count() == checks.size(), "DUPLICATE_CHECK_REF");
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
            require(inputBinding == null && completenessEvaluation == null && assessmentCoverage.equals(emptyCoverage()), "UNTRUSTED_RESULTS_RETAINED");
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
                        && (inputBinding.policyVersion().equals(b.policyVersion())
                            || b.policyVersion().equals("UNKNOWN") && !b.errors().isEmpty() && b.coverage() == null && b.requiredAnswerElements().isEmpty())
                        && inputBinding.policySha256().equals(b.policySha256())
                        && inputBinding.datasetVersion().equals(r.datasetVersion()), "V3_NESTED_IDENTITY_MISMATCH");
                require(ProcurementAnswerGrader.VERSION.equals(b.legacyEvaluation().graderVersion()), "LEGACY_GRADER_VERSION_MISMATCH");
                var assessment = ProcurementAnswerAssessment.analyze(completenessEvaluation);
                require(findings.equals(assessment.findings()) && assessmentCoverage.equals(assessment.coverage()), "ASSESSMENT_SUPPRESSED_OR_FORGED");
            } else {
                require(findings.size() == 1 && findings.get(0).origin() == Origin.SCORING
                        && findings.get(0).kind() == FindingKind.ERROR
                        && findings.get(0).sourceRefs().equals(List.of("completenessEvaluation")), "MISSING_SCORING_RESULT");
                require(assessmentCoverage.equals(emptyCoverage()), "MISSING_SCORING_COVERAGE");
            }
        }
        boolean error = findings.stream().anyMatch(f -> f.kind() == FindingKind.ERROR);
        var expected = foundationError ? CompleteAnswerStatus.ERROR : completenessEvaluation == null ? CompleteAnswerStatus.NEEDS_REVIEW
                : ProcurementAnswerAssessment.decision(assessmentCoverage.checks()).status();
        require(completeAnswerStatus == expected && assessmentStage == (foundationError ? Stage.BLOCKED : Stage.ASSESSED)
                && assessmentExecutionStatus == (error ? ExecutionStatus.ERROR : ExecutionStatus.COMPLETE), "INCONSISTENT_COMPLETE_ANSWER_DECISION");
    }
    static AssessmentCoverage emptyCoverage() { return new AssessmentCoverage(COVERAGE_VERSION, List.of(), List.of()); }
    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
    static void nonblank(String value) { require(value != null && !value.isBlank(), "REQUIRED_TEXT_MISSING"); }
}
