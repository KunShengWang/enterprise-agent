package com.agent.platform.procurement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.*;

/** Offline composition only: retains the old grader's result and adds coverage/policy definitions. */
public final class ProcurementAnswerCoverageEvaluator {
    public static final String VERSION = "procurement-answer-coverage-evaluator-v1";

    public ProcurementAnswerEvaluationV2 evaluate(EvaluationCase c, ExecutionArtifact a, byte[] fixture, byte[] policyBytes) {
        var legacy = new ProcurementAnswerGrader().grade(c, a, fixture);
        var errors = new ArrayList<>(legacy.errors());
        ProcurementAnswerCoverage.Analysis coverage = null;
        ProcurementAnswerPolicy.Policy policy = null;
        List<ProcurementAnswerPolicy.Element> elements = List.of();
        try {
            if (legacy.claims().isEmpty() && !legacy.errors().isEmpty())
                throw new IllegalArgumentException("INVALID_INPUT_FOR_COVERAGE");
            policy = ProcurementAnswerPolicy.load(policyBytes, c, fixture);
            elements = policy.forCase(c.caseId()).elements();
            coverage = new ProcurementAnswerCoverage().analyze(a.runtime().answer());
            if (!coverage.claims().stream().map(ProcurementAnswerCoverage.Extraction::claim).toList()
                    .equals(legacy.claims().stream().map(ProcurementAnswerEvaluation.ClaimResult::claim).toList()))
                throw new IllegalArgumentException("EXTRACTOR_AND_LEGACY_RESULT_MISMATCH");
        } catch (IOException | RuntimeException invalid) {
            errors.add(invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage());
        }
        String answer = a == null || a.runtime() == null || a.runtime().answer() == null ? "" : a.runtime().answer();
        return new ProcurementAnswerEvaluationV2(ProcurementAnswerEvaluationV2.SCHEMA, legacy.caseId(),
                a == null || a.runtime() == null ? "UNKNOWN" : a.runtime().runId(), legacy.artifactSha256(),
                ProcurementEvaluationReports.sha256(answer.getBytes(StandardCharsets.UTF_8)), legacy.datasetSha256(), legacy.fixtureSha256(),
                policy == null ? "UNKNOWN" : policy.policyVersion(), policyBytes == null ? "" : ProcurementEvaluationReports.sha256(policyBytes),
                legacy.extractorVersion(), ProcurementAnswerCoverage.VERSION, VERSION,
                errors.isEmpty() ? legacy.evaluationStatus() : Status.ERROR, Status.SKIP, Status.SKIP,
                legacy, coverage, elements, errors,
                "7B-2A: coverage and reviewed requirement definitions only; legacy claim verdicts retained; no completeness, reasoning or whole-answer verdict");
    }

    /** A new output file is mandatory: old artifacts and sidecars cannot be overwritten. */
    public ProcurementAnswerEvaluationV2 replay(EvaluationCase c, Path artifact, Path fixture, Path policy, Path output) throws IOException {
        var result = evaluate(c, ProcurementEvaluationReports.readArtifact(artifact), Files.readAllBytes(fixture), Files.readAllBytes(policy));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createFile(output); // CREATE_NEW semantics also reject hardlink/symlink aliases of existing inputs.
        ProcurementEvaluationReports.write(output, result);
        return result;
    }
}
