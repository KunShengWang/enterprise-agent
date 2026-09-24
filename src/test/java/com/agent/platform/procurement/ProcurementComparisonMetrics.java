package com.agent.platform.procurement;

import java.math.BigDecimal;
import java.util.*;

/** Descriptive metrics only; all sources are retained. Not a persisted report or a scoring contract. */
public final class ProcurementComparisonMetrics {
    private ProcurementComparisonMetrics() { }
    public static final String VERSION = "procurement-comparison-metrics-v1";
    public static final String LIMITS = ProcurementComparisonInputs.LIMITS + "; UNPAIRED_DESCRIPTIVE_DIFFERENCES; NOT_CAUSAL; NO_COMPOSITE_SCORE";
    public enum State { READY, BLOCKED }
    public enum Calculation { CALCULABLE, NOT_COMPUTABLE }
    public enum Unit { RECORD, CHECK, CLAIM, ELEMENT, FRAGMENT }
    public enum Key {
        COMPLETE_ANSWER, SCALAR_FACT, RELATION_DIRECTION, RELATION_DIFFERENCE,
        SCALAR_EVIDENCE, RELATION_DIRECTION_EVIDENCE, RELATION_DIFFERENCE_EVIDENCE,
        SCALAR_VERIFIED, RELATION_VERIFIED, REQUIRED_ELEMENTS, EFFECTIVE_COVERAGE, ASSESSMENT_COVERAGE, SCORING_EXECUTION;
        public Unit unit() { return switch (this) {
            case COMPLETE_ANSWER, SCORING_EXECUTION -> Unit.RECORD;
            case SCALAR_VERIFIED, RELATION_VERIFIED -> Unit.CLAIM;
            case REQUIRED_ELEMENTS -> Unit.ELEMENT;
            case EFFECTIVE_COVERAGE, ASSESSMENT_COVERAGE -> Unit.FRAGMENT;
            default -> Unit.CHECK;
        }; }
        public List<String> statuses() { return switch (this) {
            case COMPLETE_ANSWER -> List.of("PASS", "FAIL", "NEEDS_REVIEW", "ERROR");
            case REQUIRED_ELEMENTS -> List.of("PRESENT", "MISSING", "UNRESOLVED", "UNAVAILABLE");
            case EFFECTIVE_COVERAGE -> List.of("PARSED_SCALAR", "PARSED_RELATION", "UNKNOWN_CONTENT", "UNRESOLVED_BUSINESS");
            case ASSESSMENT_COVERAGE -> List.of("RESOLVED", "BLOCKED");
            case SCORING_EXECUTION -> List.of("COMPLETE", "ERROR");
            default -> List.of("PASS", "FAIL", "SKIP", "ERROR");
        }; }
        public Set<String> successes() { return switch (this) {
            case REQUIRED_ELEMENTS -> Set.of("PRESENT");
            case EFFECTIVE_COVERAGE -> Set.of("PARSED_SCALAR", "PARSED_RELATION");
            case ASSESSMENT_COVERAGE -> Set.of("RESOLVED");
            case SCORING_EXECUTION -> Set.of("COMPLETE");
            default -> Set.of("PASS");
        }; }
    }
    public record Ratio(long numerator, long denominator, Calculation calculation, BigDecimal value, String reason) { }
    public record Source(String recordId, String caseId, String ref, String status, String reason, List<String> evidencePaths) {
        public Source { evidencePaths = List.copyOf(evidencePaths); }
    }
    public record Exclusion(Source source, String reason) { }
    public record Bucket(String status, long count, Ratio proportion, List<Source> sources) {
        public Bucket { sources = List.copyOf(sources); if (count != sources.size()) throw new IllegalArgumentException("COUNT_SOURCE_MISMATCH"); }
    }
    public record Metric(Key key, Unit unit, long denominator, Ratio successRatio, List<Bucket> buckets, List<Exclusion> exclusions) {
        public Metric { buckets = List.copyOf(buckets); exclusions = List.copyOf(exclusions); }
    }
    public record CaseMetrics(String groupId, String caseId, int expectedRecords, int validRecords, List<String> recordIds, List<Metric> metrics) {
        public CaseMetrics { recordIds = List.copyOf(recordIds); metrics = List.copyOf(metrics); }
    }
    public record CaseRatio(String caseId, Ratio ratio) { }
    public record Macro(Calculation calculation, BigDecimal value, List<CaseRatio> cases, List<String> uncomputableCases) {
        public Macro { cases = List.copyOf(cases); uncomputableCases = List.copyOf(uncomputableCases); }
    }
    public record GroupMetric(String groupId, Key metric, Metric micro, Macro macro) { }
    /** right minus left; sources locate diagnostic differences, never paired trials or causal effects. */
    public record Difference(String caseId, String leftGroup, String rightGroup, Key metric, String status,
            long leftCount, long rightCount, long countDelta, Ratio leftProportion, Ratio rightProportion,
            Calculation calculation, BigDecimal proportionDelta, List<Source> leftSources, List<Source> rightSources) {
        public Difference { leftSources = List.copyOf(leftSources); rightSources = List.copyOf(rightSources); }
    }
    public record Result(String version, String manifestSha256, ProcurementComparisonInputs.Result inputValidation, State state,
            List<String> blockingDiagnostics, Map<String, ProcurementAnswerEvaluationV3> recordEvidence,
            List<CaseMetrics> cases, List<GroupMetric> groupMetrics, List<Difference> differences, String limitations) {
        public Result {
            blockingDiagnostics = List.copyOf(blockingDiagnostics); recordEvidence = Collections.unmodifiableMap(new TreeMap<>(recordEvidence));
            cases = List.copyOf(cases); groupMetrics = List.copyOf(groupMetrics); differences = List.copyOf(differences);
            if (state == State.BLOCKED && (!cases.isEmpty() || !groupMetrics.isEmpty() || !differences.isEmpty() || !recordEvidence.isEmpty()))
                throw new IllegalArgumentException("BLOCKED_INPUT_HAS_FORMAL_METRICS");
        }
    }
}
