package com.agent.platform.procurement;

import java.math.BigDecimal;
import java.util.List;
import static com.agent.platform.procurement.ProcurementEvaluation.Status;

/** Independent sidecar; offsets are Java UTF-16 [start,end) in the unchanged answer. */
public final class ProcurementAnswerEvaluation {
    public static final String SCHEMA = "procurement-answer-v1";
    private ProcurementAnswerEvaluation() { }

    public record AnswerClaim(String text, int start, int end, String subject, String property,
                              BigDecimal number, String unit, String currency, String modality,
                              String extractionMethod, String unresolvedReason) { }
    public record Verdict(Status status, String reason, List<String> evidencePaths) {
        public Verdict { evidencePaths = List.copyOf(evidencePaths); }
    }
    public record ClaimResult(AnswerClaim claim, Verdict factualCorrectness, Verdict faithfulness) { }
    public record AnswerEvaluation(String schemaVersion, String caseId, String datasetSha256,
                                   String artifactSha256, String fixtureSha256, String extractorVersion,
                                   String graderVersion, Status structuredStatus, Status evaluationStatus,
                                   Status completeAnswerStatus, String scope, List<ClaimResult> claims,
                                   List<String> errors) {
        public AnswerEvaluation { claims = List.copyOf(claims); errors = List.copyOf(errors); }
    }
}
