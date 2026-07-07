package com.agent.platform.rag;

import com.agent.platform.config.RagProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class TextChunker {

    private final RagProperties ragProperties;

    public TextChunker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public List<DocumentChunk> split(LoadedDocument document) {
        // chunkSize 控制每个文本块多大
        int chunkSize = Math.max(100, ragProperties.getChunkSize());
        // chunkOverlap 控制相邻文本块之间重叠多少内容
        int overlap = Math.max(0, Math.min(ragProperties.getChunkOverlap(), chunkSize / 2));
        String content = normalize(document.content());
        List<DocumentChunk> chunks = new ArrayList<>();
        int index = 0;
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + chunkSize);
            String chunkText = content.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new DocumentChunk(chunkId(document.source(), index, chunkText), document.source(), index, chunkText));
                index++;
            }
            if (end == content.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private String normalize(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String chunkId(String source, int chunkIndex, String content) {
        return sanitize(source) + "#" + chunkIndex + "-" + sha256(content).substring(0, 12);
    }

    public String contentHash(String content) {
        return sha256(content == null ? "" : content);
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._/-]", "_");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
