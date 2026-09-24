package com.agent.platform.procurement;

import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.Status;
import static com.agent.platform.procurement.ProcurementAnswerEvaluationV3.*;

/** Applicability bookkeeping only; all business verdicts come from the unchanged graders. */
final class ProcurementAnswerAssessment {
    private static final Set<String> EXECUTION = Set.of("READ_ONLY", "WAITING_APPROVAL", "RFQ_CREATED", "EXECUTION_FAILED");
    record Output(AssessmentCoverage coverage, List<Finding> findings, ProcurementCompleteAnswerDecision.Decision decision) { }
    private ProcurementAnswerAssessment() { }

    static Output analyze(ProcurementAnswerCompletenessGrader.Evaluation result) {
        Objects.requireNonNull(result);
        List<AssessedFragment> fragments = new ArrayList<>();
        List<ApplicableCheck> checks = new ArrayList<>();
        var base = result.baseEvaluation(); var original = base.coverage();
        var scalars = base.legacyEvaluation().claims(); var relations = result.relationEvaluation().results();
        Set<String> superseded = new HashSet<>(), nonFactual = new HashSet<>();
        Map<String, List<String>> scoreRefs = new LinkedHashMap<>();
        Map<String, List<ApplicableCheck>> claimChecks = new LinkedHashMap<>();
        for (int i = 0; i < scalars.size(); i++) {
            String ref = "scalar:claim-" + i; var r = scalars.get(i);
            claimChecks.put(ref, List.of(check(ref + "/factualCorrectness", r.factualCorrectness(), false),
                    check(ref + "/faithfulness", r.faithfulness(), false)));
        }
        for (int i = 0; i < relations.size(); i++) {
            String ref = "relation:" + i; var r = relations.get(i);
            boolean noDifference = r.claim().unresolvedReason().isEmpty() && r.claim().difference() == null;
            claimChecks.put(ref, List.of(check(ref + "/factualDirection", r.factualDirection(), false),
                    check(ref + "/factualDifference", r.factualDifference(), noDifference),
                    check(ref + "/evidenceDirection", r.evidenceDirection(), false),
                    check(ref + "/evidenceDifference", r.evidenceDifference(), noDifference)));
        }
        claimChecks.forEach((ref, values) -> scoreRefs.put(ref, values.stream().map(ApplicableCheck::ref).toList()));
        for (int i = 0; i < result.errors().size(); i++) checks.add(new ApplicableCheck("scoringError:" + i, Status.ERROR,
                result.errors().get(i), List.of("completenessEvaluation/errors/" + i), false));

        if (original == null) {
            checks.add(new ApplicableCheck("coverage", Status.ERROR, "ORIGINAL_COVERAGE_UNAVAILABLE", List.of(), false));
        } else {
            String answer = original.fragments().stream().map(ProcurementAnswerCoverage.Fragment::text)
                    .collect(java.util.stream.Collectors.joining());
            require(original.equals(new ProcurementAnswerCoverage().analyze(answer)), "ORIGINAL_COVERAGE_SOURCE_MISMATCH");
            require(original.claims().stream().map(ProcurementAnswerCoverage.Extraction::claim).toList()
                    .equals(scalars.stream().map(ProcurementAnswerEvaluation.ClaimResult::claim).toList()), "SCALAR_RESULT_IDENTITY_MISMATCH");
            var actualRelations = relations.stream().map(ProcurementAnswerRelationGrader.Result::claim).toList();
            var expectedRelations = new ProcurementAnswerRelationExtractor().extract(answer);
            boolean relationComplete = actualRelations.equals(expectedRelations);
            require(relationComplete || !result.errors().isEmpty() && actualRelations.size() < expectedRelations.size()
                    && actualRelations.equals(expectedRelations.subList(0, actualRelations.size())), "RELATIONS_SOURCE_MISMATCH");
            if (!relationComplete) checks.add(new ApplicableCheck("relations", Status.ERROR, "RELATION_SCORING_INCOMPLETE", List.of(), false));
            var effective = result.effectiveCoverage();
            boolean mayHandoff = relationComplete && effective != null && result.errors().isEmpty();
            if (effective == null) checks.add(new ApplicableCheck("effectiveCoverage", Status.ERROR, "EFFECTIVE_COVERAGE_UNAVAILABLE", List.of(), false));
            for (int i = 0; i < original.fragments().size(); i++) {
                var f = original.fragments().get(i);
                int start = f.start(), end = f.end();
                while (start < end && Character.isWhitespace(answer.charAt(start))) start++;
                while (end > start && Character.isWhitespace(answer.charAt(end - 1))) end--;
                List<String> candidates = new ArrayList<>(), scored = new ArrayList<>(), exempt = new ArrayList<>(), blockers = new ArrayList<>();
                List<Handoff> handoffs = new ArrayList<>();
                List<Integer> scalarIndices = new ArrayList<>(), relationIndices = new ArrayList<>();
                for (int j = 0; j < scalars.size(); j++) {
                    var c = scalars.get(j).claim();
                    if (c.start() >= f.start() && c.end() <= f.end() && (c.end() > c.start() || answer.isEmpty())) {
                        scalarIndices.add(j); candidates.add("scalar:claim-" + j);
                        if (c.unresolvedReason().isEmpty()) scored.add("scalar:claim-" + j);
                    }
                }
                for (int j = 0; j < relations.size(); j++) {
                    var c = relations.get(j).claim();
                    if (c.start() == start && c.end() == end) {
                        relationIndices.add(j); candidates.add("relation:" + j);
                        if (c.unresolvedReason().isEmpty()) scored.add("relation:" + j);
                    }
                }
                ScoringPath path;
                var kind = effective == null ? null : effective.segments().get(i).kind();
                if (scalarIndices.stream().anyMatch(j -> EXECUTION.contains(scalars.get(j).claim().property()))) path = ScoringPath.EXECUTION_STATE;
                else if (kind == ProcurementAnswerEffectiveCoverage.EffectiveKind.PARSED_RELATION) path = ScoringPath.RELATION_FACT;
                else if (kind == ProcurementAnswerEffectiveCoverage.EffectiveKind.PARSED_SCALAR) path = ScoringPath.SCALAR_FACT;
                else if (f.kind() == ProcurementAnswerCoverage.Kind.NON_FACTUAL) path = ScoringPath.NON_FACTUAL;
                else if (scalarIndices.stream().anyMatch(j -> scalars.get(j).claim().modality().equals("QUALIFIED"))
                        || relationIndices.stream().anyMatch(j -> relations.get(j).claim().modality().equals("QUALIFIED"))) path = ScoringPath.QUALIFIED;
                else path = f.kind() == ProcurementAnswerCoverage.Kind.UNRESOLVED_BUSINESS ? ScoringPath.UNKNOWN_BUSINESS : ScoringPath.UNKNOWN_CONTENT;

                // An exact non-factual allowlist is applicability, not a successful factual handoff.
                if (path == ScoringPath.NON_FACTUAL) {
                    for (int j : scalarIndices) if (scalars.get(j).claim().unresolvedReason().equals("UNSUPPORTED_EXPRESSION")
                            && scalars.get(j).claim().modality().equals("ASSERTED")) exempt.add("scalar:claim-" + j);
                    for (int j : relationIndices) if (plainRelationUnknown(relations.get(j).claim())) exempt.add("relation:" + j);
                }
                if (mayHandoff && path == ScoringPath.SCALAR_FACT && !scored.isEmpty()
                        && scored.stream().allMatch(ref -> verified(claimChecks.get(ref)))) {
                    for (int j : relationIndices) if (plainRelationUnknown(relations.get(j).claim())) {
                        String ref = "relation:" + j;
                        handoffs.add(new Handoff(ref, scored.stream().flatMap(s -> scoreRefs.get(s).stream()).toList(), "SAME_FRAGMENT_VERIFIED_SCALARS"));
                    }
                }
                if (mayHandoff && path == ScoringPath.RELATION_FACT && relationIndices.size() == 1) {
                    String support = "relation:" + relationIndices.get(0);
                    if (verified(claimChecks.get(support))) {
                        for (int j : scalarIndices) {
                            var c = scalars.get(j).claim();
                            // The full finite asserted relation grammar disambiguates scalar over-abstention
                            // (e.g. the character 约 in 原始硬约束). No prefix/adjacent-clause handoff.
                            if (c.property().equals("UNRESOLVED") && c.start() == start && c.end() == end
                                    && Set.of("UNSUPPORTED_EXPRESSION", "UNSUPPORTED_SUBJECT_CLAUSE", "NEGATION_CONDITION_TENSE_OR_HEDGE").contains(c.unresolvedReason())
                                    && claimChecks.get("scalar:claim-" + j).stream().allMatch(v -> v.status() == Status.SKIP))
                                handoffs.add(new Handoff("scalar:claim-" + j, scoreRefs.get(support), "SAME_FRAGMENT_VERIFIED_ASSERTED_RELATION"));
                        }
                    }
                }
                List<String> handed = handoffs.stream().map(Handoff::unresolvedRef).toList();
                superseded.addAll(handed); nonFactual.addAll(exempt);
                for (String ref : candidates) if (!handed.contains(ref) && !exempt.contains(ref)) {
                    for (var check : claimChecks.get(ref)) if (check.status() != Status.PASS && check.status() != Status.NOT_APPLICABLE) blockers.add(check.ref());
                }
                if (Set.of(ScoringPath.QUALIFIED, ScoringPath.UNKNOWN_BUSINESS, ScoringPath.UNKNOWN_CONTENT, ScoringPath.EXECUTION_STATE).contains(path))
                    blockers.add("fragment:" + i + ":UNVERIFIED_CONTENT");
                if (path != ScoringPath.NON_FACTUAL && scored.isEmpty()) blockers.add("fragment:" + i + ":NO_VERIFIED_PATH");
                var elements = result.elements().stream().filter(e -> e.matchedClaimRefs().stream().anyMatch(candidates::contains))
                        .map(e -> "element:" + e.elementId()).toList();
                fragments.add(new AssessedFragment(i, f.text(), f.start(), f.end(), path, candidates, scored, handed, handoffs,
                        exempt, elements, blockers.stream().distinct().toList(), "EXACT_FRAGMENT_AND_CURRENT_SCORE_BINDING"));
                if (!blockers.isEmpty()) checks.add(new ApplicableCheck("fragment:" + i, Status.SKIP, "UNRESOLVED_FRAGMENT_PATHS", blockers, false));
            }
            if (answer.isBlank() || fragments.stream().noneMatch(f -> !f.scoredClaimRefs().isEmpty()))
                checks.add(new ApplicableCheck("substantiveAnswer", Status.SKIP, "EMPTY_OR_NO_SCORED_BUSINESS_CONTENT", List.of(), false));
        }
        // Only duplicate unresolved checks proved above are removed; all other scores remain applicable.
        claimChecks.forEach((ref, values) -> { if (!superseded.contains(ref) && !nonFactual.contains(ref)) checks.addAll(values); });
        if (result.elements().isEmpty()) checks.add(new ApplicableCheck("elements", Status.ERROR, "REQUIRED_ELEMENTS_UNAVAILABLE", List.of(), false));
        for (var e : result.elements()) {
            Status status = switch (e.presence()) { case PRESENT -> Status.PASS; case MISSING -> Status.FAIL; case UNRESOLVED -> Status.SKIP; case NOT_APPLICABLE -> Status.ERROR; };
            checks.add(new ApplicableCheck("element:" + e.elementId(), status, e.reason(), e.matchedClaimRefs(), false));
        }
        var coverage = new AssessmentCoverage(COVERAGE_VERSION, fragments, checks);
        List<Finding> findings = checks.stream().filter(c -> c.status() == Status.FAIL || c.status() == Status.ERROR || c.status() == Status.SKIP)
                .map(c -> { List<String> refs = new ArrayList<>(); refs.add(c.ref()); refs.addAll(c.evidencePaths());
                    return new Finding(Origin.SCORING, c.status() == Status.FAIL ? FindingKind.FAIL : c.status() == Status.ERROR ? FindingKind.ERROR : FindingKind.NEEDS_REVIEW, c.reason(), refs); }).toList();
        return new Output(coverage, findings, decision(checks));
    }
    static ProcurementCompleteAnswerDecision.Decision decision(List<ApplicableCheck> checks) {
        Set<Integer> na = new HashSet<>();
        for (int i = 0; i < checks.size(); i++) if (checks.get(i).noDifference()) na.add(i);
        return ProcurementCompleteAnswerDecision.decide(true, true, checks.stream().map(ApplicableCheck::status).toList(), na);
    }
    private static ApplicableCheck check(String ref, ProcurementAnswerEvaluation.Verdict v, boolean noDifference) {
        return new ApplicableCheck(ref, v.status(), v.reason(), v.evidencePaths(), noDifference);
    }
    private static boolean verified(List<ApplicableCheck> checks) {
        return checks != null && checks.stream().anyMatch(c -> c.status() == Status.PASS)
                && checks.stream().allMatch(c -> c.status() == Status.PASS || c.noDifference() && c.status() == Status.NOT_APPLICABLE);
    }
    private static boolean plainRelationUnknown(ProcurementAnswerRelationExtractor.Claim c) {
        return c.modality().equals("UNKNOWN") && c.unresolvedReason().equals("UNSUPPORTED_WHOLE_CLAUSE");
    }
}
