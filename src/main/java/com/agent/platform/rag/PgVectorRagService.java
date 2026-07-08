package com.agent.platform.rag;

import com.agent.platform.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@ConditionalOnProperty(prefix = "enterprise-agent.rag", name = "mode", havingValue = "pgvector", matchIfMissing = true)
public class PgVectorRagService implements RagService, RagCacheOperations {

    private final RagProperties ragProperties;

    private final EmbeddingClient embeddingClient;

    private final PgVectorRagRepository repository;

    private final RagReranker ragReranker;

    private final RagRunRecorder ragRunRecorder;

    private final ConcurrentMap<String, CachedRagResult> cache = new ConcurrentHashMap<>();

    private final AtomicLong cacheHits = new AtomicLong();

    private final AtomicLong cacheMisses = new AtomicLong();

    public PgVectorRagService(RagProperties ragProperties,
                              EmbeddingClient embeddingClient,
                              PgVectorRagRepository repository,
                              RagReranker ragReranker,
                              RagRunRecorder ragRunRecorder) {
        this.ragProperties = ragProperties;
        this.embeddingClient = embeddingClient;
        this.repository = repository;
        this.ragReranker = ragReranker;
        this.ragRunRecorder = ragRunRecorder;
    }

    @Override
    public RagResult retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return RagResult.empty(query);
        }
        long startNanos = System.nanoTime();
        int effectiveTopK = topK <= 0 ? ragProperties.getTopK() : topK;
        double minSimilarity = ragProperties.getMinSimilarity();
        String cacheKey = cacheKey(query, effectiveTopK, minSimilarity);
        RagResult cached = readCache(cacheKey, startNanos);
        if (cached != null) {
            ragRunRecorder.record(cached);
            return cached;
        }
        cacheMisses.incrementAndGet();
        // 把问题向量化
        double[] queryEmbedding = embeddingClient.embed(query);
        List<RetrievedDocument> documents = retrieveDocuments(
                query,
                queryEmbedding,
                effectiveTopK,
                minSimilarity
        );
        RagResult result = new RagResult(
                query,
                documents,
                !documents.isEmpty(),
                topK,
                effectiveTopK,
                minSimilarity,
                elapsedMs(startNanos),
                ragProperties.getHybrid().isEnabled() ? "hybrid" : "pgvector"
        );
        writeCache(cacheKey, result);
        ragRunRecorder.record(result);
        return result;
    }

    @Override
    public RagCacheStats cacheStats() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        return new RagCacheStats(
                ragProperties.getCache().isEnabled(),
                cache.size(),
                hits,
                misses,
                total == 0 ? 0 : (double) hits / total,
                Math.max(1, ragProperties.getCache().getTtlSeconds()),
                Math.max(1, ragProperties.getCache().getMaxEntries())
        );
    }

    @Override
    public void clearCache() {
        cache.clear();
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    private List<RetrievedDocument> retrieveDocuments(String query,
                                                      double[] queryEmbedding,
                                                      int effectiveTopK,
                                                      double minSimilarity) {
        if (!ragProperties.getHybrid().isEnabled()) {
            int vectorLimit = candidateLimit(effectiveTopK);
            List<RetrievedDocument> vectorDocuments = repository.search(queryEmbedding, vectorLimit, minSimilarity);
            return rerankIfEnabled(query, vectorDocuments, effectiveTopK);
        }
        RagProperties.Hybrid hybrid = ragProperties.getHybrid();
        int vectorLimit = candidateLimit(effectiveTopK);
        int keywordLimit = Math.max(effectiveTopK, hybrid.getKeywordCandidateLimit());
        // 通过 pgvector 计算用户问题向量与所有文档向量之间的相似度，返回最相似的前 K 个
        List<RetrievedDocument> vectorDocuments = repository.search(queryEmbedding, vectorLimit, minSimilarity);
        // 关键词检索
        List<RetrievedDocument> keywordDocuments = repository.keywordSearch(query, keywordLimit);
        List<RetrievedDocument> fusedCandidates = fuse(vectorDocuments, keywordDocuments, vectorLimit + keywordLimit, hybrid.getVectorWeight(), hybrid.getKeywordWeight());
        return rerankIfEnabled(query, fusedCandidates, effectiveTopK);
    }

    private List<RetrievedDocument> fuse(List<RetrievedDocument> vectorDocuments,
                                         List<RetrievedDocument> keywordDocuments,
                                         int topK,
                                         double vectorWeight,
                                         double keywordWeight) {
        double weightSum = vectorWeight + keywordWeight;
        double normalizedVectorWeight = weightSum <= 0 ? 0.7 : vectorWeight / weightSum;
        double normalizedKeywordWeight = weightSum <= 0 ? 0.3 : keywordWeight / weightSum;
        Map<String, HybridCandidate> candidates = new LinkedHashMap<>();
        for (RetrievedDocument document : vectorDocuments) {
            candidates.computeIfAbsent(document.documentId(), ignored -> new HybridCandidate()).vectorDocument = document;
        }
        for (RetrievedDocument document : keywordDocuments) {
            candidates.computeIfAbsent(document.documentId(), ignored -> new HybridCandidate()).keywordDocument = document;
        }
        List<RetrievedDocument> fused = new ArrayList<>();
        for (HybridCandidate candidate : candidates.values()) {
            RetrievedDocument base = candidate.vectorDocument != null ? candidate.vectorDocument : candidate.keywordDocument;
            double vectorScore = candidate.vectorDocument == null ? 0 : candidate.vectorDocument.score();
            double keywordScore = candidate.keywordDocument == null ? 0 : candidate.keywordDocument.score();
            double finalScore = normalizedVectorWeight * vectorScore + normalizedKeywordWeight * keywordScore;
            Map<String, Object> metadata = new HashMap<>(base.metadata());
            metadata.put("retrievalMode", "hybrid");
            metadata.put("vectorScore", vectorScore);
            metadata.put("keywordScore", keywordScore);
            metadata.put("finalScore", finalScore);
            metadata.put("matchedByVector", candidate.vectorDocument != null);
            metadata.put("matchedByKeyword", candidate.keywordDocument != null);
            fused.add(new RetrievedDocument(base.documentId(), base.title(), base.content(), finalScore, metadata));
        }
        List<RetrievedDocument> sorted = fused.stream()
                .sorted(Comparator.comparingDouble(RetrievedDocument::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
        List<RetrievedDocument> ranked = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            RetrievedDocument document = sorted.get(index);
            Map<String, Object> metadata = new HashMap<>(document.metadata());
            metadata.put("rank", index + 1);
            ranked.add(new RetrievedDocument(document.documentId(), document.title(), document.content(), document.score(), metadata));
        }
        return ranked;
    }

    private List<RetrievedDocument> rerankIfEnabled(String query, List<RetrievedDocument> candidates, int topK) {
        if (!ragProperties.getRerank().isEnabled()) {
            return candidates.stream().limit(Math.max(1, topK)).toList();
        }
        return ragReranker.rerank(query, candidates, topK);
    }

    private int candidateLimit(int effectiveTopK) {
        return Math.max(effectiveTopK, effectiveTopK * Math.max(1, ragProperties.getHybrid().getVectorCandidateMultiplier()));
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private RagResult readCache(String cacheKey, long startNanos) {
        if (!ragProperties.getCache().isEnabled()) {
            return null;
        }
        CachedRagResult cached = cache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (cached.expired()) {
            cache.remove(cacheKey);
            return null;
        }
        cacheHits.incrementAndGet();
        RagResult result = cached.result();
        return new RagResult(
                result.query(),
                result.documents(),
                result.enoughEvidence(),
                result.requestedTopK(),
                result.effectiveTopK(),
                result.minSimilarity(),
                elapsedMs(startNanos),
                result.retrievalMode() + ":cache-hit"
        );
    }

    private void writeCache(String cacheKey, RagResult result) {
        if (!ragProperties.getCache().isEnabled() || result == null) {
            return;
        }
        long ttlMillis = Math.max(1, ragProperties.getCache().getTtlSeconds()) * 1000;
        cache.put(cacheKey, new CachedRagResult(result, System.currentTimeMillis() + ttlMillis));
        trimCache();
    }

    private void trimCache() {
        int maxEntries = Math.max(1, ragProperties.getCache().getMaxEntries());
        if (cache.size() <= maxEntries) {
            return;
        }
        cache.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(CachedRagResult::expiresAtMillis)))
                .limit(Math.max(1, cache.size() - maxEntries))
                .map(Map.Entry::getKey)
                .forEach(cache::remove);
    }

    private String cacheKey(String query, int effectiveTopK, double minSimilarity) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return String.join("|",
                normalizedQuery,
                "topK=" + effectiveTopK,
                "min=" + minSimilarity,
                "hybrid=" + ragProperties.getHybrid().isEnabled(),
                "rerank=" + ragProperties.getRerank().isEnabled(),
                "vw=" + ragProperties.getHybrid().getVectorWeight(),
                "kw=" + ragProperties.getHybrid().getKeywordWeight()
        );
    }

    private static class HybridCandidate {

        private RetrievedDocument vectorDocument;

        private RetrievedDocument keywordDocument;
    }

    private record CachedRagResult(RagResult result, long expiresAtMillis) {

        boolean expired() {
            return System.currentTimeMillis() > expiresAtMillis;
        }
    }
}
