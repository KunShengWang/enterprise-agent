package com.agent.platform.procurement;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Independent comparison namespace; no report-writing path accepts external summary counts. */
public final class ProcurementComparisonReports {
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();
    @FunctionalInterface interface TempWriter { void write(Path path, byte[] bytes) throws IOException; }
    private final TempWriter writer;
    public ProcurementComparisonReports() { this((p, b) -> Files.write(p, b)); }
    ProcurementComparisonReports(TempWriter writer) { this.writer = Objects.requireNonNull(writer); }

    public Path replay(Path manifestPath, Path outputDirectory) throws IOException {
        var m = ProcurementExperimentManifest.read(manifestPath);
        var result = new ProcurementComparisonAnalyzer().analyze(manifestPath);
        if (result.state() != ProcurementComparisonMetrics.State.READY)
            throw new IOException("COMPARISON_NOT_READY: " + result.blockingDiagnostics() + "; " + result.inputValidation().diagnostics());
        if (!m.equals(ProcurementExperimentManifest.read(manifestPath))) throw new IOException("MANIFEST_CHANGED_DURING_REPLAY");
        final byte[] bytes;
        try { bytes = ProcurementComparisonReport.encode(ProcurementComparisonReport.capture(m, result)); }
        catch (RuntimeException e) { throw new IOException("INVALID_COMPARISON_RESULT", e); }
        // Exercise the full strict binding before publishing, including BigDecimal token preservation.
        decode(bytes);
        Path directory = outputDirectory.toAbsolutePath().normalize(); Files.createDirectories(directory);
        Path target = directory.resolve(fileName(bytes));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Path pending = Files.createTempFile(directory, ".comparison-", ".pending");
        try {
            writer.write(pending, bytes);
            if (!Arrays.equals(bytes, Files.readAllBytes(pending))) throw new IOException("INCOMPLETE_COMPARISON_WRITE");
            try { Files.createLink(target, pending); }
            catch (UnsupportedOperationException e) { throw new IOException("COMPARISON_PUBLICATION_REQUIRES_HARD_LINK_SUPPORT", e); }
        } finally { Files.deleteIfExists(pending); }
        return target;
    }
    public ProcurementComparisonReport read(Path reportPath) throws IOException {
        byte[] bytes = Files.readAllBytes(reportPath);
        if (!reportPath.getFileName().toString().equals(fileName(bytes))) throw new IOException("COMPARISON_FILE_IDENTITY_MISMATCH");
        return decode(bytes);
    }
    private static ProcurementComparisonReport decode(byte[] bytes) throws IOException {
        try {
            var tree = JSON.readTree(bytes);
            if (tree == null || !tree.isObject() || !ProcurementComparisonReport.SCHEMA.equals(tree.path("schemaVersion").asText()))
                throw new IOException("UNSUPPORTED_COMPARISON_SCHEMA");
            // Direct original-token binding avoids a tree conversion changing BigDecimal scale.
            var report = JSON.readValue(bytes, ProcurementComparisonReport.class);
            if (!Arrays.equals(bytes, ProcurementComparisonReport.encode(report))) throw new IOException("NON_CANONICAL_COMPARISON_CONTENT");
            return report;
        } catch (RuntimeException e) { throw new IOException("INVALID_COMPARISON_CONTRACT", e); }
    }
    static String fileName(byte[] bytes) { return ProcurementComparisonReport.SCHEMA + "-" + ProcurementEvaluationReports.sha256(bytes) + ".json"; }
}
