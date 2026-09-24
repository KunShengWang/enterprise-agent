package com.agent.platform.procurement;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.agent.platform.procurement.ProcurementEvaluation.*;
import static com.agent.platform.procurement.ProcurementAnswerEvaluation.Verdict;
import static com.agent.platform.procurement.ProcurementAnswerEvidence.*;
import static com.agent.platform.procurement.ProcurementAnswerRelationExtractor.Claim;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.runtime.ToolExecutionState;

/** Offline relation-only scoring. Authority and captured evidence are never merged. */
public final class ProcurementAnswerRelationGrader {
    public static final String VERSION = "procurement-relation-grader-v1.1";
    public static final String SCHEMA = "procurement-answer-relations-v1";
    private static final JsonMapper STRICT_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record Result(Claim claim, Verdict factualDirection, Verdict factualDifference,
                         Verdict evidenceDirection, Verdict evidenceDifference) {
        public Result {
            Objects.requireNonNull(claim);
            var all = List.of(factualDirection, factualDifference, evidenceDirection, evidenceDifference);
            if (!claim.unresolvedReason().isEmpty()) {
                if (all.stream().anyMatch(v -> v.status() != Status.SKIP))
                    throw new IllegalArgumentException("UNRESOLVED_RELATION_MUST_SKIP");
            } else {
                var directions = Set.of(Status.PASS, Status.FAIL, Status.SKIP, Status.ERROR);
                if (!directions.contains(factualDirection.status()) || !directions.contains(evidenceDirection.status()))
                    throw new IllegalArgumentException("INVALID_DIRECTION_STATUS");
                if (claim.difference() == null ? factualDifference.status() != Status.NOT_APPLICABLE || evidenceDifference.status() != Status.NOT_APPLICABLE
                        : !directions.contains(factualDifference.status()) || !directions.contains(evidenceDifference.status()))
                    throw new IllegalArgumentException("INVALID_DIFFERENCE_STATUS");
            }
        }
    }
    public record Evaluation(String schemaVersion, String caseId, String runId, String datasetVersion,
                             String datasetSha256, String fixtureSha256, String artifactSha256, String answerSha256,
                             String extractorVersion, String graderVersion, Status relationStatus, Status completeAnswerStatus,
                             List<Result> results, List<String> errors, String scope) {
        public Evaluation {
            results = List.copyOf(results); errors = List.copyOf(errors);
            if (!SCHEMA.equals(schemaVersion) || completeAnswerStatus != Status.SKIP
                    || !ProcurementAnswerRelationExtractor.VERSION.equals(extractorVersion) || !VERSION.equals(graderVersion)
                    || relationStatus != summarize(results, errors))
                throw new IllegalArgumentException("RELATION_ONLY_CONTRACT");
        }
    }
    private record Index(List<Fact> facts, Set<String> eligible, String eligiblePath) { }
    private record Pair(Verdict direction, Verdict difference) { }
    private record Operand(BigDecimal value, String currency, List<String> paths) { }
    private static final class Missing extends RuntimeException { Missing(String s) { super(s); } }
    private static final class Conflict extends RuntimeException { Conflict(String s) { super(s); } }
    private static final Set<String> REQUIRED_CHECKS = Set.of("requirement.productCategoryPresent", "requirement.productDescriptionPresent",
            "requirement.quantity", "requirement.budget", "requirement.currency", "requirement.requiredDeliveryDays",
            "requirement.hardConstraints", "requirement.preferences", "requirement.excludedSuppliers");

    public Evaluation grade(EvaluationCase c, ExecutionArtifact a, byte[] fixture) {
        List<Result> results = new ArrayList<>(); List<String> errors = new ArrayList<>();
        var structured = new ProcurementDeterministicGrader().grade(c, a);
        String answer = a == null || a.runtime() == null || a.runtime().answer() == null ? "" : a.runtime().answer();
        try {
            // v2 reports both input validation and outcome parsing under artifactValidity.
            // The complete requirement check set proves identity validation completed; outcome payloads
            // are checked separately below so a missing eligible array is not mistaken for an empty set.
            boolean identityCompleted = ProcurementDeterministicGrader.VERSION.equals("procurement-deterministic-v2")
                    && REQUIRED_CHECKS.stream().allMatch(metric -> structured.checks().stream().filter(check -> check.metric().equals(metric)).count() == 1);
            if (!identityCompleted) throw new IllegalArgumentException("ARTIFACT_INVALID: " + structured.checks().stream()
                    .filter(check -> check.metric().equals("artifactValidity")).map(Check::reason).toList());
            ReferenceFacts base = reference(c, fixture);
            var truthFacts = new ArrayList<>(base.facts());
            truthFacts.add(new Fact("case", "BUDGET", number(c.expectedCase(), "budget").toPlainString(),
                    c.expectedCase().path("currency").asText(), "benchmark:/expectedCase/budget"));
            Index truth = new Index(List.copyOf(truthFacts), ids(c.expected().path("eligibleSupplierIds"), false), "benchmark:/expected/eligibleSupplierIds");
            Index evidence = null; String evidenceError = "";
            try {
                var failedRequirements = structured.checks().stream().filter(check -> REQUIRED_CHECKS.contains(check.metric()) && check.status() != Status.PASS).toList();
                if (!failedRequirements.isEmpty()) throw new IllegalArgumentException("CASE_REQUIREMENT_SCOPE_MISMATCH: " + failedRequirements);
                evidence = observedIndex(c, a, base);
            }
            catch (RuntimeException invalid) { evidenceError = "INVALID_OBSERVED_EVIDENCE: " + invalid.getMessage(); errors.add(evidenceError); }
            for (var claim : new ProcurementAnswerRelationExtractor().extract(answer)) {
                if (!claim.unresolvedReason().isEmpty()) {
                    var skip = verdict(Status.SKIP, claim.unresolvedReason(), List.of());
                    results.add(new Result(claim, skip, skip, skip, skip)); continue;
                }
                Pair factual = judge(claim, truth, truth, c, false);
                Pair support = evidence == null ? new Pair(verdict(Status.ERROR, evidenceError, List.of()),
                        claim.difference() == null ? na() : verdict(Status.ERROR, evidenceError, List.of()))
                        : judge(claim, evidence, truth, c, true);
                results.add(new Result(claim, factual.direction(), factual.difference(), support.direction(), support.difference()));
            }
        } catch (IOException | RuntimeException invalid) { errors.add(invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage()); }
        Status status = summarize(results, errors);
        return new Evaluation(SCHEMA, c == null ? "UNKNOWN" : c.caseId(), a == null || a.runtime() == null ? "UNKNOWN" : a.runtime().runId(),
                c == null ? "" : c.datasetVersion(), c == null ? "" : c.datasetSha256(), c == null ? "" : c.fixtureSha256(), structured.artifactSha256(),
                ProcurementEvaluationReports.sha256(answer.getBytes(StandardCharsets.UTF_8)), ProcurementAnswerRelationExtractor.VERSION, VERSION,
                status, Status.SKIP, results, errors, "RELATIONS_ONLY; no completeness, effective coverage, preference ranking or complete-answer verdict; support means captured evidence, not proven model-visible context");
    }

    private static Status summarize(List<Result> results, List<String> errors) {
        var verdicts = results.stream().flatMap(r -> java.util.stream.Stream.of(r.factualDirection(), r.factualDifference(), r.evidenceDirection(), r.evidenceDifference())).toList();
        return !errors.isEmpty() || verdicts.stream().anyMatch(v -> v.status() == Status.ERROR) ? Status.ERROR
                : verdicts.stream().anyMatch(v -> v.status() == Status.FAIL) ? Status.FAIL
                : verdicts.isEmpty() || verdicts.stream().anyMatch(v -> v.status() == Status.SKIP) ? Status.SKIP : Status.PASS;
    }

    private Index observedIndex(EvaluationCase c, ExecutionArtifact a, ReferenceFacts reference) {
        // Existing reader validates raw numeric/provenance fields without SupplierOffer reconstruction.
        var facts = new ArrayList<>(observed(a, reference).facts());
        JsonNode state = JSON.valueToTree(a.finalCase().state());
        facts.add(new Fact("case", "BUDGET", number(state, "budget").toPlainString(), state.path("currency").asText(), "artifact:/finalCase/state/budget"));
        Set<String> eligible = null; String path = ""; int searches = 0;
        for (int i = 0; i < a.toolExecutions().size(); i++) {
            var record = a.toolExecutions().get(i);
            if (record.state() != ToolExecutionState.SUCCEEDED
                    || record.result() == null || !record.result().success()) continue;
            if (!Set.of(ProcurementToolCatalog.SUPPLIER_SEARCH, ProcurementToolCatalog.RECOMMENDATION_FINALIZE).contains(record.toolName())) continue;
            var payload = STRICT_JSON.readTree(record.result().content());
            if (payload.has("caseId") && !a.finalCase().caseId().equals(payload.path("caseId").asText()))
                throw new IllegalArgumentException("FOREIGN_PAYLOAD_CASE");
            if (!record.toolName().equals(ProcurementToolCatalog.SUPPLIER_SEARCH)) continue;
            if (++searches > 1) throw new IllegalArgumentException("AMBIGUOUS_SUCCESSFUL_SEARCHES");
            path = "artifact:/toolExecutions/" + i + "/result/content#/eligibleSuppliers";
            // Missing is unavailable, not empty. Malformed present fields are infrastructure errors.
            if (payload.has("eligibleSuppliers")) eligible = ids(payload.path("eligibleSuppliers"), true);
            if (eligible != null) {
                for (String field : List.of("quantity", "budget", "currency", "requiredDeliveryDays", "hardConstraints", "excludedSuppliers")) {
                    var expected = c.expectedCase().path(field); var actual = state.path(field);
                    boolean equal = field.equals("excludedSuppliers") ? ids(expected, false).equals(ids(actual, false))
                            : expected.isNumber() && actual.isNumber() ? expected.decimalValue().compareTo(actual.decimalValue()) == 0 : expected.equals(actual);
                    if (!equal) throw new IllegalArgumentException("SEARCH_CONSTRAINT_SCOPE_MISMATCH:" + field);
                }
                if (!payload.has("offers") || !payload.has("evidence")) eligible = null;
                else if (!payload.path("offers").isArray() || !payload.path("evidence").isArray())
                    throw new IllegalArgumentException("INVALID_SEARCH_SUPPORT_STRUCTURE");
            }
        }
        return new Index(List.copyOf(facts), eligible, path);
    }

    private Pair judge(Claim claim, Index index, Index truth, EvaluationCase c, boolean support) {
        List<String> paths = new ArrayList<>();
        try {
            if (claim.property().equals("ELIGIBLE_SET")) {
                if (index.eligible() == null) throw new Missing("NO_EXPLICIT_SUCCESSFUL_SEARCH_ELIGIBILITY");
                paths.add(index.eligiblePath());
                if (support) {
                    // Search conclusion is evidence, never the source of the expected set.
                    if (!index.eligible().equals(truth.eligible())) throw new Conflict("SEARCH_SET_CONFLICTS_WITH_FROZEN_SET");
                    for (String id : index.eligible()) operand(index, truth, id, "TOTAL_PRICE", true, paths);
                    operand(index, truth, "case", "BUDGET", true, paths);
                    operand(index, truth, "case", "REQUIRED_DELIVERY", true, paths);
                }
                boolean match = claim.operator().equals("EMPTY") ? index.eligible().isEmpty() : index.eligible().equals(Set.of(claim.left()));
                return new Pair(verdict(match ? Status.PASS : Status.FAIL, "EXPLICIT_ELIGIBLE_SET_CHECK", paths), na());
            }
            Operand left = operand(index, truth, claim.left(), claim.property(), support, paths);
            String rightProperty = switch (claim.scope()) { case "CASE_BUDGET" -> "BUDGET"; case "CASE_DELIVERY" -> "REQUIRED_DELIVERY"; default -> claim.property(); };
            Operand right = operand(index, truth, claim.right(), rightProperty, support, paths);
            if (claim.property().equals("TOTAL_PRICE")) {
                String currency = claim.currency().equals("CASE_CURRENCY") ? c.expectedCase().path("currency").asText() : claim.currency();
                if (!left.currency().equals(right.currency()) || !left.currency().equals(currency)) throw new Conflict("CURRENCY_MISMATCH");
            }
            int comparison = left.value().compareTo(right.value());
            boolean direction = switch (claim.operator()) { case "LT" -> comparison < 0; case "GT" -> comparison > 0; case "LE" -> comparison <= 0; default -> throw new IllegalArgumentException("UNSUPPORTED_OPERATOR"); };
            var d = verdict(direction ? Status.PASS : Status.FAIL, "OPERAND_DIRECTION_CHECK", paths);
            // Magnitude is deliberately independent of direction, even when the direction is wrong.
            var difference = claim.difference() == null ? na() : verdict(left.value().subtract(right.value()).abs().compareTo(claim.difference()) == 0
                    ? Status.PASS : Status.FAIL, "ABSOLUTE_DIFFERENCE_CHECK", paths);
            return new Pair(d, difference);
        } catch (Missing missing) {
            var v = verdict(support ? Status.SKIP : Status.FAIL, missing.getMessage(), paths);
            return new Pair(v, claim.difference() == null ? na() : v);
        } catch (Conflict conflict) {
            var v = verdict(Status.FAIL, conflict.getMessage(), paths); return new Pair(v, claim.difference() == null ? na() : v);
        }
    }

    private Operand operand(Index index, Index truth, String subject, String property, boolean support, List<String> paths) {
        var facts = index.facts().stream().filter(f -> f.subject().equals(subject) && f.property().equals(property)).toList();
        if (facts.isEmpty()) throw new Missing("MISSING_OPERAND:" + subject + ":" + property);
        paths.addAll(facts.stream().map(Fact::path).toList());
        var first = facts.get(0);
        for (var f : facts) {
            if (!first.currency().equals(f.currency()) || new BigDecimal(first.value()).compareTo(new BigDecimal(f.value())) != 0)
                throw new Conflict("CONFLICTING_OPERAND_VALUES:" + subject + ":" + property);
            if (support && truth.facts().stream().noneMatch(t -> t.subject().equals(subject) && t.property().equals(property)
                    && t.currency().equals(f.currency()) && new BigDecimal(t.value()).compareTo(new BigDecimal(f.value())) == 0))
                throw new Conflict("CAPTURED_OPERAND_CONFLICTS_WITH_REFERENCE:" + subject + ":" + property);
        }
        return new Operand(new BigDecimal(first.value()), first.currency(), facts.stream().map(Fact::path).toList());
    }
    private static Set<String> ids(JsonNode array, boolean objects) {
        if (!array.isArray()) throw new IllegalArgumentException("INVALID_ELIGIBLE_ARRAY");
        Set<String> ids = new TreeSet<>();
        for (var item : array) {
            var id = objects ? item.path("supplierId") : item;
            if (!id.isString() || id.asText().isBlank() || !ids.add(id.asText())) throw new IllegalArgumentException("INVALID_OR_DUPLICATE_ELIGIBLE_ID");
        }
        return Collections.unmodifiableSet(ids);
    }
    private static BigDecimal number(JsonNode node, String field) {
        if (!node.path(field).isNumber()) throw new IllegalArgumentException("INVALID_NUMBER:" + field);
        return node.path(field).decimalValue();
    }
    private static Verdict verdict(Status status, String reason, List<String> paths) { return new Verdict(status, reason, paths.stream().distinct().sorted().toList()); }
    private static Verdict na() { return verdict(Status.NOT_APPLICABLE, "NO_DIFFERENCE_ASSERTED", List.of()); }

    public Evaluation replay(EvaluationCase c, Path artifact, Path fixture, Path output) throws IOException {
        var result = grade(c, ProcurementEvaluationReports.readArtifact(artifact), Files.readAllBytes(fixture));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createFile(output);
        ProcurementEvaluationReports.write(output, result);
        return result;
    }
}
