package com.agent.platform.procurement;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import static com.agent.platform.procurement.ProcurementComparisonInputs.*;
import static com.agent.platform.procurement.ProcurementComparisonMetrics.*;

/** Self-contained saved comparison. Consistency is not execution authenticity or fresh reevaluation. */
public record ProcurementComparisonReport(String schemaVersion, String reportVersion, String inputSha256,
        Input input, ProcurementComparisonInputs.Result inputValidation,
        Map<String, ProcurementAnswerEvaluationV3> recordEvidence, Statistics statistics, String limitations) {
    public static final String SCHEMA = "procurement-comparison-report-v1";
    public static final String VERSION = "procurement-comparison-report-writer-v1";
    public static final String LIMITS = ProcurementComparisonMetrics.LIMITS + "; INTERNAL_CONSISTENCY_ONLY; READ_IS_NOT_CURRENT_INPUT_REPLAY";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Paths are locators, not portable experiment identity. Original Step 2 path hash is not persisted. */
    public record Input(String manifestVersion, String experimentId, String datasetVersion, String datasetSha256,
            Map<String, Integer> requiredRecordsPerCase, List<ProcurementExperimentManifest.Group> groups, List<Entry> records) {
        public Input {
            requiredRecordsPerCase = Collections.unmodifiableMap(new TreeMap<>(requiredRecordsPerCase));
            groups = List.copyOf(groups); records = List.copyOf(records);
        }
    }
    public record Entry(String recordId, String groupId, String caseId, String runId, String sessionId,
                        String artifactSha256, String v3Sha256) { }
    public record Statistics(String metricsVersion, List<CaseMetrics> cases, List<GroupMetric> groupMetrics, List<Difference> differences) {
        public Statistics { cases = List.copyOf(cases); groupMetrics = List.copyOf(groupMetrics); differences = List.copyOf(differences); }
    }

    public ProcurementComparisonReport {
        require(SCHEMA.equals(schemaVersion) && VERSION.equals(reportVersion), "UNSUPPORTED_COMPARISON_REPORT");
        require(LIMITS.equals(limitations), "REPORT_LIMITATIONS_MISMATCH");
        recordEvidence = Collections.unmodifiableMap(new TreeMap<>(recordEvidence));
        require(input != null && inputSha256 != null && inputSha256.equals(hash(input)), "INPUT_IDENTITY_MISMATCH");
        require(ProcurementExperimentManifest.VERSION.equals(input.manifestVersion()), "MANIFEST_VERSION_MISMATCH");
        require(inputValidation != null && inputValidation.eligibility() == Eligibility.COMPARABLE
                && ProcurementComparisonInputs.VERSION.equals(inputValidation.version())
                && ProcurementComparisonInputs.LIMITS.equals(inputValidation.limitations()) && inputValidation.diagnostics().isEmpty(), "COMPARISON_NOT_READY");
        require(input.experimentId().equals(inputValidation.experimentId()) && input.groups().equals(inputValidation.declaredGroups()), "DECLARATION_MISMATCH");
        // Reuse the manifest declaration constructor, without resolving any external paths.
        new ProcurementExperimentManifest(input.manifestVersion(), input.experimentId(), input.datasetVersion(), input.datasetSha256(),
                "fixture-by-content", "policy-by-content", input.requiredRecordsPerCase(), input.groups(),
                input.records().stream().map(e -> new ProcurementExperimentManifest.Entry(e.recordId(), e.groupId(), e.caseId(), e.runId(), e.sessionId(), e.artifactSha256(), "artifact-by-content", "v3-by-content")).toList());
        try {
            var definitions = ProcurementEvaluationDataset.load();
            var frozenIds = new TreeSet<String>(); definitions.forEach(c -> frozenIds.add(c.caseId()));
            require(input.datasetVersion().equals(definitions.get(0).datasetVersion()) && input.datasetSha256().equals(definitions.get(0).datasetSha256()), "DATASET_MISMATCH");
            require(frozenIds.containsAll(input.requiredRecordsPerCase().keySet()), "UNKNOWN_REPORT_CASE");
            require(inputValidation.scope() == (frozenIds.equals(input.requiredRecordsPerCase().keySet()) ? Scope.FULL_FROZEN_BENCHMARK : Scope.DECLARED_SUBSET), "SCOPE_MISMATCH");
            var groups = new TreeSet<String>();
            for (var g : input.groups()) require(groups.add(g.groupId()), "DUPLICATE_GROUP");
            require(input.groups().stream().map(ProcurementExperimentManifest.Group::groupId).toList().equals(List.copyOf(groups)), "GROUP_ORDER");
            var ids = new TreeSet<String>(); var artifacts = new HashSet<String>(); var runs = new HashSet<String>();
            var checks = new ArrayList<RecordCheck>();
            for (var e : input.records()) {
                require(ids.add(e.recordId()) && artifacts.add(e.artifactSha256()) && runs.add(e.runId()), "DUPLICATE_SAVED_RECORD");
                require(groups.contains(e.groupId()) && input.requiredRecordsPerCase().containsKey(e.caseId()), "RECORD_OUTSIDE_SCOPE");
                var v = recordEvidence.get(e.recordId()); require(v != null && v.assessmentStage() == ProcurementAnswerEvaluationV3.Stage.ASSESSED, "UNTRUSTED_SAVED_RECORD");
                var b = v.inputBinding();
                var definition = definitions.stream().filter(c -> c.caseId().equals(e.caseId())).findFirst().orElseThrow();
                require(b.caseId().equals(e.caseId()) && b.runId().equals(e.runId()) && b.sessionId().equals(e.sessionId())
                        && b.artifactSha256().equals(e.artifactSha256()) && b.datasetSha256().equals(input.datasetSha256())
                        && b.datasetVersion().equals(input.datasetVersion()) && b.fixtureSha256().equals(definition.fixtureSha256())
                        && hash(v).equals(e.v3Sha256()), "RECORD_BINDING_MISMATCH");
                checks.add(new RecordCheck(e.recordId(), e.groupId(), e.caseId(), Eligibility.COMPARABLE, b, v.completeAnswerStatus(), v.assessmentExecutionStatus(), List.of()));
            }
            require(input.records().stream().map(Entry::recordId).toList().equals(List.copyOf(ids)) && recordEvidence.keySet().equals(ids), "RECORD_SET_OR_ORDER");
            require(checks.equals(inputValidation.records()), "RECORD_CHECK_MISMATCH");
            var slices = new ArrayList<CaseSlice>();
            for (var c : input.requiredRecordsPerCase().entrySet()) {
                var members = new TreeMap<String, List<String>>();
                for (String g : groups) {
                    var matched = input.records().stream().filter(e -> e.caseId().equals(c.getKey()) && e.groupId().equals(g)).map(Entry::recordId).toList();
                    require(matched.size() == c.getValue(), "SAVED_RECORD_COUNT_MISMATCH"); members.put(g, matched);
                }
                slices.add(new CaseSlice(c.getKey(), Eligibility.COMPARABLE, members));
            }
            require(slices.equals(inputValidation.cases()), "CASE_MEMBERSHIP_MISMATCH");
            // Only version-pinned, bundled Policy definitions; never read the current experiment's files.
            var policyBytes = ProcurementAnswerPolicy.resourceBytes();
            require(ProcurementEvaluationReports.sha256(policyBytes).equals(ProcurementAnswerCompletenessGrader.POLICY_SHA256), "BUNDLED_POLICY_MISMATCH");
            var elements = new TreeMap<String, List<String>>();
            for (var c : JSON.readTree(policyBytes).path("cases")) {
                var names = new ArrayList<String>(); c.path("elements").forEach(e -> names.add(e.path("elementId").asText()));
                elements.put(c.path("caseId").asText(), names);
            }
            // Validate stored statistics using the exact Step 2 reducer. Never replace them or rerun graders.
            var expected = ProcurementComparisonAnalyzer.summarize(inputSha256, inputValidation, recordEvidence, input.requiredRecordsPerCase(), elements);
            require(statistics != null && statistics.equals(statistics(expected)), "STATISTICS_OR_SOURCE_MISMATCH");
        } catch (java.io.IOException e) { throw new IllegalArgumentException("REPORT_DEFINITION_UNAVAILABLE", e); }
    }
    static ProcurementComparisonReport capture(ProcurementExperimentManifest m, ProcurementComparisonMetrics.Result r) {
        require(r.state() == State.READY, "COMPARISON_NOT_READY");
        var entries = m.records().stream().map(e -> new Entry(e.recordId(), e.groupId(), e.caseId(), e.runId(), e.sessionId(), e.artifactSha256(), hash(r.recordEvidence().get(e.recordId()))))
                .sorted(Comparator.comparing(Entry::recordId)).toList();
        var input = new Input(m.schemaVersion(), m.experimentId(), m.datasetVersion(), m.datasetSha256(), m.requiredRecordsPerCase(), r.inputValidation().declaredGroups(), entries);
        return new ProcurementComparisonReport(SCHEMA, VERSION, hash(input), input, r.inputValidation(), r.recordEvidence(), statistics(r), LIMITS);
    }
    static Statistics statistics(ProcurementComparisonMetrics.Result r) { return new Statistics(r.version(), r.cases(), r.groupMetrics(), r.differences()); }
    static byte[] encode(Object value) { return ProcurementEvaluationReports.canonicalJson(JSON.valueToTree(value)).getBytes(StandardCharsets.UTF_8); }
    static String hash(Object value) { return ProcurementEvaluationReports.sha256(encode(value)); }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
