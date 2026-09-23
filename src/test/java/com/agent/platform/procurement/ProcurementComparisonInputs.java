package com.agent.platform.procurement;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.agent.platform.procurement.ProcurementAnswerEvaluationV3.*;

/** Comparison eligibility only. No ranking, rates or independent-sample count. */
public final class ProcurementComparisonInputs {
    public static final String VERSION = "procurement-comparison-inputs-v1";
    public static final String LIMITS = "DECLARED_EXPERIMENT_LABELS; DISTINCT_CAPTURED_RUN_IDS_ONLY; INDEPENDENCE_NOT_ATTESTED; NOT_SOURCE_AUTHENTICATION";
    public enum Eligibility { COMPARABLE, NOT_COMPARABLE }
    public enum Scope { FULL_FROZEN_BENCHMARK, DECLARED_SUBSET }
    public record Diagnostic(String code, String groupId, String caseId, String recordId) { }
    public record RecordCheck(String recordId, String groupId, String caseId, Eligibility eligibility,
            InputBinding inputBinding, CompleteAnswerStatus completeAnswerStatus, ExecutionStatus assessmentExecutionStatus,
            List<String> diagnostics) { public RecordCheck { diagnostics = List.copyOf(diagnostics); } }
    public record CaseSlice(String caseId, Eligibility eligibility, Map<String, List<String>> groupRecordIds) {
        public CaseSlice { groupRecordIds = Collections.unmodifiableMap(new TreeMap<>(groupRecordIds)); }
    }
    public record Result(String version, String experimentId, Eligibility eligibility, Scope scope,
            List<ProcurementExperimentManifest.Group> declaredGroups, List<RecordCheck> records,
            List<CaseSlice> cases, List<Diagnostic> diagnostics, String limitations) { }
    private static final JsonMapper STRICT = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public Result validate(Path manifestPath) throws IOException {
        var m = ProcurementExperimentManifest.read(manifestPath);
        Path base = manifestPath.toAbsolutePath().normalize().getParent();
        var frozen = ProcurementEvaluationDataset.load();
        var definitions = new TreeMap<String, ProcurementEvaluation.EvaluationCase>();
        frozen.forEach(c -> definitions.put(c.caseId(), c));
        var diagnostics = new ArrayList<Diagnostic>();
        if (!m.datasetVersion().equals(frozen.get(0).datasetVersion()) || !m.datasetSha256().equals(frozen.get(0).datasetSha256()))
            diagnostics.add(new Diagnostic("DATASET_IDENTITY_MISMATCH", "", "", ""));
        for (String id : m.requiredRecordsPerCase().keySet()) if (!definitions.containsKey(id))
            diagnostics.add(new Diagnostic("UNKNOWN_SCOPE_CASE", "", id, ""));
        var groupIds = new TreeSet<String>();
        for (var g : m.groups()) if (!groupIds.add(g.groupId()))
            diagnostics.add(new Diagnostic("DUPLICATE_OR_CONFLICTING_GROUP", g.groupId(), "", ""));
        byte[] fixture = null, policy = null;
        try {
            fixture = Files.readAllBytes(base.resolve(m.fixturePath())); policy = Files.readAllBytes(base.resolve(m.policyPath()));
            for (var c : frozen) ProcurementAnswerPolicy.load(policy, c, fixture);
            if (!Arrays.equals(policy, ProcurementAnswerPolicy.resourceBytes())) throw new IOException("POLICY_SOURCE_MISMATCH");
        } catch (IOException | RuntimeException e) { diagnostics.add(new Diagnostic("FROZEN_RESOURCES_UNTRUSTED", "", "", "")); }
        boolean globalInvalid = !diagnostics.isEmpty();
        var checks = new ArrayList<RecordCheck>();
        var seenArtifacts = new HashMap<String, List<Integer>>(); var seenRuns = new HashMap<String, List<Integer>>();
        var seenIds = new HashMap<String, List<Integer>>();
        var entries = m.records().stream().sorted(Comparator.comparing(ProcurementExperimentManifest.Entry::recordId)
                .thenComparing(ProcurementExperimentManifest.Entry::groupId).thenComparing(ProcurementExperimentManifest.Entry::caseId)
                .thenComparing(ProcurementExperimentManifest.Entry::artifactPath).thenComparing(ProcurementExperimentManifest.Entry::v3Path)
                .thenComparing(e -> ProcurementEvaluationReports.canonicalJson(STRICT.valueToTree(e)))).toList();
        for (var entry : entries) {
            var reasons = new TreeSet<String>(); InputBinding binding = null; CompleteAnswerStatus status = null; ExecutionStatus execution = null;
            if (globalInvalid) reasons.add("MANIFEST_OR_FROZEN_SCOPE_UNTRUSTED");
            if (!groupIds.contains(entry.groupId())) reasons.add("UNKNOWN_GROUP");
            if (!m.requiredRecordsPerCase().containsKey(entry.caseId())) reasons.add("RECORD_OUTSIDE_DECLARED_SCOPE");
            ProcurementAnswerEvaluationV3 report = null;
            try { report = new ProcurementAnswerV3Reports().read(base.resolve(entry.v3Path())); }
            catch (IOException | RuntimeException e) { reasons.add("V3_READ_REJECTED"); }
            if (report != null) {
                binding = report.inputBinding(); status = report.completeAnswerStatus(); execution = report.assessmentExecutionStatus();
                if (binding == null || report.assessmentStage() == Stage.BLOCKED) reasons.add("FOUNDATION_ERROR_NOT_AN_ANSWER_FAILURE");
                else {
                    // Index actual v3 identities, not user-supplied labels or file names.
                    seenArtifacts.computeIfAbsent(binding.artifactSha256(), k -> new ArrayList<>()).add(checks.size());
                    seenRuns.computeIfAbsent(binding.runId(), k -> new ArrayList<>()).add(checks.size());
                    if (!entry.caseId().equals(binding.caseId()) || !entry.runId().equals(binding.runId())
                            || !entry.sessionId().equals(binding.sessionId()) || !entry.artifactSha256().equals(binding.artifactSha256()))
                        reasons.add("ENTRY_BINDING_MISMATCH");
                    if (!m.datasetVersion().equals(binding.datasetVersion()) || !m.datasetSha256().equals(binding.datasetSha256()))
                        reasons.add("DATASET_IDENTITY_MISMATCH");
                    if (!globalInvalid) {
                        try {
                            Path source = base.resolve(entry.artifactPath()); byte[] before = Files.readAllBytes(source);
                            STRICT.readTree(before);
                            var artifact = ProcurementEvaluationReports.readArtifact(source);
                            if (!Arrays.equals(before, Files.readAllBytes(source))) throw new IOException("CHANGED_INPUT");
                            if (!binding.artifactSha256().equals(ProcurementEvaluationReports.artifactHash(artifact))) reasons.add("ARTIFACT_BINDING_MISMATCH");
                            var c = definitions.get(binding.caseId());
                            if (c == null) reasons.add("UNKNOWN_FROZEN_CASE");
                            else if (!report.equals(new ProcurementCompleteAnswerEvaluator().evaluate(c, artifact, fixture, policy)))
                                reasons.add("RAW_REEVALUATION_MISMATCH");
                        } catch (IOException | RuntimeException e) { reasons.add("RAW_ARTIFACT_REJECTED"); }
                    }
                }
            }
            seenIds.computeIfAbsent(entry.recordId(), k -> new ArrayList<>()).add(checks.size());
            checks.add(new RecordCheck(entry.recordId(), entry.groupId(), entry.caseId(), eligibility(reasons.isEmpty()), binding, status, execution, List.copyOf(reasons)));
        }
        rejectDuplicates(checks, seenIds, "DUPLICATE_RECORD_ID");
        rejectDuplicates(checks, seenArtifacts, "DUPLICATE_ARTIFACT_REFERENCE");
        rejectDuplicates(checks, seenRuns, "RUN_ID_REUSED_INDEPENDENCE_UNPROVEN");
        for (var r : checks) for (String code : r.diagnostics()) diagnostics.add(new Diagnostic(code, r.groupId(), r.caseId(), r.recordId()));
        var slices = new ArrayList<CaseSlice>();
        for (var requirement : m.requiredRecordsPerCase().entrySet()) {
            Map<String, List<String>> ids = new TreeMap<>(); boolean ready = !globalInvalid;
            for (String group : groupIds) {
                var matched = checks.stream().filter(r -> r.groupId().equals(group) && r.caseId().equals(requirement.getKey())
                        && r.eligibility() == Eligibility.COMPARABLE).map(RecordCheck::recordId).toList();
                ids.put(group, matched);
                if (matched.size() != requirement.getValue()) {
                    ready = false;
                    diagnostics.add(new Diagnostic(matched.size() < requirement.getValue() ? "MISSING_REQUIRED_RECORDS" : "UNDECLARED_EXTRA_RECORDS",
                            group, requirement.getKey(), ""));
                }
                if (checks.stream().anyMatch(r -> r.groupId().equals(group) && r.caseId().equals(requirement.getKey()) && r.eligibility() == Eligibility.NOT_COMPARABLE)) ready = false;
            }
            slices.add(new CaseSlice(requirement.getKey(), eligibility(ready), ids));
        }
        var sortedDiagnostics = diagnostics.stream().distinct().sorted(Comparator.comparing(Diagnostic::code).thenComparing(Diagnostic::groupId)
                .thenComparing(Diagnostic::caseId).thenComparing(Diagnostic::recordId)).toList();
        var sortedGroups = m.groups().stream().sorted(Comparator.comparing(ProcurementExperimentManifest.Group::groupId)
                .thenComparing(g -> ProcurementEvaluationReports.canonicalJson(STRICT.valueToTree(g)))).toList();
        return new Result(VERSION, m.experimentId(), eligibility(sortedDiagnostics.isEmpty()),
                m.requiredRecordsPerCase().keySet().equals(definitions.keySet()) ? Scope.FULL_FROZEN_BENCHMARK : Scope.DECLARED_SUBSET,
                sortedGroups, List.copyOf(checks), List.copyOf(slices), sortedDiagnostics, LIMITS);
    }
    private static Eligibility eligibility(boolean good) { return good ? Eligibility.COMPARABLE : Eligibility.NOT_COMPARABLE; }
    private static void rejectDuplicates(List<RecordCheck> records, Map<String, List<Integer>> index, String reason) {
        for (var positions : index.values()) if (positions.size() > 1) for (int i : positions) {
            var r = records.get(i); var codes = new TreeSet<>(r.diagnostics()); codes.add(reason);
            records.set(i, new RecordCheck(r.recordId(), r.groupId(), r.caseId(), Eligibility.NOT_COMPARABLE,
                    r.inputBinding(), r.completeAnswerStatus(), r.assessmentExecutionStatus(), List.copyOf(codes)));
        }
    }
}
