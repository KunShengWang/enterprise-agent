package com.agent.platform.procurement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.*;

/** File IO and comparison only; regrade has no execution hook. */
public final class ProcurementEvaluationReports {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ProcurementEvaluationReports() { }

    public static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(MAPPER.valueToTree(value), "")));
    }

    public static ExecutionArtifact readArtifact(Path path) throws IOException {
        try {
            JsonNode raw = MAPPER.readTree(Files.readString(path));
            require(raw != null && raw.isObject() && SCHEMA.equals(raw.path("schemaVersion").asText()), "unsupported artifact schema");
            // Validate BEFORE production record constructors can default currency/state/timestamps.
            for (String field : List.of("caseId", "datasetSha256", "userMessage")) text(raw, field);
            JsonNode runtime = raw.path("runtime");
            for (String field : List.of("runId", "sessionId", "state", "stopReason")) text(runtime, field);
            require(runtime.path("answer").isString(), "missing raw answer");
            require(runtime.path("events").isArray() && raw.path("observedEvents").isArray(), "missing event capture");
            require(raw.path("metadata").isObject() && raw.path("captureLimitations").isArray(), "missing capture metadata");
            JsonNode state = raw.path("finalCase").path("state");
            for (String field : List.of("productCategory", "productDescription", "quantity", "budget", "currency",
                    "requiredDeliveryDays", "hardConstraints", "preferences", "excludedSuppliers", "missingFields", "currentPhase")) {
                require(state.has(field) && !state.path(field).isNull(), "missing raw state field: " + field);
            }
            text(state, "currency");
            require(state.path("quantity").isIntegralNumber() && state.path("requiredDeliveryDays").isIntegralNumber()
                    && state.path("budget").isNumber(), "invalid raw numeric business fields");
            require(raw.path("toolExecutions").isArray(), "missing tool execution capture");
            for (var tool : raw.path("toolExecutions")) {
                for (String field : List.of("toolCallId", "runId", "toolName", "state", "createdAt", "updatedAt")) text(tool, field);
                require(tool.path("attempt").isIntegralNumber() && tool.path("request").path("arguments").isObject(), "missing tool attempt/arguments");
                require(tool.has("result"), "missing raw tool result field");
                if (!tool.path("result").isNull()) require(tool.path("result").path("success").isBoolean(), "missing tool success flag");
            }
            return MAPPER.treeToValue(raw, ExecutionArtifact.class);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid execution artifact: " + invalid.getMessage(), invalid);
        }
    }

    private static void text(JsonNode node, String field) {
        require(node.path(field).isString() && !node.path(field).asText().isBlank(), "missing text field: " + field);
    }

    public static Report regrade(List<EvaluationCase> cases, List<ExecutionArtifact> artifacts) {
        if (cases.isEmpty()) throw new IllegalArgumentException("empty dataset");
        EvaluationCase first = cases.get(0);
        Map<String, ExecutionArtifact> byId = new LinkedHashMap<>();
        for (var a : artifacts) {
            if (byId.putIfAbsent(a.caseId(), a) != null) throw new IllegalArgumentException("duplicate artifact caseId");
        }
        Set<String> seen = new HashSet<>();
        List<EvaluationResult> results = new ArrayList<>();
        var grader = new ProcurementDeterministicGrader();
        for (var c : cases) {
            if (!seen.add(c.caseId()) || !first.datasetSha256().equals(c.datasetSha256())
                    || !first.datasetVersion().equals(c.datasetVersion())
                    || !first.fixtureSha256().equals(c.fixtureSha256())) throw new IllegalArgumentException("mixed/duplicate dataset");
            var a = byId.remove(c.caseId());
            results.add(a == null ? new EvaluationResult(c.caseId(), "", ProcurementDeterministicGrader.VERSION,
                    Status.ERROR, List.of(new Check("artifactAvailability", Status.ERROR, "Missing artifact", List.of("/"))))
                    : grader.grade(c, a));
        }
        if (!byId.isEmpty()) throw new IllegalArgumentException("artifacts contain unknown cases");
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        for (var status : Status.values()) counts.put(status, 0L);
        results.forEach(r -> r.checks().forEach(c -> counts.merge(c.status(), 1L, Long::sum)));
        return new Report(SCHEMA, first.datasetVersion(), first.datasetSha256(), first.fixtureSha256(), ProcurementDeterministicGrader.VERSION,
                "STRUCTURED_CHECKS_ONLY; not final-answer accuracy, task success or production safety", results, counts);
    }

    /** Changed grader is comparable only on exactly the same artifacts; never label it model improvement. */
    public static Comparison compare(Report baseline, Report candidate) {
        validateReport(baseline);
        validateReport(candidate);
        if (!baseline.schemaVersion().equals(candidate.schemaVersion())
                || !baseline.datasetVersion().equals(candidate.datasetVersion())
                || !baseline.datasetSha256().equals(candidate.datasetSha256())
                || !baseline.fixtureSha256().equals(candidate.fixtureSha256())
                || !baseline.scope().equals(candidate.scope())) {
            return new Comparison("INCOMPARABLE", List.of("schema/dataset/fixture/scope differs"));
        }
        Map<String, EvaluationResult> left = index(baseline), right = index(candidate);
        if (!left.keySet().equals(right.keySet())) return new Comparison("INCOMPARABLE", List.of("case coverage differs"));
        if (left.values().stream().anyMatch(r -> r.artifactSha256().isBlank())
                || right.values().stream().anyMatch(r -> r.artifactSha256().isBlank()))
            return new Comparison("INCOMPARABLE", List.of("missing artifact fingerprint"));
        boolean sameArtifacts = left.keySet().stream().allMatch(id -> !left.get(id).artifactSha256().isBlank()
                && left.get(id).artifactSha256().equals(right.get(id).artifactSha256()));
        boolean sameGrader = baseline.graderVersion().equals(candidate.graderVersion());
        if (!sameGrader && !sameArtifacts) return new Comparison("INCOMPARABLE", List.of("grader and artifacts both changed"));
        List<String> changes = new ArrayList<>();
        for (String id : left.keySet()) {
            Map<String, Status> previous = metrics(left.get(id)), next = metrics(right.get(id));
            Set<String> names = new TreeSet<>(previous.keySet());
            names.addAll(next.keySet());
            for (String name : names) {
                if (previous.get(name) != next.get(name)) changes.add(id + "/" + name + ": "
                        + previous.get(name) + " -> " + next.get(name));
            }
        }
        return new Comparison(!sameGrader ? "GRADER_CHANGE_ON_SAME_ARTIFACTS"
                : sameArtifacts ? (changes.isEmpty() ? "REPLAY_CHECK" : "INCONSISTENT_REPLAY")
                : "ARTIFACT_CHANGE_NOT_CAUSAL_MODEL_CLAIM", changes);
    }

    static Status aggregate(List<Check> checks) {
        if (checks.stream().anyMatch(c -> c.status() == Status.ERROR)) return Status.ERROR;
        if (checks.stream().anyMatch(c -> c.status() == Status.FAIL)) return Status.FAIL;
        if (checks.stream().anyMatch(c -> c.status() == Status.PASS)) return Status.PASS;
        return checks.stream().anyMatch(c -> c.status() == Status.SKIP) ? Status.SKIP : Status.NOT_APPLICABLE;
    }

    private static void validateReport(Report report) {
        require(report != null && SCHEMA.equals(report.schemaVersion()) && !report.results().isEmpty(), "empty/unknown report");
        require(report.datasetSha256().matches("[a-f0-9]{64}") && report.fixtureSha256().matches("[a-f0-9]{64}"), "invalid report fingerprint");
        require(!report.datasetVersion().isBlank() && !report.graderVersion().isBlank()
                && report.scope().startsWith("STRUCTURED_CHECKS_ONLY"), "invalid report scope/version");
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        for (Status status : Status.values()) counts.put(status, 0L);
        for (var row : report.results()) {
            require(!row.caseId().isBlank() && !row.checks().isEmpty(), "empty result");
            require(row.artifactSha256() != null && (row.artifactSha256().matches("[a-f0-9]{64}")
                    || row.artifactSha256().isEmpty() && row.structuredStatus() == Status.ERROR), "invalid artifact fingerprint");
            metrics(row);
            for (var check : row.checks()) {
                require(check.status() != null && check.metric() != null && !check.metric().isBlank(), "invalid check");
                counts.merge(check.status(), 1L, Long::sum);
            }
            require(row.structuredStatus() == aggregate(row.checks()), "inconsistent structured status");
        }
        require(counts.equals(report.checkCounts()), "inconsistent report counts");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static Map<String, EvaluationResult> index(Report report) {
        Map<String, EvaluationResult> result = new TreeMap<>();
        for (var row : report.results()) {
            if (!row.graderVersion().equals(report.graderVersion()) || result.putIfAbsent(row.caseId(), row) != null)
                throw new IllegalArgumentException("invalid report grader/case identity");
        }
        return result;
    }

    private static Map<String, Status> metrics(EvaluationResult result) {
        Map<String, Status> map = new TreeMap<>();
        result.checks().forEach(c -> {
            if (map.putIfAbsent(c.metric(), c.status()) != null) throw new IllegalArgumentException("duplicate metric");
        });
        return map;
    }

    public static String artifactHash(ExecutionArtifact artifact) {
        return sha256(MAPPER.writeValueAsBytes(sorted(MAPPER.valueToTree(artifact), "")));
    }

    static String canonicalJson(JsonNode value) {
        return MAPPER.writeValueAsString(sorted(value, ""));
    }

    // Stable across JSON object/map property ordering. Array order is deliberately evidence-significant.
    private static JsonNode sorted(JsonNode node, String path) {
        if (node.isObject()) {
            var result = MAPPER.createObjectNode();
            node.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> result.set(e.getKey(), sorted(e.getValue(), path + "/" + e.getKey())));
            return result;
        }
        if (node.isArray()) {
            var result = MAPPER.createArrayNode();
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(sorted(item, path + "/*")));
            if (Set.of("/finalCase/state/excludedSuppliers", "/finalCase/appliedInputIds").contains(path)) {
                items.sort(Comparator.comparing(JsonNode::asText));
            }
            items.forEach(result::add);
            return result;
        }
        return node;
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
