package com.agent.platform.procurement;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import static com.agent.platform.procurement.ProcurementEvaluation.Status;

/** Independent v2 wire contract. This batch does not execute any completeness/relationship rules. */
public record ProcurementAnswerEvaluationV2(
        String schemaVersion, String caseId, String runId, String artifactSha256, String answerSha256,
        String datasetSha256, String fixtureSha256, String policyVersion, String policySha256,
        String extractorVersion, String coverageAnalyzerVersion, String answerGraderVersion,
        Status evaluationStatus, Status completenessStatus, Status completeAnswerStatus,
        ProcurementAnswerEvaluation.AnswerEvaluation legacyEvaluation, ProcurementAnswerCoverage.Analysis coverage,
        List<ProcurementAnswerPolicy.Element> requiredAnswerElements, List<String> errors, String scope) {
    public static final String SCHEMA = "procurement-answer-v2";
    public ProcurementAnswerEvaluationV2 {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unknown answer v2 schema");
        requiredAnswerElements = List.copyOf(requiredAnswerElements);
        errors = List.copyOf(errors);
        if (completenessStatus != Status.SKIP || completeAnswerStatus != Status.SKIP || evaluationStatus == Status.PASS)
            throw new IllegalArgumentException("7B-2A cannot assert completeness or complete-answer PASS");
        Objects.requireNonNull(legacyEvaluation, "legacyEvaluation");
        if (!Objects.equals(caseId, legacyEvaluation.caseId())
                || !Objects.equals(artifactSha256, legacyEvaluation.artifactSha256())
                || !Objects.equals(datasetSha256, legacyEvaluation.datasetSha256())
                || !Objects.equals(fixtureSha256, legacyEvaluation.fixtureSha256())
                || !Objects.equals(extractorVersion, legacyEvaluation.extractorVersion())
                || !ProcurementAnswerCoverage.VERSION.equals(coverageAnalyzerVersion)
                || !ProcurementAnswerCoverageEvaluator.VERSION.equals(answerGraderVersion))
            throw new IllegalArgumentException("V2_IDENTITY_OR_VERSION_MISMATCH");
        if (evaluationStatus != (errors.isEmpty() ? legacyEvaluation.evaluationStatus() : Status.ERROR)
                || !errors.containsAll(legacyEvaluation.errors()))
            throw new IllegalArgumentException("V2_ERROR_STATUS_MISMATCH");
        if (coverage != null) {
            String answer = coverage.fragments().stream().map(ProcurementAnswerCoverage.Fragment::text)
                    .collect(java.util.stream.Collectors.joining());
            if (!ProcurementEvaluationReports.sha256(answer.getBytes(StandardCharsets.UTF_8)).equals(answerSha256)
                    || !coverage.extractorVersion().equals(extractorVersion)
                    || !coverage.claims().stream().map(ProcurementAnswerCoverage.Extraction::claim).toList()
                    .equals(legacyEvaluation.claims().stream().map(ProcurementAnswerEvaluation.ClaimResult::claim).toList()))
                throw new IllegalArgumentException("V2_ANSWER_OR_CLAIMS_MISMATCH");
        }
        if (errors.isEmpty() && (coverage == null || requiredAnswerElements.isEmpty()
                || !ProcurementAnswerPolicy.VERSION.equals(policyVersion)
                || policySha256 == null || !policySha256.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("V2_REQUIRED_BINDING_MISSING");
        if (requiredAnswerElements.stream().anyMatch(e -> !Objects.equals(caseId, e.caseId())))
            throw new IllegalArgumentException("V2_POLICY_CASE_MISMATCH");
    }
}
