package com.agent.platform.procurement;

import java.io.IOException;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import static com.agent.platform.procurement.ProcurementComparisonMetrics.*;

/** Read-only Step 1 consumer; never accepts externally assembled grading results. */
public final class ProcurementComparisonAnalyzer {
    private static final ObjectMapper JSON = new ObjectMapper();
    public Result analyze(Path manifestPath) throws IOException {
        var manifest = ProcurementExperimentManifest.read(manifestPath);
        Path base = manifestPath.toAbsolutePath().normalize().getParent();
        var snapshots = new LinkedHashMap<Path, byte[]>();
        List<Path> paths = new ArrayList<>(List.of(manifestPath, base.resolve(manifest.fixturePath()), base.resolve(manifest.policyPath())));
        manifest.records().forEach(e -> { paths.add(base.resolve(e.artifactPath())); paths.add(base.resolve(e.v3Path())); });
        for (Path p : paths) try { snapshots.put(p, Files.readAllBytes(p)); } catch (IOException e) { snapshots.put(p, null); }
        var gate = new ProcurementComparisonInputs().validate(manifestPath);
        String identity = manifestHash(manifest);
        if (gate.eligibility() != ProcurementComparisonInputs.Eligibility.COMPARABLE)
            return blocked(identity, gate, "STEP1_NOT_COMPARABLE");
        Map<String, ProcurementAnswerEvaluationV3> reports = new TreeMap<>();
        try {
            if (!manifest.equals(ProcurementExperimentManifest.read(manifestPath))) return blocked(identity, gate, "INPUT_CHANGED");
            for (var e : manifest.records()) {
                Path p = base.resolve(e.v3Path()); var r = new ProcurementAnswerV3Reports().read(p);
                if (!Arrays.equals(snapshots.get(p), ProcurementEvaluationReports.canonicalJson(JSON.valueToTree(r)).getBytes(StandardCharsets.UTF_8)))
                    return blocked(identity, gate, "INPUT_CHANGED");
                var checked = gate.records().stream().filter(x -> x.recordId().equals(e.recordId())).findFirst().orElseThrow();
                if (!r.inputBinding().equals(checked.inputBinding()) || r.completeAnswerStatus() != checked.completeAnswerStatus()
                        || r.assessmentExecutionStatus() != checked.assessmentExecutionStatus()) return blocked(identity, gate, "INPUT_CHANGED");
                reports.put(e.recordId(), r);
            }
            for (var snapshot : snapshots.entrySet()) if (snapshot.getValue() == null || !Arrays.equals(snapshot.getValue(), Files.readAllBytes(snapshot.getKey())))
                return blocked(identity, gate, "INPUT_CHANGED");
        } catch (IOException | RuntimeException e) { return blocked(identity, gate, "INPUT_UNAVAILABLE_AFTER_VALIDATION"); }
        Map<String, List<String>> elements = new TreeMap<>();
        var definitions = ProcurementEvaluationDataset.load();
        for (var slice : gate.cases()) {
            var c = definitions.stream().filter(x -> x.caseId().equals(slice.caseId())).findFirst().orElseThrow();
            var policy = ProcurementAnswerPolicy.load(snapshots.get(base.resolve(manifest.policyPath())), c, snapshots.get(base.resolve(manifest.fixturePath())));
            elements.put(c.caseId(), policy.forCase(c.caseId()).elements().stream().map(ProcurementAnswerPolicy.Element::elementId).toList());
        }
        return summarize(identity, gate, reports, manifest.requiredRecordsPerCase(), elements);
    }

    /** Same reducer for live analysis and stored-report consistency checks; no input trust or grading here. */
    static Result summarize(String identity, ProcurementComparisonInputs.Result gate,
            Map<String, ProcurementAnswerEvaluationV3> reports, Map<String, Integer> expected,
            Map<String, List<String>> elements) {
        var cases = new ArrayList<CaseMetrics>();
        for (var slice : gate.cases()) {
            for (var group : gate.declaredGroups()) {
                var ids = slice.groupRecordIds().get(group.groupId()); var builders = builders();
                for (String id : ids) collect(builders, id, reports.get(id), elements.get(slice.caseId()));
                cases.add(new CaseMetrics(group.groupId(), slice.caseId(), expected.get(slice.caseId()), ids.size(), ids, finish(builders)));
            }
        }
        var summaries = new ArrayList<GroupMetric>(); var diffs = new ArrayList<Difference>();
        for (var group : gate.declaredGroups()) for (Key key : Key.values()) {
            var selected = cases.stream().filter(c -> c.groupId().equals(group.groupId())).toList();
            Builder micro = new Builder(key); var rates = new ArrayList<CaseRatio>(); var missing = new ArrayList<String>(); BigDecimal total = BigDecimal.ZERO;
            for (var c : selected) {
                Metric m = metric(c, key); m.buckets().forEach(b -> micro.sources.addAll(b.sources())); micro.excluded.addAll(m.exclusions());
                rates.add(new CaseRatio(c.caseId(), m.successRatio()));
                if (m.successRatio().value() == null) missing.add(c.caseId()); else total = total.add(m.successRatio().value());
            }
            summaries.add(new GroupMetric(group.groupId(), key, micro.finish(), new Macro(missing.isEmpty() ? Calculation.CALCULABLE : Calculation.NOT_COMPUTABLE,
                    missing.isEmpty() ? total.divide(BigDecimal.valueOf(rates.size()), 12, RoundingMode.HALF_EVEN) : null, rates, missing)));
        }
        var groups = gate.declaredGroups();
        for (var slice : gate.cases()) for (int a = 0; a < groups.size(); a++) for (int b = a + 1; b < groups.size(); b++) {
            String left = groups.get(a).groupId(), right = groups.get(b).groupId();
            var l = cases.stream().filter(c -> c.caseId().equals(slice.caseId()) && c.groupId().equals(left)).findFirst().orElseThrow();
            var r = cases.stream().filter(c -> c.caseId().equals(slice.caseId()) && c.groupId().equals(right)).findFirst().orElseThrow();
            for (Key key : Key.values()) for (int i = 0; i < key.statuses().size(); i++) {
                var x = metric(l, key).buckets().get(i); var y = metric(r, key).buckets().get(i);
                boolean computable = x.proportion().value() != null && y.proportion().value() != null;
                diffs.add(new Difference(slice.caseId(), left, right, key, x.status(), x.count(), y.count(), y.count() - x.count(), x.proportion(), y.proportion(),
                        computable ? Calculation.CALCULABLE : Calculation.NOT_COMPUTABLE, computable ? y.proportion().value().subtract(x.proportion().value()) : null, x.sources(), y.sources()));
            }
        }
        return new Result(VERSION, identity, gate, State.READY, List.of(), reports, cases, summaries, diffs, LIMITS);
    }

    private static void collect(Map<Key, Builder> out, String id, ProcurementAnswerEvaluationV3 report, List<String> policy) {
        String caseId = report.inputBinding().caseId();
        add(out, Key.COMPLETE_ANSWER, id, caseId, "completeAnswerStatus", report.completeAnswerStatus().name(), "V3_COMPLETE_ANSWER", List.of());
        add(out, Key.SCORING_EXECUTION, id, caseId, "assessmentExecutionStatus", report.assessmentExecutionStatus().name(), "V3_EXECUTION_STATUS", List.of());
        var completeness = report.completenessEvaluation();
        Map<String, String> excluded = new HashMap<>();
        report.assessmentCoverage().fragments().forEach(f -> {
            f.supersededUnresolvedRefs().forEach(ref -> excluded.put(ref, "VERIFIED_DUPLICATE_HANDOFF"));
            f.nonFactualClaimRefs().forEach(ref -> excluded.put(ref, "NON_FACTUAL_ALLOWLIST"));
        });
        if (completeness != null) {
            var scalars = completeness.baseEvaluation().legacyEvaluation().claims();
            for (int i = 0; i < scalars.size(); i++) {
                String ref = "scalar:claim-" + i; var s = scalars.get(i);
                verdict(out, Key.SCALAR_FACT, id, caseId, ref + "/factualCorrectness", s.factualCorrectness(), excluded.get(ref));
                verdict(out, Key.SCALAR_EVIDENCE, id, caseId, ref + "/faithfulness", s.faithfulness(), excluded.get(ref));
                joint(out, Key.SCALAR_VERIFIED, id, caseId, ref, List.of(s.factualCorrectness(), s.faithfulness()), excluded.get(ref));
            }
            var relations = completeness.relationEvaluation().results();
            for (int i = 0; i < relations.size(); i++) {
                String ref = "relation:" + i; var r = relations.get(i);
                verdict(out, Key.RELATION_DIRECTION, id, caseId, ref + "/factualDirection", r.factualDirection(), excluded.get(ref));
                verdict(out, Key.RELATION_DIFFERENCE, id, caseId, ref + "/factualDifference", r.factualDifference(), excluded.get(ref));
                verdict(out, Key.RELATION_DIRECTION_EVIDENCE, id, caseId, ref + "/evidenceDirection", r.evidenceDirection(), excluded.get(ref));
                verdict(out, Key.RELATION_DIFFERENCE_EVIDENCE, id, caseId, ref + "/evidenceDifference", r.evidenceDifference(), excluded.get(ref));
                joint(out, Key.RELATION_VERIFIED, id, caseId, ref, List.of(r.factualDirection(), r.factualDifference(), r.evidenceDirection(), r.evidenceDifference()), excluded.get(ref));
            }
        }
        for (var definition : policy) {
            var element = completeness == null ? null : completeness.elements().stream().filter(e -> e.elementId().equals(definition)).findFirst().orElse(null);
            add(out, Key.REQUIRED_ELEMENTS, id, caseId, "element:" + definition, element == null ? "UNAVAILABLE" : element.presence().name(),
                    element == null ? "SCORING_RESULT_UNAVAILABLE" : element.reason(), element == null ? List.of() : element.matchedClaimRefs());
        }
        if (completeness != null && completeness.effectiveCoverage() != null) for (var segment : completeness.effectiveCoverage().segments()) {
            var source = new Source(id, caseId, "effective:fragment:" + segment.originalFragmentIndex(), segment.kind().name(), segment.reason(), segment.claimRefs());
            if (segment.kind() == ProcurementAnswerEffectiveCoverage.EffectiveKind.NON_FACTUAL) out.get(Key.EFFECTIVE_COVERAGE).excluded.add(new Exclusion(source, "NON_FACTUAL_ALLOWLIST"));
            else out.get(Key.EFFECTIVE_COVERAGE).sources.add(source);
        }
        for (var f : report.assessmentCoverage().fragments()) {
            var source = new Source(id, caseId, "fragment:" + f.originalFragmentIndex(), f.blockers().isEmpty() ? "RESOLVED" : "BLOCKED", f.reason(), f.blockers());
            if (f.scoringPath() == ProcurementAnswerEvaluationV3.ScoringPath.NON_FACTUAL) out.get(Key.ASSESSMENT_COVERAGE).excluded.add(new Exclusion(source, "NON_FACTUAL_ALLOWLIST"));
            else out.get(Key.ASSESSMENT_COVERAGE).sources.add(source);
        }
        for (Key key : Key.values()) if (out.get(key).sources.stream().noneMatch(s -> s.recordId().equals(id))
                && out.get(key).excluded.stream().noneMatch(e -> e.source().recordId().equals(id)))
            out.get(key).excluded.add(new Exclusion(new Source(id, caseId, key.name(), "UNAVAILABLE", "NO_CHECKS_AVAILABLE", List.of()), "NO_CHECKS_AVAILABLE"));
    }
    private static void verdict(Map<Key, Builder> out, Key key, String id, String c, String ref, ProcurementAnswerEvaluation.Verdict v, String exclusion) {
        var source = new Source(id, c, ref, v.status().name(), v.reason(), v.evidencePaths());
        if (exclusion != null) out.get(key).excluded.add(new Exclusion(source, exclusion));
        else if (v.status() == ProcurementEvaluation.Status.NOT_APPLICABLE) out.get(key).excluded.add(new Exclusion(source, "NOT_APPLICABLE:" + v.reason()));
        else out.get(key).sources.add(source);
    }
    private static void joint(Map<Key, Builder> out, Key key, String id, String c, String ref, List<ProcurementAnswerEvaluation.Verdict> values, String exclusion) {
        String status = values.stream().anyMatch(v -> v.status() == ProcurementEvaluation.Status.FAIL) ? "FAIL"
                : values.stream().anyMatch(v -> v.status() == ProcurementEvaluation.Status.ERROR) ? "ERROR"
                : values.stream().anyMatch(v -> v.status() == ProcurementEvaluation.Status.SKIP) ? "SKIP"
                : values.stream().anyMatch(v -> v.status() == ProcurementEvaluation.Status.PASS) ? "PASS" : "SKIP";
        var source = new Source(id, c, ref, status, "ALL_FACT_AND_EVIDENCE_CHECKS_REQUIRED; NOT_A_FACTUAL_VERDICT", values.stream().flatMap(v -> v.evidencePaths().stream()).distinct().toList());
        if (exclusion == null) out.get(key).sources.add(source); else out.get(key).excluded.add(new Exclusion(source, exclusion));
    }
    private static void add(Map<Key, Builder> out, Key key, String id, String c, String ref, String status, String reason, List<String> paths) {
        out.get(key).sources.add(new Source(id, c, ref, status, reason, paths));
    }
    private static Map<Key, Builder> builders() { var out = new EnumMap<Key, Builder>(Key.class); for (Key key : Key.values()) out.put(key, new Builder(key)); return out; }
    private static List<Metric> finish(Map<Key, Builder> out) { return Arrays.stream(Key.values()).map(k -> out.get(k).finish()).toList(); }
    private static Metric metric(CaseMetrics c, Key k) { return c.metrics().stream().filter(m -> m.key() == k).findFirst().orElseThrow(); }
    private static Ratio ratio(long n, long d) { return new Ratio(n, d, d == 0 ? Calculation.NOT_COMPUTABLE : Calculation.CALCULABLE,
            d == 0 ? null : BigDecimal.valueOf(n).divide(BigDecimal.valueOf(d), 12, RoundingMode.HALF_EVEN), d == 0 ? "ZERO_DENOMINATOR" : "ALL_APPLICABLE_OBSERVATIONS_INCLUDING_UNRESOLVED_AND_ERRORS"); }
    private static final class Builder {
        final Key key; final List<Source> sources = new ArrayList<>(); final List<Exclusion> excluded = new ArrayList<>();
        Builder(Key key) { this.key = key; }
        Metric finish() {
            var order = Comparator.comparing(Source::caseId).thenComparing(Source::recordId).thenComparing(Source::ref);
            var sorted = sources.stream().sorted(order).toList();
            if (sorted.stream().anyMatch(s -> !key.statuses().contains(s.status()))) throw new IllegalArgumentException("UNSUPPORTED_METRIC_STATUS:" + key);
            var buckets = key.statuses().stream().map(s -> { var refs = sorted.stream().filter(x -> x.status().equals(s)).toList(); return new Bucket(s, refs.size(), ratio(refs.size(), sorted.size()), refs); }).toList();
            return new Metric(key, key.unit(), sorted.size(), ratio(sorted.stream().filter(s -> key.successes().contains(s.status())).count(), sorted.size()), buckets,
                    excluded.stream().sorted(Comparator.comparing(Exclusion::source, order).thenComparing(Exclusion::reason)).toList());
        }
    }
    private static String manifestHash(ProcurementExperimentManifest m) {
        var normalized = new ProcurementExperimentManifest(m.schemaVersion(), m.experimentId(), m.datasetVersion(), m.datasetSha256(), m.fixturePath(), m.policyPath(), m.requiredRecordsPerCase(),
                m.groups().stream().sorted(Comparator.comparing(g -> ProcurementEvaluationReports.canonicalJson(JSON.valueToTree(g)))).toList(),
                m.records().stream().sorted(Comparator.comparing(e -> ProcurementEvaluationReports.canonicalJson(JSON.valueToTree(e)))).toList());
        return ProcurementEvaluationReports.sha256(ProcurementEvaluationReports.canonicalJson(JSON.valueToTree(normalized)).getBytes(StandardCharsets.UTF_8));
    }
    private static Result blocked(String hash, ProcurementComparisonInputs.Result gate, String code) {
        return new Result(VERSION, hash, gate, State.BLOCKED, List.of(code), Map.of(), List.of(), List.of(), List.of(), LIMITS);
    }
}
