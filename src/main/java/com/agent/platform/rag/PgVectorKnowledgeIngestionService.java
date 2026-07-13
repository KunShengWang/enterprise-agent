package com.agent.platform.rag;

import com.agent.platform.config.RagProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PgVectorKnowledgeIngestionService implements KnowledgeIngestionService {

    private final RagProperties ragProperties;

    private final LocalDocumentLoader documentLoader;

    private final TextChunker textChunker;

    private final EmbeddingClient embeddingClient;

    private final PgVectorRagRepository repository;

    public PgVectorKnowledgeIngestionService(RagProperties ragProperties,
                                             LocalDocumentLoader documentLoader,
                                             TextChunker textChunker,
                                             EmbeddingClient embeddingClient,
                                             PgVectorRagRepository repository) {
        this.ragProperties = ragProperties;
        this.documentLoader = documentLoader;
        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.repository = repository;
    }

    /**
     * 加载文档，向量化等并存入 postgresql
     * rag 的流程：文档加载 -> 切分 -> 向量化 -> 存储
     */
    @Override
    public IngestionReport ingestConfiguredDirectory() {
        long startNanos = System.nanoTime();
        // 默认 data/rag-docs 目录
        Path documentDir = Path.of(ragProperties.getDocumentDir());
        // 加载目录下文档
        List<LoadedDocument> documents = documentLoader.load(documentDir);
        List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
        // 记录文档有多少个 chunks
        Map<String, Integer> chunksBySource = new LinkedHashMap<>();
        long embeddingStartNanos = System.nanoTime();
        for (LoadedDocument document : documents) {
            // 文档切分
            for (DocumentChunk chunk : textChunker.split(document)) {
                embeddedChunks.add(new EmbeddedChunk(
                        chunk,
                        embeddingClient.embed(chunk.content()),// 调用 embedding API 向量化
                        textChunker.contentHash(chunk.content())// 内容哈希去重
                ));
                chunksBySource.merge(chunk.source(), 1, Integer::sum);
            }
        }
        long embeddingDurationMs = elapsedMs(embeddingStartNanos);
        long databaseStartNanos = System.nanoTime();
        RagSaveReport saveReport = repository.replaceBySources(new ArrayList<>(chunksBySource.keySet()), embeddedChunks);// 删除旧 chunks，写入新 chunks 到 pgvector
        long databaseDurationMs = elapsedMs(databaseStartNanos);
        return new IngestionReport(
                "pgvector",
                documentDir.toString(),
                documents.size(),
                embeddedChunks.size(),
                saveReport.deletedChunks(),
                saveReport.savedChunks(),
                embeddingDurationMs,
                databaseDurationMs,
                elapsedMs(startNanos),
                documents.stream().map(LoadedDocument::source).toList(),
                sourceReports(chunksBySource)
        );
    }

    private List<SourceIngestionReport> sourceReports(Map<String, Integer> chunksBySource) {
        return chunksBySource.entrySet().stream()
                .map(entry -> new SourceIngestionReport(entry.getKey(), entry.getValue()))
                .toList();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
