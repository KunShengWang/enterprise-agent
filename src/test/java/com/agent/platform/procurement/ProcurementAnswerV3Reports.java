package com.agent.platform.procurement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** Offline file adapter only. Content addressing is integrity checking, not authentication. */
public final class ProcurementAnswerV3Reports {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @FunctionalInterface interface TempWriter { void write(Path path, byte[] bytes) throws IOException; }
    private final TempWriter writer;
    public ProcurementAnswerV3Reports() { this((path, bytes) -> Files.write(path, bytes)); }
    ProcurementAnswerV3Reports(TempWriter writer) { this.writer = java.util.Objects.requireNonNull(writer); }

    /** Select the frozen case from the raw Artifact; never accepts intermediate grading JSON. */
    public Path replay(Path artifact, Path fixture, Path policy, Path outputDirectory) throws IOException {
        byte[] source = Files.readAllBytes(artifact);
        try { JSON.readTree(source); } catch (RuntimeException e) { throw new IOException("INVALID_ARTIFACT_JSON", e); }
        var saved = ProcurementEvaluationReports.readArtifact(artifact);
        if (!Arrays.equals(source, Files.readAllBytes(artifact))) throw new IOException("ARTIFACT_CHANGED_DURING_READ");
        var definition = ProcurementEvaluationDataset.load().stream().filter(c -> c.caseId().equals(saved.caseId()))
                .findFirst().orElseThrow(() -> new IOException("UNKNOWN_FROZEN_CASE"));
        var result = new ProcurementCompleteAnswerEvaluator().evaluate(definition, saved,
                Files.readAllBytes(fixture), Files.readAllBytes(policy));
        byte[] bytes = encode(result);
        Path directory = outputDirectory.toAbsolutePath().normalize();
        Path target = directory.resolve(fileName(bytes));
        Files.createDirectories(directory);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Path pending = Files.createTempFile(directory, ".answer-v3-", ".pending");
        try {
            writer.write(pending, bytes);
            if (!Arrays.equals(bytes, Files.readAllBytes(pending))) throw new IOException("INCOMPLETE_V3_WRITE");
            // Same-directory hard-link publication exposes only a complete file and never replaces a target.
            // Unsupported filesystems fail closed; no overwrite-capable move fallback.
            try { Files.createLink(target, pending); }
            catch (UnsupportedOperationException unsupported) { throw new IOException("V3_PUBLICATION_REQUIRES_HARD_LINK_SUPPORT", unsupported); }
        } finally {
            Files.deleteIfExists(pending);
        }
        return target;
    }

    /** Reads only the independent v3 file identity and validates the complete record contract. */
    public ProcurementAnswerEvaluationV3 read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (!path.getFileName().toString().equals(fileName(bytes))) throw new IOException("V3_FILE_IDENTITY_MISMATCH");
        try {
            var tree = JSON.readTree(bytes);
            if (tree == null || !tree.isObject() || !ProcurementAnswerEvaluationV3.SCHEMA.equals(tree.path("schemaVersion").asText()))
                throw new IOException("UNSUPPORTED_V3_SCHEMA");
            // Bind original numeric tokens directly: a generic tree round-trip can change BigDecimal scale.
            var result = JSON.readValue(bytes, ProcurementAnswerEvaluationV3.class);
            if (!Arrays.equals(bytes, encode(result))) throw new IOException("NON_CANONICAL_V3_CONTENT");
            return result;
        } catch (RuntimeException e) { throw new IOException("INVALID_V3_CONTRACT", e); }
    }
    private static byte[] encode(ProcurementAnswerEvaluationV3 result) {
        return ProcurementEvaluationReports.canonicalJson(JSON.valueToTree(result)).getBytes(StandardCharsets.UTF_8);
    }
    private static String fileName(byte[] bytes) {
        return ProcurementAnswerEvaluationV3.SCHEMA + "-" + ProcurementEvaluationReports.sha256(bytes) + ".json";
    }
}
