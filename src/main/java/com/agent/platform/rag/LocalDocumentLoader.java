package com.agent.platform.rag;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Component
public class LocalDocumentLoader {

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

    /**
     * TODO 只支撑.md和.txt两种格式，后续使用 Spring AI 的 DocumentReader 扩展其他格式的文件
     */
    private boolean isSupported(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".md") || fileName.endsWith(".txt");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to read RAG document: " + path, exception);
        }
    }
}
