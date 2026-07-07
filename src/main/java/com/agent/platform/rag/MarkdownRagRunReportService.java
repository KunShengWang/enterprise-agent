package com.agent.platform.rag;

import com.agent.platform.config.RagProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class MarkdownRagRunReportService implements RagRunReportService {

    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    private final RagProperties ragProperties;

    private final RagRunRecorder ragRunRecorder;

    public MarkdownRagRunReportService(RagProperties ragProperties,
                                       RagRunRecorder ragRunRecorder) {
        this.ragProperties = ragProperties;
        this.ragRunRecorder = ragRunRecorder;
    }

    @Override
    public RagRunReportFile generate(int limit) {
        int effectiveLimit = Math.max(1, limit);
        List<RagRunRecord> runs = ragRunRecorder.recent(effectiveLimit);
        RagRunStats stats = ragRunRecorder.stats(effectiveLimit);
        Instant createdAt = Instant.now();
        String reportId = "rag-report-" + FILE_TIME_FORMATTER.format(createdAt) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path reportDir = Path.of(ragProperties.getReportDir());
        Path reportPath = reportDir.resolve(reportId + ".md");
        try {
            Files.createDirectories(reportDir);
            Files.writeString(reportPath, buildMarkdown(reportId, createdAt, stats, runs), StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new PgVectorException("Failed to write RAG run report: " + reportPath, exception);
        }
        return new RagRunReportFile(reportId, reportPath.toAbsolutePath().toString(), runs.size(), stats, createdAt);
    }

    private String buildMarkdown(String reportId,
                                 Instant createdAt,
                                 RagRunStats stats,
                                 List<RagRunRecord> runs) {
        StringBuilder builder = new StringBuilder();
        builder.append("# RAG Run Report\n\n");
        builder.append("- reportId: `").append(reportId).append("`\n");
        builder.append("- createdAt: `").append(createdAt).append("`\n");
        builder.append("- totalRuns: `").append(stats.totalRuns()).append("`\n");
        builder.append("- hitRuns: `").append(stats.hitRuns()).append("`\n");
        builder.append("- hitRate: `").append(format(stats.hitRate())).append("`\n");
        builder.append("- averageDurationMs: `").append(format(stats.averageDurationMs())).append("`\n");
        builder.append("- averageRetrievedDocuments: `").append(format(stats.averageRetrievedDocuments())).append("`\n");
        builder.append("- runsByMode: `").append(stats.runsByMode()).append("`\n\n");
        builder.append("## Runs\n\n");
        if (runs.isEmpty()) {
            builder.append("No RAG runs recorded.\n");
            return builder.toString();
        }
        for (RagRunRecord run : runs) {
            appendRun(builder, run);
        }
        return builder.toString();
    }

    private void appendRun(StringBuilder builder, RagRunRecord run) {
        builder.append("### ").append(run.ragRunId()).append("\n\n");
        builder.append("- query: ").append(escapeMarkdown(run.query())).append("\n");
        builder.append("- mode: `").append(run.retrievalMode()).append("`\n");
        builder.append("- enoughEvidence: `").append(run.enoughEvidence()).append("`\n");
        builder.append("- topK: `").append(run.effectiveTopK()).append("`\n");
        builder.append("- retrievedDocuments: `").append(run.retrievedDocuments()).append("`\n");
        builder.append("- durationMs: `").append(run.durationMs()).append("`\n");
        builder.append("- createdAt: `").append(run.createdAt()).append("`\n\n");
        builder.append("| rank | source | chunkIndex | score | documentId |\n");
        builder.append("| --- | --- | --- | --- | --- |\n");
        for (RagHitSnapshot hit : run.hits()) {
            builder.append("| ")
                    .append(hit.rank()).append(" | ")
                    .append(escapeMarkdown(hit.source())).append(" | ")
                    .append(escapeMarkdown(String.valueOf(hit.chunkIndex()))).append(" | ")
                    .append(format(hit.score())).append(" | ")
                    .append(escapeMarkdown(hit.documentId())).append(" |\n");
        }
        builder.append('\n');
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String escapeMarkdown(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
