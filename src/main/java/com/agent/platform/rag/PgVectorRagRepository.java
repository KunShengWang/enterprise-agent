package com.agent.platform.rag;

import com.agent.platform.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Repository
@ConditionalOnProperty(prefix = "enterprise-agent.rag", name = "mode", havingValue = "pgvector", matchIfMissing = true)
public class PgVectorRagRepository {

    private final RagProperties ragProperties;

    private final KeywordQueryTokenizer keywordQueryTokenizer;

    public PgVectorRagRepository(RagProperties ragProperties,
                                 KeywordQueryTokenizer keywordQueryTokenizer) {
        this.ragProperties = ragProperties;
        this.keywordQueryTokenizer = keywordQueryTokenizer;
    }

    public void ensureSchema() {
        int dimension = validatedDimension();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            // 查看 vector 扩展是否存在
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            // 查看 rag_chunk 表是否存在
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS rag_chunk (
                        chunk_id TEXT PRIMARY KEY,
                        source TEXT NOT NULL,
                        chunk_index INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        embedding vector(%d) NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    )
                    """.formatted(dimension));
            statement.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunk_source ON rag_chunk(source)");
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to initialize pgvector schema. Check PostgreSQL, pgvector extension, and DB credentials.", exception);
        }
    }

    public void save(List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            save(connection, chunks);
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to save RAG chunks into pgvector", exception);
        }
    }

    public RagSaveReport replaceBySources(List<String> sources, List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new RagSaveReport(0, 0);
        }
        ensureSchema();// ① 确保表/扩展存在（会建表）
        Set<String> effectiveSources = normalizeSources(sources);
        if (effectiveSources.isEmpty()) {
            effectiveSources = sourcesFromChunks(chunks);
        }
        try (Connection connection = openConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int deletedRows = deleteSources(connection, effectiveSources);// ② 先按 source 删除旧 chunk
                int savedRows = save(connection, chunks);// ③ 批量插入新 chunk
                connection.commit();
                connection.setAutoCommit(oldAutoCommit);
                return new RagSaveReport(deletedRows, savedRows);
            }
            catch (SQLException exception) {
                connection.rollback();// ④ 失败回滚
                connection.setAutoCommit(oldAutoCommit);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to replace RAG chunks by source", exception);
        }
    }

    public int deleteBySource(String source) {
        ensureSchema();
        Set<String> sources = normalizeSources(List.of(source));
        if (sources.isEmpty()) {
            return 0;
        }
        try (Connection connection = openConnection()) {
            return deleteSources(connection, sources);
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to delete RAG chunks by source", exception);
        }
    }

    private int save(Connection connection, List<EmbeddedChunk> chunks) throws SQLException {
        String sql = """
                INSERT INTO rag_chunk(chunk_id, source, chunk_index, content, content_hash, embedding, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::vector, NOW())
                ON CONFLICT (chunk_id) DO UPDATE SET
                    source = EXCLUDED.source,
                    chunk_index = EXCLUDED.chunk_index,
                    content = EXCLUDED.content,
                    content_hash = EXCLUDED.content_hash,
                    embedding = EXCLUDED.embedding,
                    updated_at = NOW()
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (EmbeddedChunk embeddedChunk : chunks) {
                statement.setString(1, embeddedChunk.chunk().chunkId());
                statement.setString(2, embeddedChunk.chunk().source());
                statement.setInt(3, embeddedChunk.chunk().chunkIndex());
                statement.setString(4, embeddedChunk.chunk().content());
                statement.setString(5, embeddedChunk.contentHash());
                statement.setString(6, vectorLiteral(embeddedChunk.embedding()));
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            int affectedRows = 0;
            for (int result : results) {
                if (result > 0) {
                    affectedRows += result;
                }
                else if (result == Statement.SUCCESS_NO_INFO) {
                    affectedRows++;
                }
            }
            return affectedRows;
        }
    }

    private int deleteSources(Connection connection, Set<String> sources) throws SQLException {
        if (sources.isEmpty()) {
            return 0;
        }
        String sql = "DELETE FROM rag_chunk WHERE source = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String source : sources) {
                statement.setString(1, source);
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            int deletedRows = 0;
            for (int result : results) {
                if (result > 0) {
                    deletedRows += result;
                }
            }
            return deletedRows;
        }
    }

    /**
     * 通过 pgvector 计算用户问题向量与所有文档向量之间的相似度，返回最相似的前 K 个
     */
    public List<RetrievedDocument> search(double[] queryEmbedding, int topK, double minSimilarity) {
        ensureSchema();
        // vectorLiteral 把 double[] 转成 pgvector 接受的字符串字面量
        String vector = vectorLiteral(queryEmbedding);
        String sql = """
                SELECT chunk_id, source, chunk_index, content, content_hash,
                       (embedding <=> ?::vector) AS distance -- ① 计算距离
                FROM rag_chunk
                ORDER BY embedding <=> ?::vector -- ② 按距离升序排
                LIMIT ? -- ③ 取前 K 条
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            // 给 ？ 占位符传参数
            statement.setString(1, vector);
            statement.setString(2, vector);
            statement.setInt(3, Math.max(1, topK));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RetrievedDocument> documents = new ArrayList<>();
                int rank = 0;
                while (resultSet.next()) {
                    // 余弦距离
                    double distance = resultSet.getDouble("distance");
                    // 余弦相似度
                    double similarity = 1.0 - distance;
                    // 阈值过滤，低于此值的不返回
                    if (similarity < minSimilarity) {
                        continue;
                    }
                    rank++;
                    documents.add(new RetrievedDocument(
                            resultSet.getString("chunk_id"),
                            resultSet.getString("source"),
                            resultSet.getString("content"),
                            similarity,
                            Map.of(
                                    "rank", rank,
                                    "source", resultSet.getString("source"),
                                    "chunkIndex", resultSet.getInt("chunk_index"),
                                    "distance", distance,
                                    "similarity", similarity,
                                    "contentHash", resultSet.getString("content_hash"),
                                    "retrievalMode", "pgvector"
                            )
                    ));
                }
                return documents;
            }
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to search RAG chunks from pgvector", exception);
        }
    }

    public List<RetrievedDocument> keywordSearch(String query, int limit) {
        ensureSchema();
        // 简单分词，例如：["退款", "流程"]
        List<String> tokens = keywordQueryTokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        // SELECT FROM rag_chunk WHERE source LIKE '%退款%' OR content LIKE '%退款%' OR source LIKE '%流程%' OR content LIKE '%流程%'
        String whereClause = keywordWhereClause(tokens.size());
        String sql = """
                SELECT chunk_id, source, chunk_index, content, content_hash
                FROM rag_chunk
                WHERE %s
                LIMIT ?
                """.formatted(whereClause);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameterIndex = 1;
            for (String token : tokens) {
                String pattern = "%" + escapeLike(token.toLowerCase(Locale.ROOT)) + "%";
                statement.setString(parameterIndex++, pattern);
                statement.setString(parameterIndex++, pattern);
            }
            statement.setInt(parameterIndex, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RetrievedDocument> documents = new ArrayList<>();
                while (resultSet.next()) {
                    String source = resultSet.getString("source");
                    String content = resultSet.getString("content");
                    // 算每个 chunk 的命中率 (命中 token 数 / 总 token 数)
                    double keywordScore = keywordScore(tokens, source + "\n" + content);
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", source);
                    metadata.put("chunkIndex", resultSet.getInt("chunk_index"));
                    metadata.put("contentHash", resultSet.getString("content_hash"));
                    metadata.put("keywordScore", keywordScore);
                    metadata.put("keywordTokens", tokens);
                    metadata.put("retrievalMode", "keyword");
                    documents.add(new RetrievedDocument(
                            resultSet.getString("chunk_id"),
                            source,
                            content,
                            keywordScore,
                            metadata
                    ));
                }
                return rankKeywordDocuments(documents);
            }
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to keyword search RAG chunks from pgvector", exception);
        }
    }

    public RagCorpusStats stats() {
        ensureSchema();
        String sql = """
                SELECT source, COUNT(*) AS chunks
                FROM rag_chunk
                GROUP BY source
                ORDER BY source
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<RagCorpusStats.SourceStats> sources = new ArrayList<>();
            long totalChunks = 0;
            while (resultSet.next()) {
                long chunks = resultSet.getLong("chunks");
                totalChunks += chunks;
                sources.add(new RagCorpusStats.SourceStats(resultSet.getString("source"), chunks));
            }
            return new RagCorpusStats("pgvector", totalChunks, sources);
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to read RAG corpus stats from pgvector", exception);
        }
    }

    public RagVectorIndexReport createVectorIndex() {
        ensureSchema();
        long startNanos = System.nanoTime();
        String indexType = normalizedIndexType();
        String indexName = "idx_rag_chunk_embedding_" + indexType;
        String sql = switch (indexType) {
            case "ivfflat" -> """
                    CREATE INDEX IF NOT EXISTS %s
                    ON rag_chunk USING ivfflat (embedding vector_cosine_ops)
                    WITH (lists = %d)
                    """.formatted(indexName, validatedIvfflatLists());
            case "hnsw" -> """
                    CREATE INDEX IF NOT EXISTS %s
                    ON rag_chunk USING hnsw (embedding vector_cosine_ops)
                    """.formatted(indexName);
            default -> throw new IllegalArgumentException("Unsupported pgvector index type: " + indexType);
        };
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return new RagVectorIndexReport("pgvector", indexName, indexType, compactSql(sql), elapsedMs(startNanos));
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to create pgvector index. Check pgvector version and database privileges.", exception);
        }
    }

    private Connection openConnection() throws SQLException {
        RagProperties.Datasource datasource = ragProperties.getDatasource();
        return DriverManager.getConnection(datasource.getUrl(), datasource.getUsername(), datasource.getPassword());
    }

    private String normalizedIndexType() {
        String type = ragProperties.getIndex().getType();
        if (type == null || type.isBlank()) {
            return "hnsw";
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private int validatedIvfflatLists() {
        int lists = ragProperties.getIndex().getIvfflatLists();
        if (lists <= 0 || lists > 10000) {
            throw new IllegalArgumentException("Invalid ivfflat lists: " + lists);
        }
        return lists;
    }

    private String compactSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private String keywordWhereClause(int tokenCount) {
        List<String> clauses = new ArrayList<>();
        for (int index = 0; index < tokenCount; index++) {
            clauses.add("(LOWER(source) LIKE ? ESCAPE '\\' OR LOWER(content) LIKE ? ESCAPE '\\')");
        }
        return String.join(" OR ", clauses);
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 算每个 chunk 的命中率 (命中 token 数 / 总 token 数)
     */
    private double keywordScore(List<String> tokens, String text) {
        if (tokens.isEmpty()) {
            return 0;
        }
        String normalizedText = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : tokens) {
            if (normalizedText.contains(token.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return (double) hits / tokens.size();
    }

    private List<RetrievedDocument> rankKeywordDocuments(List<RetrievedDocument> documents) {
        List<RetrievedDocument> sorted = documents.stream()
                .sorted(Comparator.comparingDouble(RetrievedDocument::score).reversed())
                .toList();
        List<RetrievedDocument> ranked = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            RetrievedDocument document = sorted.get(index);
            Map<String, Object> metadata = new HashMap<>(document.metadata());
            metadata.put("keywordRank", index + 1);
            ranked.add(new RetrievedDocument(document.documentId(), document.title(), document.content(), document.score(), metadata));
        }
        return ranked;
    }

    private Set<String> normalizeSources(List<String> sources) {
        Set<String> normalized = new LinkedHashSet<>();
        if (sources == null) {
            return normalized;
        }
        for (String source : sources) {
            if (source != null && !source.isBlank()) {
                normalized.add(source.trim());
            }
        }
        return normalized;
    }

    private Set<String> sourcesFromChunks(List<EmbeddedChunk> chunks) {
        Set<String> sources = new LinkedHashSet<>();
        for (EmbeddedChunk chunk : chunks) {
            sources.add(chunk.chunk().source());
        }
        return sources;
    }

    private int validatedDimension() {
        int dimension = ragProperties.getEmbedding().getDimension();
        if (dimension <= 0 || dimension > 8192) {
            throw new IllegalArgumentException("Invalid embedding dimension: " + dimension);
        }
        return dimension;
    }

    /**
     * vectorLiteral 把 double[] 转成 pgvector 接受的字符串字面量
     */
    private String vectorLiteral(double[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Embedding vector must not be empty");
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            double value = vector[index];
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Embedding vector contains non-finite value at index " + index);
            }
            if (index > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.append(']').toString();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
