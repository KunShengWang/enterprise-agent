package com.agent.platform.rag;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class LocalDocumentLoader {

    private static final long MAX_TEXT_DOCUMENT_BYTES = 5 * 1024 * 1024;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".txt", ".adoc", ".rst", ".csv", ".json", ".jsonl",
            ".yaml", ".yml", ".xml", ".html", ".htm", ".log", ".sql",
            ".java", ".kt", ".py", ".js", ".ts"
    );

    public List<LoadedDocument> load(Path documentDir) {
        if (documentDir == null || !Files.exists(documentDir)) {
            throw new IllegalArgumentException("RAG document directory does not exist: " + documentDir);
        }
        // 递归遍历目录树
        try (var paths = Files.walk(documentDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> new LoadedDocument(documentDir.relativize(path).toString().replace('\\', '/'), read(path)))
                    .filter(document -> !document.content().isBlank())
                    .toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to load RAG documents from " + documentDir, exception);
        }
    }

    private boolean isSupported(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(fileName::endsWith) && withinSizeLimit(path);
    }

    private boolean withinSizeLimit(Path path) {
        try {
            return Files.size(path) <= MAX_TEXT_DOCUMENT_BYTES;
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect RAG document: " + path, exception);
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read UTF-8 text RAG document (binary PDF/DOCX requires a dedicated parser): " + path,
                    exception
            );
        }
    }
}
