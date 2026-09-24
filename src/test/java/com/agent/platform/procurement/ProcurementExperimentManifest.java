package com.agent.platform.procurement;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Experiment labels are declarations, never model-call attestations. Paths are manifest-relative. */
public record ProcurementExperimentManifest(String schemaVersion, String experimentId,
        String datasetVersion, String datasetSha256, String fixturePath, String policyPath,
        Map<String, Integer> requiredRecordsPerCase, List<Group> groups, List<Entry> records) {
    public static final String VERSION = "procurement-experiment-manifest-v1";
    private static final JsonMapper JSON = JsonMapper.builder().enable(
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public record Group(String groupId, String model, String promptVersion, String agentVersion, Map<String, String> configuration) {
        public Group {
            for (String s : List.of(groupId, model, promptVersion, agentVersion)) text(s);
            configuration = Collections.unmodifiableMap(new TreeMap<>(configuration));
            configuration.forEach((k, v) -> { text(k); text(v); });
        }
    }
    public record Entry(String recordId, String groupId, String caseId, String runId, String sessionId,
                        String artifactSha256, String artifactPath, String v3Path) {
        public Entry {
            for (String s : List.of(recordId, groupId, caseId, runId, sessionId, artifactPath, v3Path)) text(s);
            hash(artifactSha256);
        }
    }
    public ProcurementExperimentManifest {
        if (!VERSION.equals(schemaVersion)) throw new IllegalArgumentException("UNSUPPORTED_MANIFEST_VERSION");
        for (String s : List.of(experimentId, datasetVersion, fixturePath, policyPath)) text(s);
        hash(datasetSha256);
        requiredRecordsPerCase = Collections.unmodifiableMap(new TreeMap<>(requiredRecordsPerCase));
        if (requiredRecordsPerCase.isEmpty()) throw new IllegalArgumentException("EMPTY_DECLARED_SCOPE");
        requiredRecordsPerCase.forEach((id, n) -> { text(id); if (n == null || n < 1) throw new IllegalArgumentException("INVALID_RECORD_REQUIREMENT"); });
        groups = List.copyOf(groups); records = List.copyOf(records);
        if (groups.size() < 2) throw new IllegalArgumentException("TWO_GROUPS_REQUIRED");
    }
    public static ProcurementExperimentManifest read(Path path) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(path);
            JSON.readTree(bytes); // strict duplicate detection before record binding
            var manifest = JSON.readValue(bytes, ProcurementExperimentManifest.class);
            if (manifest == null) throw new IOException("INVALID_EXPERIMENT_MANIFEST: null root");
            return manifest;
        } catch (RuntimeException e) { throw new IOException("INVALID_EXPERIMENT_MANIFEST", e); }
    }
    private static void text(String s) { if (s == null || s.isBlank()) throw new IllegalArgumentException("REQUIRED_MANIFEST_TEXT"); }
    private static void hash(String s) { if (s == null || !s.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("INVALID_MANIFEST_HASH"); }
}
