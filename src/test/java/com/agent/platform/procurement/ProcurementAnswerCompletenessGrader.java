package com.agent.platform.procurement;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static com.agent.platform.procurement.ProcurementAnswerEffectiveCoverage.*;

/** Presence matching only. A false but explicit assertion can satisfy presence. */
public final class ProcurementAnswerCompletenessGrader {
    public static final String VERSION = "procurement-completeness-v1.1";
    public static final String SCHEMA = "procurement-completeness-coverage-v1";
    public static final String POLICY_SHA256 = "2588b6a4c336df497390dadf3fa91e312a2f81e6be3f43c473f9d5c365bfe8c4";
    public enum Presence { PRESENT, MISSING, UNRESOLVED, NOT_APPLICABLE }
    public record ElementResult(String elementId, Presence presence, List<String> matchedClaimRefs,
                                List<Integer> candidateFragmentRefs, String reason) {
        public ElementResult {
            matchedClaimRefs = List.copyOf(matchedClaimRefs); candidateFragmentRefs = List.copyOf(candidateFragmentRefs);
            if (presence == null || elementId == null || reason == null
                    || (presence == Presence.PRESENT) != !matchedClaimRefs.isEmpty())
                throw new IllegalArgumentException("INVALID_ELEMENT_RESULT");
        }
    }
    public record Evaluation(String schemaVersion, String matcherVersion, ProcurementAnswerEvaluationV2 baseEvaluation,
                             ProcurementAnswerRelationGrader.Evaluation relationEvaluation, Analysis effectiveCoverage,
                             List<ElementResult> elements, Status completenessStatus, Status evaluationStatus,
                             Status completeAnswerStatus, List<String> errors) {
        public Evaluation {
            elements = List.copyOf(elements); errors = List.copyOf(errors);
            if (!SCHEMA.equals(schemaVersion) || !VERSION.equals(matcherVersion) || completeAnswerStatus != Status.SKIP
                    || evaluationStatus != (errors.isEmpty() ? Status.SKIP : Status.ERROR))
                throw new IllegalArgumentException("COMPLETENESS_ONLY_CONTRACT");
            if (!errors.containsAll(baseEvaluation.errors()) || !errors.containsAll(relationEvaluation.errors())
                    || errors.isEmpty() && (effectiveCoverage == null || elements.isEmpty()))
                throw new IllegalArgumentException("MISSING_RESULTS_OR_SUPPRESSED_ERRORS");
            if (effectiveCoverage != null && !effectiveCoverage.original().equals(baseEvaluation.coverage()))
                throw new IllegalArgumentException("ORIGINAL_COVERAGE_MISMATCH");
            if (!Objects.equals(baseEvaluation.caseId(), relationEvaluation.caseId())
                    || !Objects.equals(baseEvaluation.runId(), relationEvaluation.runId())
                    || !Objects.equals(baseEvaluation.artifactSha256(), relationEvaluation.artifactSha256())
                    || !Objects.equals(baseEvaluation.answerSha256(), relationEvaluation.answerSha256())
                    || !Objects.equals(baseEvaluation.datasetSha256(), relationEvaluation.datasetSha256())
                    || !Objects.equals(baseEvaluation.fixtureSha256(), relationEvaluation.fixtureSha256()))
                throw new IllegalArgumentException("COMPOSITION_IDENTITY_MISMATCH");
            if (effectiveCoverage != null) {
                String answer = effectiveCoverage.original().fragments().stream().map(ProcurementAnswerCoverage.Fragment::text)
                        .collect(java.util.stream.Collectors.joining());
                if (!POLICY_SHA256.equals(baseEvaluation.policySha256())
                        || !effectiveCoverage.equals(new ProcurementAnswerEffectiveCoverage().analyze(answer, baseEvaluation.coverage(), relationEvaluation.results())))
                    throw new IllegalArgumentException("EFFECTIVE_COVERAGE_BINDING_MISMATCH");
            }
            if (!elements.isEmpty()) {
                if (effectiveCoverage == null || !elements.stream().map(ElementResult::elementId).toList()
                        .equals(baseEvaluation.requiredAnswerElements().stream().map(ProcurementAnswerPolicy.Element::elementId).toList()))
                    throw new IllegalArgumentException("ELEMENT_POLICY_MISMATCH");
                Set<String> available = new HashSet<>(), used = new HashSet<>();
                effectiveCoverage.segments().forEach(s -> available.addAll(s.claimRefs()));
                for (var e : elements) {
                    if (e.presence() == Presence.NOT_APPLICABLE || e.presence() == Presence.UNRESOLVED && e.candidateFragmentRefs().isEmpty())
                        throw new IllegalArgumentException("INVALID_PINNED_POLICY_PRESENCE");
                    for (String ref : e.matchedClaimRefs()) if (!available.contains(ref) || !used.add(ref))
                        throw new IllegalArgumentException("INVALID_OR_REUSED_MATCH_REFERENCE");
                    for (int ref : e.candidateFragmentRefs()) if (ref < 0 || ref >= effectiveCoverage.segments().size())
                        throw new IllegalArgumentException("INVALID_CANDIDATE_REFERENCE");
                }
                Set<String> eligible = verifiedMatchingContext(baseEvaluation, relationEvaluation);
                if (!elements.equals(match(eligible, baseEvaluation.requiredAnswerElements(), baseEvaluation, relationEvaluation, effectiveCoverage)))
                    throw new IllegalArgumentException("ELEMENT_MATCH_SEMANTICS_MISMATCH");
            }
            if (completenessStatus != presenceStatus(elements)) throw new IllegalArgumentException("PRESENCE_SUMMARY_MISMATCH");
        }
    }

    public Evaluation grade(EvaluationCase c, ExecutionArtifact a, byte[] fixture, byte[] policyBytes) {
        var base = new ProcurementAnswerCoverageEvaluator().evaluate(c, a, fixture, policyBytes);
        var relations = new ProcurementAnswerRelationGrader().grade(c, a, fixture);
        List<String> errors = new ArrayList<>(base.errors()); errors.addAll(relations.errors());
        Analysis effective = null; List<ElementResult> elements = List.of();
        try {
            var policy = ProcurementAnswerPolicy.load(policyBytes, c, fixture);
            if (!POLICY_SHA256.equals(policy.policySha256())) throw new IllegalArgumentException("UNSUPPORTED_MATCHER_POLICY_FINGERPRINT");
            if (base.coverage() == null || !Objects.equals(base.caseId(), relations.caseId())
                    || !Objects.equals(base.runId(), relations.runId()) || !Objects.equals(base.artifactSha256(), relations.artifactSha256())
                    || !Objects.equals(base.answerSha256(), relations.answerSha256()) || !Objects.equals(base.datasetSha256(), relations.datasetSha256())
                    || !Objects.equals(base.fixtureSha256(), relations.fixtureSha256()))
                throw new IllegalArgumentException("EVALUATION_INPUT_BINDING_MISMATCH");
            effective = new ProcurementAnswerEffectiveCoverage().analyze(a.runtime().answer(), base.coverage(), relations.results());
            Set<String> eligible = new HashSet<>(); c.expected().path("eligibleSupplierIds").forEach(id -> eligible.add(id.asText()));
            elements = match(eligible, policy.forCase(c.caseId()).elements(), base, relations, effective);
        } catch (IOException | RuntimeException invalid) {
            errors.add(invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage());
        }
        return new Evaluation(SCHEMA, VERSION, base, relations, effective, elements, presenceStatus(elements),
                errors.isEmpty() ? Status.SKIP : Status.ERROR, Status.SKIP, errors.stream().distinct().toList());
    }

    private static List<ElementResult> match(Set<String> eligible, List<ProcurementAnswerPolicy.Element> rules,
                                      ProcurementAnswerEvaluationV2 base, ProcurementAnswerRelationGrader.Evaluation relations, Analysis coverage) {
        var scalar = base.coverage().claims();
        Set<String> parsed = new HashSet<>(); coverage.segments().forEach(s -> parsed.addAll(s.claimRefs()));
        var recommendations = scalar.stream().filter(x -> parsed.contains("scalar:" + x.claimId()) && x.claim().property().equals("RECOMMENDATION")
                && x.claim().modality().equals("ASSERTED") && x.claim().unresolvedReason().isEmpty()).toList();
        Set<String> subjects = new HashSet<>(); recommendations.forEach(x -> subjects.add(x.claim().subject()));
        String selected = subjects.size() == 1 ? subjects.iterator().next() : "";
        List<Integer> unknown = coverage.segments().stream().filter(s -> unresolved(s.kind())
                && !coverage.original().fragments().get(s.originalFragmentIndex()).text().isBlank()).map(Segment::originalFragmentIndex).toList();
        Set<String> used = new HashSet<>(); List<ElementResult> results = new ArrayList<>();
        for (var rule : rules) {
            String id = rule.elementId(); List<String> refs = new ArrayList<>(); List<Integer> candidates = new ArrayList<>();
            if (id.equals("recommendation")) {
                if (!selected.isEmpty()) recommendations.forEach(x -> refs.add("scalar:" + x.claimId()));
                else if (!recommendations.isEmpty()) candidates.addAll(fragmentRefs(coverage, recommendations.stream().map(x -> "scalar:" + x.claimId()).toList()));
            } else if (id.equals("selected_total") || id.equals("selected_lead_time")) {
                String property = id.equals("selected_total") ? "TOTAL_PRICE" : "LEAD_TIME";
                for (var x : scalar) {
                    String ref = "scalar:" + x.claimId();
                    if (!parsed.contains(ref) || !x.claim().property().equals(property) || !x.claim().unresolvedReason().isEmpty()) continue;
                    if (!selected.isEmpty() && x.claim().subject().equals(selected)) refs.add(ref);
                    else if (selected.isEmpty()) candidates.addAll(fragmentRefs(coverage, List.of(ref)));
                }
            } else {
                if (!Set.of("delivery_advantage", "price_advantage", "budget_compliance", "delivery_compliance", "unique_eligible", "no_eligible").contains(id))
                    throw new IllegalArgumentException("UNSUPPORTED_REQUIRED_ELEMENT:" + id);
                for (int i = 0; i < relations.results().size(); i++) {
                    String ref = "relation:" + i; var claim = relations.results().get(i).claim();
                    if (!parsed.contains(ref) || !claim.unresolvedReason().isEmpty()) continue;
                    boolean shape = switch (id) {
                        case "delivery_advantage" -> claim.scope().equals("PAIRWISE") && claim.property().equals("LEAD_TIME");
                        case "price_advantage" -> claim.scope().equals("PAIRWISE") && claim.property().equals("TOTAL_PRICE");
                        case "budget_compliance" -> claim.scope().equals("CASE_BUDGET") && claim.property().equals("TOTAL_PRICE");
                        case "delivery_compliance" -> claim.scope().equals("CASE_DELIVERY") && claim.property().equals("LEAD_TIME");
                        case "unique_eligible" -> claim.scope().equals("ORIGINAL_CASE_ELIGIBLE") && claim.operator().equals("ONLY");
                        default -> claim.scope().equals("ORIGINAL_CASE_ELIGIBLE") && claim.operator().equals("EMPTY");
                    };
                    if (!shape) continue;
                    if (id.equals("no_eligible")) { refs.add(ref); continue; }
                    if (selected.isEmpty()) { candidates.addAll(fragmentRefs(coverage, List.of(ref))); continue; }
                    boolean role = claim.left().equals(selected);
                    if (id.endsWith("advantage")) role = (role || claim.right().equals(selected))
                            && eligible.contains(claim.left()) && eligible.contains(claim.right()) && !claim.left().equals(claim.right());
                    if (role) refs.add(ref);
                }
            }
            // Unknown substantive text cannot prove presence or definite omission. Conservatively
            // associate every remaining unknown with each unmatched element, without keyword guessing.
            if (refs.isEmpty()) candidates.addAll(unknown);
            if (refs.isEmpty() && subjects.size() > 1 && !id.equals("no_eligible"))
                candidates.addAll(fragmentRefs(coverage, recommendations.stream().map(x -> "scalar:" + x.claimId()).toList()));
            for (String ref : refs) if (!used.add(ref)) throw new IllegalArgumentException("CLAIM_REUSED_ACROSS_REQUIRED_ELEMENTS:" + ref);
            var presence = !refs.isEmpty() ? Presence.PRESENT : !candidates.isEmpty() ? Presence.UNRESOLVED : Presence.MISSING;
            results.add(new ElementResult(id, presence, refs, candidates.stream().distinct().sorted().toList(),
                    presence == Presence.PRESENT ? "EXPLICIT_TYPE_ROLE_SCOPE_MATCH; factual/support verdicts remain independent"
                            : presence == Presence.UNRESOLVED ? "POSSIBLE_CONTENT_OR_RECOMMENDATION_ROLE_UNRESOLVED" : "NO_MATCH_IN_FULLY_CLASSIFIED_CONTENT"));
        }
        return List.copyOf(results);
    }
    /** Check bundled frozen definitions, not caller-provided result labels. No Artifact is executed. */
    private static Set<String> verifiedMatchingContext(ProcurementAnswerEvaluationV2 base, ProcurementAnswerRelationGrader.Evaluation relations) {
        var json = new tools.jackson.databind.ObjectMapper();
        try (var benchmark = ProcurementAnswerCompletenessGrader.class.getResourceAsStream("/procurement/benchmark/procurement-benchmark-v1.json")) {
            if (benchmark == null) throw new IllegalArgumentException("FROZEN_BENCHMARK_MISSING");
            byte[] dataset = benchmark.readAllBytes(), policy = ProcurementAnswerPolicy.resourceBytes();
            if (!ProcurementEvaluationReports.sha256(dataset).equals(base.datasetSha256())
                    || !ProcurementEvaluationReports.sha256(policy).equals(POLICY_SHA256))
                throw new IllegalArgumentException("FROZEN_MATCHING_CONTEXT_MISMATCH");
            var benchmarkRoot = json.readTree(dataset); var policyRoot = json.readTree(policy);
            if (!benchmarkRoot.path("benchmarkVersion").asText().equals(relations.datasetVersion())
                    || !policyRoot.path("policyVersion").asText().equals(base.policyVersion())
                    || !policyRoot.path("datasetSha256").asText().equals(base.datasetSha256())
                    || !policyRoot.path("fixtureSha256").asText().equals(base.fixtureSha256()))
                throw new IllegalArgumentException("MATCHING_CONTEXT_VERSION_OR_HASH_MISMATCH");
            List<ProcurementAnswerPolicy.Element> expected = new ArrayList<>();
            for (var item : policyRoot.path("cases")) if (item.path("caseId").asText().equals(base.caseId())) {
                for (var e : item.path("elements")) {
                    List<ProcurementAnswerPolicy.Source> sources = new ArrayList<>();
                    e.path("sources").forEach(s -> sources.add(json.treeToValue(s, ProcurementAnswerPolicy.Source.class)));
                    List<String> types = new ArrayList<>(); e.path("acceptedClaimTypes").forEach(t -> types.add(t.asText()));
                    expected.add(new ProcurementAnswerPolicy.Element(e.path("elementId").asText(), base.caseId(), e.path("requirement").asText(), sources, e.path("rationale").asText(), types));
                }
            }
            if (expected.isEmpty() || !expected.equals(base.requiredAnswerElements())) throw new IllegalArgumentException("PINNED_POLICY_DEFINITIONS_MISMATCH");
            for (var item : benchmarkRoot.path("cases")) if (item.path("caseId").asText().equals(base.caseId())) {
                Set<String> eligible = new HashSet<>(); item.path("expected").path("eligibleSupplierIds").forEach(id -> eligible.add(id.asText()));
                return eligible;
            }
            throw new IllegalArgumentException("UNKNOWN_MATCHING_CASE");
        } catch (IOException invalid) { throw new IllegalArgumentException("MATCHING_CONTEXT_UNAVAILABLE", invalid); }
    }
    private static List<Integer> fragmentRefs(Analysis coverage, List<String> refs) {
        return coverage.segments().stream().filter(s -> s.claimRefs().stream().anyMatch(refs::contains)).map(Segment::originalFragmentIndex).toList();
    }
    private static Status presenceStatus(List<ElementResult> elements) {
        if (elements.isEmpty()) return Status.SKIP;
        if (elements.stream().anyMatch(e -> e.presence() == Presence.MISSING)) return Status.FAIL;
        if (elements.stream().anyMatch(e -> e.presence() == Presence.UNRESOLVED)) return Status.SKIP;
        return elements.stream().anyMatch(e -> e.presence() == Presence.PRESENT) ? Status.PASS : Status.NOT_APPLICABLE;
    }
    public Evaluation replay(EvaluationCase c, Path artifact, Path fixture, Path policy, Path output) throws IOException {
        var result = grade(c, ProcurementEvaluationReports.readArtifact(artifact), Files.readAllBytes(fixture), Files.readAllBytes(policy));
        Files.createDirectories(output.toAbsolutePath().getParent()); Files.createFile(output);
        ProcurementEvaluationReports.write(output, result); return result;
    }
}
