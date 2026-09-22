package com.agent.platform.procurement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static com.agent.platform.procurement.ProcurementAnswerEvaluation.*;
import static com.agent.platform.procurement.ProcurementAnswerEvidence.*;

/** Pure answer scoring. No Agent, tool executor, model client, or production state writer. */
public final class ProcurementAnswerGrader {
    public static final String VERSION = "procurement-answer-grader-v1.1";

    public AnswerEvaluation grade(EvaluationCase c, ExecutionArtifact a, byte[] fixtureBytes) {
        var structured = new ProcurementDeterministicGrader().grade(c, a);
        List<ClaimResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            for (var check : structured.checks()) if (check.metric().equals("artifactValidity") && check.status() == Status.ERROR)
                throw new IllegalArgumentException("ARTIFACT_INVALID: " + check.reason());
            var reference = reference(c, fixtureBytes);
            ObservedEvidence observed = null;
            String evidenceError = "";
            try { observed = observed(a, reference); }
            catch (RuntimeException invalid) { evidenceError = "INVALID_OBSERVED_EVIDENCE: " + invalid.getMessage(); errors.add(evidenceError); }
            for (var claim : new ProcurementAnswerClaimExtractor().extract(a.runtime().answer())) {
                Verdict factual, support;
                if (!claim.unresolvedReason().isEmpty()) {
                    factual = verdict(Status.SKIP, claim.unresolvedReason());
                    support = verdict(Status.SKIP, claim.unresolvedReason());
                } else if (Set.of("READ_ONLY", "WAITING_APPROVAL", "RFQ_CREATED", "EXECUTION_FAILED").contains(claim.property())) {
                    // Benchmark/fixture freeze procurement facts, not an authoritative external event ledger.
                    factual = verdict(Status.SKIP, "NO_AUTHORITATIVE_EXECUTION_STATE_TRUTH");
                    support = verdict(Status.SKIP, "INSUFFICIENT_STATE_EVIDENCE: completion/finalize/approvalId do not prove external effects or their absence");
                } else {
                    String currency = claim.currency().equals("CASE_CURRENCY") ? c.expectedCase().path("currency").asText() : claim.currency();
                    var expected = relevant(reference.facts(), claim);
                    factual = judge(claim, currency, expected, false);
                    support = observed == null ? verdict(Status.ERROR, evidenceError) : judge(claim, currency, relevant(observed.facts(), claim), true);
                    if (support.status() == Status.PASS && Set.of("RECOMMENDATION", "ALTERNATIVE").contains(claim.property())
                            && structured.checks().stream().noneMatch(check -> check.metric().equals("evidenceGrounding") && check.status() == Status.PASS))
                        support = verdict(Status.SKIP, "UNVERIFIED_RECOMMENDATION_PROVENANCE");
                    // A matching tool result is a captured assertion, not an authority overriding the fixture.
                    if (support.status() == Status.PASS && factual.status() == Status.FAIL)
                        support = new Verdict(Status.FAIL, "CAPTURED_MATCH_CONFLICTS_WITH_REFERENCE", support.evidencePaths());
                }
                results.add(new ClaimResult(claim, factual, support));
            }
        } catch (IOException | RuntimeException invalid) {
            errors.add(invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage());
        }
        Status status = !errors.isEmpty() ? Status.ERROR : results.stream().anyMatch(r -> r.factualCorrectness().status() == Status.FAIL
                || r.faithfulness().status() == Status.FAIL) ? Status.FAIL : Status.SKIP;
        return new AnswerEvaluation(ProcurementAnswerEvaluation.SCHEMA, c == null ? "UNKNOWN" : c.caseId(),
                c == null ? "" : c.datasetSha256(), structured.artifactSha256(), c == null ? "" : c.fixtureSha256(),
                ProcurementAnswerClaimExtractor.VERSION, VERSION, structured.structuredStatus(), status, Status.SKIP,
                "PARTIAL_CLAIMS_ONLY; complete answer/completeness not assessed; faithfulness is captured-evidence support, not proven model-visible context",
                results, errors);
    }

    /** File replay reads only saved artifacts and fixture bytes. Output must not overwrite either input. */
    public AnswerEvaluation replay(EvaluationCase c, Path artifact, Path fixture, Path output) throws IOException {
        for (Path input : List.of(artifact, fixture)) {
            if (output.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())
                    || Files.exists(output) && Files.isSameFile(output, input)) throw new IOException("Sidecar must not overwrite input");
        }
        var result = grade(c, ProcurementEvaluationReports.readArtifact(artifact), Files.readAllBytes(fixture));
        ProcurementEvaluationReports.write(output, result);
        return result;
    }

    private List<Fact> relevant(List<Fact> facts, AnswerClaim claim) {
        return facts.stream().filter(f -> f.property().equals(claim.property())
                && (Set.of("RECOMMENDATION", "ALTERNATIVE").contains(claim.property()) || f.subject().equals(claim.subject()))).toList();
    }

    private Verdict judge(AnswerClaim claim, String currency, List<Fact> facts, boolean observed) {
        if (facts.isEmpty()) return verdict(observed ? Status.SKIP : Status.FAIL,
                observed ? "INSUFFICIENT_EXECUTION_EVIDENCE" : "NO_MATCHING_FROZEN_FACT");
        String value = claim.property().equals("CURRENCY") ? currency
                : claim.number() == null ? claim.subject() : claim.number().stripTrailingZeros().toPlainString();
        boolean choice = claim.property().equals("ALTERNATIVE");
        boolean match = choice ? facts.stream().anyMatch(f -> f.value().equals(value))
                : facts.stream().allMatch(f -> f.value().equals(value) && f.currency().equals(currency));
        return new Verdict(match ? Status.PASS : Status.FAIL,
                match ? (observed ? "SUPPORTED_BY_CAPTURED_EVIDENCE" : "MATCHES_FROZEN_FACT")
                        : (observed ? "CONTRADICTED_OR_CONFLICTING_EXECUTION_EVIDENCE" : "CONTRADICTS_FROZEN_FACT"),
                facts.stream().map(Fact::path).distinct().sorted().toList());
    }
    private static Verdict verdict(Status status, String reason) { return new Verdict(status, reason, List.of()); }
}
