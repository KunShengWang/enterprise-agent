package com.agent.platform.memory;

import com.agent.platform.config.MemoryProperties;
import com.agent.platform.config.RagProperties;
import com.agent.platform.rag.EmbeddingClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL/pgvector long-term memory. Short-term conversation history and summaries are owned by AgentTimelineStore.
 */
@Service
public class JdbcMemoryService implements MemoryService {

    private static final String DEFAULT_CONVERSATION_ID = "default-conversation";
    private static final Set<String> AUTOMATIC_PROFILE_KEYS = Set.of("language", "response_style");

    private final MemoryProperties properties;
    private final RagProperties ragProperties;
    private final MemoryExtractor memoryExtractor;
    private final MemoryRecallScorer lexicalFallback;
    private final ObjectProvider<EmbeddingClient> embeddingClientProvider;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcMemoryService(MemoryProperties properties,
                             RagProperties ragProperties,
                             MemoryExtractor memoryExtractor,
                             MemoryRecallScorer lexicalFallback,
                             ObjectProvider<EmbeddingClient> embeddingClientProvider) {
        this.properties = properties;
        this.ragProperties = ragProperties;
        this.memoryExtractor = memoryExtractor;
        this.lexicalFallback = lexicalFallback;
        this.embeddingClientProvider = embeddingClientProvider;
    }

    /**
     * 根据用户问题保存长期记忆和用户画像
     */
    @Override
    public void rememberLongTerm(String conversationId, String userId, MemoryMessage message) {
        if (!DurableMemoryAdmission.hasStableUserId(userId)
                || message == null || !"user".equalsIgnoreCase(message.role())
                || message.content() == null || message.content().isBlank()) {
            return;
        }
        String originalUserContent = message.content();
        String messageContent = originalUserContent.trim();
        if (!DurableMemoryAdmission.allowsAutomaticExtraction(messageContent)) {
            return;
        }
        String normalizedConversationId = normalize(conversationId, DEFAULT_CONVERSATION_ID);
        String normalizedUserId = userId.trim();
        Instant effectiveCreatedAt = message.createdAt() == null ? Instant.now() : message.createdAt();
        MemoryMessage normalizedMessage = new MemoryMessage(
                message.role().trim().toLowerCase(Locale.ROOT),
                messageContent,
                effectiveCreatedAt
        );
        MemoryExtraction extraction = memoryExtractor.extract(
                normalizedConversationId, normalizedUserId, normalizedMessage
        );
        if (extraction == null) {
            return;
        }
        List<LongTermMemoryDraft> memories = extraction.longTermMemories().stream()
                .filter(draft -> validDraft(draft, originalUserContent))
                .toList();
        List<UserProfileItem> profileItems = extraction.profileItems().stream()
                .map(item -> validAutomaticProfileItem(
                        item,
                        originalUserContent,
                        DurableMemoryAdmission.automaticProfileSource(
                                normalizedConversationId, effectiveCreatedAt),
                        effectiveCreatedAt))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (memories.isEmpty() && profileItems.isEmpty()) return;
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (LongTermMemoryDraft draft : memories) {
                    // 保存长期记忆到数据库
                    saveLongTermMemory(connection, normalizedConversationId, normalizedUserId,
                            originalUserContent, draft);
                }
                for (UserProfileItem item : profileItems) {
                    // 插入或更新用户画像
                    upsertProfileItem(connection, normalizedUserId, item);
                }
                connection.commit();
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to persist extracted long-term memory", exception);
        }
    }

    @Override
    public List<MemorySearchResult> recall(String conversationId, String userId, String query, int limit) {
        if (!DurableMemoryAdmission.hasStableUserId(userId)
                || query == null || query.isBlank()) {
            return List.of();
        }
        String normalizedUserId = userId.trim();
        int effectiveLimit = Math.max(1, limit);
        ensureSchema();
        double[] queryEmbedding = embedBestEffort(query);
        try (Connection connection = openConnection()) {
            List<StoredMemory> candidates = queryEmbedding == null
                    ? readFallbackCandidates(connection, normalizedUserId, effectiveLimit)
                    : readSemanticCandidates(connection, normalizedUserId, queryEmbedding, effectiveLimit);
            List<MemorySearchResult> selected = candidates.stream()
                    .map(candidate -> score(query, queryEmbedding != null, candidate))
                    .filter(result -> result.score() >= properties.getMinimumRecallScore())
                    .sorted(Comparator.comparingDouble(MemorySearchResult::score).reversed())
                    .limit(effectiveLimit)
                    .toList();
            touchSelected(connection, selected);
            return selected;
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to recall long-term memory", exception);
        }
    }

    @Override
    public UserProfile loadUserProfile(String userId) {
        if (!DurableMemoryAdmission.hasStableUserId(userId)) {
            return UserProfile.empty(userId);
        }
        String normalizedUserId = userId.trim();
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT profile_key, profile_value, source, updated_at
                     FROM agent_user_profile
                     WHERE user_id = ?
                     ORDER BY updated_at DESC, profile_key
                     LIMIT ?
                     """)) {
            statement.setString(1, normalizedUserId);
            statement.setInt(2, Math.max(1, properties.getProfileItemLimit()));
            List<UserProfileItem> items = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(new UserProfileItem(
                            resultSet.getString("profile_key"),
                            resultSet.getString("profile_value"),
                            resultSet.getString("source"),
                            resultSet.getTimestamp("updated_at").toInstant()
                    ));
                }
            }
            Instant updatedAt = items.stream().map(UserProfileItem::updatedAt)
                    .max(Comparator.naturalOrder()).orElse(null);
            return new UserProfile(normalizedUserId, items, updatedAt);
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to load user profile", exception);
        }
    }

    @Override
    public void upsertUserProfile(String userId, String key, String value, String source, Instant updatedAt) {
        if (!DurableMemoryAdmission.hasStableUserId(userId)
                || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            upsertProfileItem(connection, userId.trim(), new UserProfileItem(
                    key.trim(), value.trim(), normalize(source, "manual"),
                    updatedAt == null ? Instant.now() : updatedAt
            ));
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to upsert user profile", exception);
        }
    }

    @Override
    public void clearConversation(String conversationId) {
        executeDelete("DELETE FROM agent_long_term_memory WHERE conversation_id = ?",
                normalize(conversationId, DEFAULT_CONVERSATION_ID));
    }

    @Override
    public void clearUserMemory(String userId) {
        if (!DurableMemoryAdmission.hasStableUserId(userId)) {
            return;
        }
        String normalizedUserId = userId.trim();
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement profile = connection.prepareStatement(
                    "DELETE FROM agent_user_profile WHERE user_id = ?");
                 PreparedStatement memory = connection.prepareStatement(
                         "DELETE FROM agent_long_term_memory WHERE user_id = ?")) {
                profile.setString(1, normalizedUserId);
                profile.executeUpdate();
                memory.setString(1, normalizedUserId);
                memory.executeUpdate();
                connection.commit();
            }
            catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to clear user memory", exception);
        }
    }

    private void saveLongTermMemory(Connection connection,
                                    String conversationId,
                                    String userId,
                                    String originalUserContent,
                                    LongTermMemoryDraft draft) throws SQLException {
        if (draft == null || draft.content() == null || draft.content().isBlank()) {
            return;
        }
        if (!validDraft(draft, originalUserContent)) {
            return;
        }
        String category = draft.type().persistedValue();
        String content = draft.content().trim();
        double confidence = draft.confidence();
        // 把记忆内容转成向量
        double[] embedding = embedBestEffort(content);
        Instant now = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_long_term_memory(
                    memory_id, memory_key, conversation_id, user_id, category, content,
                    confidence, importance, embedding, access_count, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, 0, ?, ?)
                ON CONFLICT(user_id, memory_key) WHERE memory_key IS NOT NULL DO UPDATE SET
                    conversation_id = EXCLUDED.conversation_id,
                    confidence = GREATEST(agent_long_term_memory.confidence, EXCLUDED.confidence),
                    importance = GREATEST(agent_long_term_memory.importance, EXCLUDED.importance),
                    embedding = COALESCE(EXCLUDED.embedding, agent_long_term_memory.embedding),
                    updated_at = EXCLUDED.updated_at
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, memoryKey(category, content));
            statement.setString(3, conversationId);
            statement.setString(4, userId);
            statement.setString(5, category);
            statement.setString(6, content);
            statement.setDouble(7, confidence);
            statement.setDouble(8, confidence);
            statement.setString(9, embedding == null ? null : vectorLiteral(embedding));
            statement.setTimestamp(10, Timestamp.from(now));
            statement.setTimestamp(11, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    /**
     * 插入或更新用户画像
     */
    private void upsertProfileItem(Connection connection, String userId, UserProfileItem item) throws SQLException {
        if (item == null || item.key() == null || item.key().isBlank()
                || item.value() == null || item.value().isBlank()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_user_profile(user_id, profile_key, profile_value, source, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, profile_key) DO UPDATE SET
                    profile_value = EXCLUDED.profile_value,
                    source = EXCLUDED.source,
                    updated_at = EXCLUDED.updated_at
                """)) {
            statement.setString(1, userId);
            statement.setString(2, item.key().trim());
            statement.setString(3, item.value().trim());
            statement.setString(4, normalize(item.source(), "message"));
            statement.setTimestamp(5, Timestamp.from(item.updatedAt() == null ? Instant.now() : item.updatedAt()));
            statement.executeUpdate();
        }
    }

    private List<StoredMemory> readSemanticCandidates(Connection connection,
                                                       String userId,
                                                       double[] queryEmbedding,
                                                       int limit) throws SQLException {
        String queryVector = vectorLiteral(queryEmbedding);
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH query_vector AS (SELECT ?::vector AS embedding)
                SELECT memory_id, category, content, confidence, importance,
                       created_at, updated_at, access_count, last_accessed_at,
                       memory.embedding IS NOT NULL AS semantic_available,
                       CASE WHEN memory.embedding IS NULL THEN 0
                            ELSE 1 - (memory.embedding <=> query_vector.embedding) END AS semantic_score
                FROM agent_long_term_memory memory
                CROSS JOIN query_vector
                WHERE user_id = ?
                  AND category IN ('PREFERENCE', 'STABLE_INSTRUCTION')
                  AND (expires_at IS NULL OR expires_at > NOW())
                ORDER BY CASE WHEN memory.embedding IS NULL THEN 2
                              ELSE memory.embedding <=> query_vector.embedding END,
                         importance DESC, updated_at DESC
                LIMIT ?
                """)) {
            statement.setString(1, queryVector);
            statement.setString(2, userId);
            statement.setInt(3, candidateLimit(limit));
            return readCandidates(statement);
        }
    }

    private List<StoredMemory> readFallbackCandidates(Connection connection,
                                                       String userId,
                                                       int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT memory_id, category, content, confidence, importance,
                       created_at, updated_at, access_count, last_accessed_at,
                       FALSE AS semantic_available,
                       0 AS semantic_score
                FROM agent_long_term_memory
                WHERE user_id = ?
                  AND category IN ('PREFERENCE', 'STABLE_INSTRUCTION')
                  AND (expires_at IS NULL OR expires_at > NOW())
                ORDER BY importance DESC, updated_at DESC
                LIMIT ?
                """)) {
            statement.setString(1, userId);
            statement.setInt(2, candidateLimit(limit));
            return readCandidates(statement);
        }
    }

    private List<StoredMemory> readCandidates(PreparedStatement statement) throws SQLException {
        List<StoredMemory> candidates = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String category = resultSet.getString("category");
                if (!isPersistedDurableType(category)) {
                    continue;
                }
                Timestamp lastAccessed = resultSet.getTimestamp("last_accessed_at");
                candidates.add(new StoredMemory(
                        resultSet.getString("memory_id"),
                        category,
                        resultSet.getString("content"),
                        resultSet.getDouble("confidence"),
                        resultSet.getDouble("importance"),
                        clamp(resultSet.getDouble("semantic_score")),
                        resultSet.getBoolean("semantic_available"),
                        resultSet.getTimestamp("updated_at").toInstant(),
                        resultSet.getLong("access_count"),
                        lastAccessed == null ? null : lastAccessed.toInstant()
                ));
            }
        }
        return candidates;
    }

    private boolean validDraft(LongTermMemoryDraft draft, String originalUserContent) {
        return draft != null && draft.type() != null
                && draft.content() != null && !draft.content().isBlank()
                && DurableMemoryAdmission.allowsCandidateContent(draft.content())
                && DurableMemoryAdmission.isExactSourceSpan(
                originalUserContent, draft.content(), DurableMemoryAdmission.MAX_LONG_TERM_CONTENT_LENGTH)
                && Double.isFinite(draft.confidence())
                && draft.confidence() >= DurableMemoryAdmission.MIN_LONG_TERM_CONFIDENCE
                && draft.confidence() <= 1;
    }

    private UserProfileItem validAutomaticProfileItem(UserProfileItem item,
                                                      String originalUserContent,
                                                      String expectedSource,
                                                      Instant expectedUpdatedAt) {
        if (item == null || item.key() == null || item.key().isBlank()
                || item.value() == null || item.value().isBlank()) {
            return null;
        }
        String key = item.key().trim().toLowerCase(Locale.ROOT);
        if (!AUTOMATIC_PROFILE_KEYS.contains(key)
                || !expectedSource.equals(item.source())
                || !expectedUpdatedAt.equals(item.updatedAt())) {
            return null;
        }
        String value = item.value().trim();
        if (!DurableMemoryAdmission.allowsCandidateContent(value)
                || !DurableMemoryAdmission.isExactSourceSpan(
                originalUserContent, value, DurableMemoryAdmission.MAX_PROFILE_VALUE_LENGTH)) {
            return null;
        }
        return new UserProfileItem(key, value, expectedSource, expectedUpdatedAt);
    }

    private boolean isPersistedDurableType(String category) {
        return DurableMemoryType.fromPersistedValue(category).isPresent();
    }

    private MemorySearchResult score(String query, boolean queryEmbeddingAvailable, StoredMemory memory) {
        MemoryRecallScore lexical = lexicalFallback.scoreDetail(query, memory.content());
        double recency = recencyScore(memory.updatedAt());
        boolean semanticAvailable = queryEmbeddingAvailable && memory.semanticAvailable();
        double score = semanticAvailable
                ? memory.semanticScore() * properties.getSemanticWeight()
                + lexical.lexicalScore() * properties.getLexicalWeight()
                + memory.confidence() * 0.075
                + memory.importance() * 0.025
                + recency * 0.05
                : lexical.lexicalScore() * 0.75
                + memory.confidence() * 0.15
                + memory.importance() * 0.05
                + recency * 0.05;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("category", memory.category());
        metadata.put("confidence", memory.confidence());
        metadata.put("importance", memory.importance());
        metadata.put("semanticScore", memory.semanticScore());
        metadata.put("lexicalScore", lexical.lexicalScore());
        metadata.put("recencyScore", recency);
        metadata.put("recallMode", semanticAvailable ? "pgvector_hybrid" : "lexical_fallback");
        metadata.put("accessCount", memory.accessCount());
        return new MemorySearchResult("long_term", memory.memoryId(), memory.content(), clamp(score), metadata);
    }

    private void touchSelected(Connection connection, List<MemorySearchResult> selected) throws SQLException {
        if (selected.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_long_term_memory
                SET access_count = access_count + 1, last_accessed_at = ?
                WHERE memory_id = ?
                """)) {
            for (MemorySearchResult result : selected) {
                statement.setTimestamp(1, Timestamp.from(Instant.now()));
                statement.setString(2, result.id());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private int candidateLimit(int requestedLimit) {
        return Math.max(Math.max(1, properties.getLongTermCandidateLimit()), requestedLimit * 4);
    }

    private void ensureSchema() {
        if (schemaReady.get()) {
            return;
        }
        synchronized (schemaReady) {
            if (schemaReady.get()) {
                return;
            }
            int dimensions = Math.max(1, ragProperties.getEmbedding().getDimension());
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_long_term_memory (
                            memory_id TEXT PRIMARY KEY,
                            memory_key TEXT,
                            conversation_id TEXT NOT NULL,
                            user_id TEXT NOT NULL,
                            category TEXT NOT NULL,
                            content TEXT NOT NULL,
                            confidence DOUBLE PRECISION NOT NULL,
                            importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,
                            access_count BIGINT NOT NULL DEFAULT 0,
                            last_accessed_at TIMESTAMPTZ,
                            expires_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS memory_key TEXT");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS importance DOUBLE PRECISION NOT NULL DEFAULT 0.5");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS access_count BIGINT NOT NULL DEFAULT 0");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS last_accessed_at TIMESTAMPTZ");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS embedding vector(" + dimensions + ")");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_user_profile (
                            user_id TEXT NOT NULL,
                            profile_key TEXT NOT NULL,
                            profile_value TEXT NOT NULL,
                            source TEXT NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL,
                            PRIMARY KEY (user_id, profile_key)
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_long_term_conversation ON agent_long_term_memory(conversation_id, updated_at DESC)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_long_term_user ON agent_long_term_memory(user_id, updated_at DESC)");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_long_term_memory_key ON agent_long_term_memory(user_id, memory_key) WHERE memory_key IS NOT NULL");
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new MemoryException("Failed to initialize pgvector long-term memory schema", exception);
            }
        }
    }

    /**
     * 把记忆内容转成向量
     */
    private double[] embedBestEffort(String text) {
        EmbeddingClient client = embeddingClientProvider.getIfAvailable();
        if (client == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            double[] vector = client.embed(text);
            int expectedDimensions = Math.max(1, ragProperties.getEmbedding().getDimension());
            return vector == null || vector.length != expectedDimensions ? null : vector;
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private String vectorLiteral(double[] vector) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(vector[index]);
        }
        return value.append(']').toString();
    }

    private String memoryKey(String category, String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((category + "\n" + content).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private double recencyScore(Instant updatedAt) {
        long ageDays = updatedAt == null ? Long.MAX_VALUE
                : Math.max(0, Duration.between(updatedAt, Instant.now()).toDays());
        return ageDays == Long.MAX_VALUE ? 0 : 1.0 / (1.0 + ageDays / 30.0);
    }

    private void executeDelete(String sql, String value) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to delete long-term memory", exception);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword()
        );
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        }
        catch (SQLException ignored) {
        }
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    private record StoredMemory(
            String memoryId,
            String category,
            String content,
            double confidence,
            double importance,
            double semanticScore,
            boolean semanticAvailable,
            Instant updatedAt,
            long accessCount,
            Instant lastAccessedAt
    ) {
    }
}
