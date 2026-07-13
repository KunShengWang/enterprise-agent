package com.agent.platform.memory;

import com.agent.platform.config.MemoryProperties;
import com.agent.platform.rag.EmbeddingClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(prefix = "enterprise-agent.memory", name = "mode", havingValue = "jdbc")
public class JdbcMemoryService implements MemoryService {

    private static final String DEFAULT_CONVERSATION_ID = "default-conversation";

    private static final String DEFAULT_USER_ID = "anonymous-user";

    private final MemoryProperties memoryProperties;

    private final ConversationSummarizer conversationSummarizer;

    private final MemoryExtractor memoryExtractor;

    private final MemoryRecallScorer recallScorer;

    private final ObjectProvider<EmbeddingClient> embeddingClientProvider;

    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcMemoryService(MemoryProperties memoryProperties,
                             ConversationSummarizer conversationSummarizer,
                             MemoryExtractor memoryExtractor,
                             MemoryRecallScorer recallScorer,
                             ObjectProvider<EmbeddingClient> embeddingClientProvider) {
        this.memoryProperties = memoryProperties;
        this.conversationSummarizer = conversationSummarizer;
        this.memoryExtractor = memoryExtractor;
        this.recallScorer = recallScorer;
        this.embeddingClientProvider = embeddingClientProvider;
    }

    /**
     * 加载消息，包含近期消息、压缩消息、长期记忆、用户画像和召回消息
     */
    @Override
    public ConversationMemory load(String conversationId, String userId, String query) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        // 确保数据库表已经创建
        ensureSchema();
        try (Connection connection = openConnection()) {
            // 从数据库读取最近的几条消息
            List<MemoryMessage> recentMessages = readRecentMessages(connection, normalizedConversationId, memoryProperties.getWindowSize());
            // 读取压缩消息
            String summary = readSummary(connection, normalizedConversationId);
            // 读取长期记忆
            List<LongTermMemory> longTerm = readLongTermMemories(connection, normalizedConversationId, normalizedUserId, memoryProperties.getLongTermLimit());
            // 读取用户画像
            UserProfile profile = readUserProfile(connection, normalizedUserId);
            // 返回的是近期消息、压缩消息、长期记忆和用户画像四类记忆中相关性最高的前 limit 条
            List<MemorySearchResult> recalled = isBlank(query)
                    ? List.of()
                    : recall(normalizedConversationId, normalizedUserId, query, memoryProperties.getRecallLimit());
            // 包含近期消息、压缩消息、长期记忆、用户画像和召回消息
            return new ConversationMemory(normalizedConversationId, normalizedUserId, recentMessages, summary, longTerm, profile, recalled);
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to load conversation memory", exception);
        }
    }

    /**
     * 把本轮用户问题保存到会话记忆中
     */
    @Override
    public void append(String conversationId, String userId, MemoryMessage message) {
        if (message == null || isBlank(message.content())) {
            return;
        }
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        MemoryMessage effectiveMessage = new MemoryMessage(
                normalizeRole(message.role()),
                message.content().trim(),
                message.createdAt() == null ? Instant.now() : message.createdAt()
        );
        MemoryExtraction extraction = memoryExtractor.extract(normalizedConversationId, normalizedUserId, effectiveMessage);
        ensureSchema();
        try (Connection connection = openConnection()) {
            // 往数据库中插入数据
            insertMessage(connection, normalizedConversationId, normalizedUserId, effectiveMessage);
            // 提炼用户画像和长期记忆
            storeExtraction(connection, normalizedConversationId, normalizedUserId, extraction);
            // 如果消息超出窗口消息大小就进行消息压缩
            updateSummaryIfNeeded(connection, normalizedConversationId, normalizedUserId);
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to append conversation memory", exception);
        }
    }

    @Override
    public void rememberLongTerm(String conversationId, String userId, MemoryMessage message) {
        if (message == null || isBlank(message.content())) {
            return;
        }
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        MemoryMessage effectiveMessage = new MemoryMessage(
                normalizeRole(message.role()),
                message.content().trim(),
                message.createdAt() == null ? Instant.now() : message.createdAt()
        );
        MemoryExtraction extraction = memoryExtractor.extract(normalizedConversationId, normalizedUserId, effectiveMessage);
        if (extraction.longTermMemories().isEmpty() && extraction.profileItems().isEmpty()) {
            return;
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            storeExtraction(connection, normalizedConversationId, normalizedUserId, extraction);
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to persist extracted long-term memory", exception);
        }
    }

    @Override
    public List<MemorySearchResult> recall(String conversationId, String userId, String query, int limit) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        ensureSchema();
        try (Connection connection = openConnection()) {
            List<MemorySearchResult> results = new ArrayList<>();
            double[] queryEmbedding = embedBestEffort(query);
            // 会话摘要 summary
            addScoredResult(results, query, "summary", normalizedConversationId, readSummary(connection, normalizedConversationId),
                    Map.of("conversationId", normalizedConversationId));
            // 读取近期 80 条数据
            List<MemoryMessage> recentMessages = readRecentMessages(connection, normalizedConversationId, 80);
            // 最近消息 message
            for (int index = 0; index < recentMessages.size(); index++) {
                MemoryMessage message = recentMessages.get(index);
                addScoredResult(results, query, "message", normalizedConversationId + ":" + index, message.content(),
                        Map.of("role", message.role(), "createdAt", message.createdAt()));
            }
            // 长期记忆 long_term
            for (StoredMemory memory : readStoredMemories(
                    connection,
                    normalizedConversationId,
                    normalizedUserId,
                    memoryProperties.getLongTermLimit()
            )) {
                addSemanticMemoryResult(results, query, queryEmbedding, memory);
            }
            // 用户画像 user_profile
            for (UserProfileItem item : readUserProfile(connection, normalizedUserId).items()) {
                addScoredResult(results, query, "user_profile", normalizedUserId + ":" + item.key(), item.key() + "=" + item.value(),
                        Map.of("key", item.key(), "source", item.source()));
            }
            // 返回的是四类记忆中相关性最高的前 limit 条
            List<MemorySearchResult> selected = results.stream()
                    .filter(result -> result.score() > 0)
                    .sorted(Comparator.comparingDouble(MemorySearchResult::score).reversed())
                    .limit(Math.max(1, limit))
                    .toList();
            touchSelectedMemories(connection, selected);
            return selected;
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to recall memory", exception);
        }
    }

    @Override
    public MemorySnapshot snapshot(String conversationId, String userId, String query, int limit) {
        ConversationMemory memory = load(conversationId, userId, query);
        try (Connection connection = openConnection()) {
            return new MemorySnapshot(
                    memory.conversationId(),
                    memory.userId(),
                    readRecentMessages(connection, memory.conversationId(), Math.max(1, limit)),
                    memory.summary(),
                    memory.longTermMemories(),
                    memory.userProfile(),
                    memory.recalledMemories(),
                    stats(memory.conversationId(), memory.userId())
            );
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to create memory snapshot", exception);
        }
    }

    @Override
    public MemoryStats stats(String conversationId, String userId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        ensureSchema();
        try (Connection connection = openConnection()) {
            return new MemoryStats(
                    "jdbc",
                    normalizedConversationId,
                    normalizedUserId,
                    count(connection, "SELECT COUNT(*) FROM agent_memory_message WHERE conversation_id = ?", normalizedConversationId),
                    count(connection, "SELECT COUNT(*) FROM agent_memory_summary WHERE conversation_id = ?", normalizedConversationId),
                    count(connection, "SELECT COUNT(*) FROM agent_long_term_memory WHERE conversation_id = ? OR user_id = ?", normalizedConversationId, normalizedUserId),
                    count(connection, "SELECT COUNT(*) FROM agent_user_profile WHERE user_id = ?", normalizedUserId)
            );
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to read memory stats", exception);
        }
    }

    @Override
    public UserProfile loadUserProfile(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        ensureSchema();
        try (Connection connection = openConnection()) {
            return readUserProfile(connection, normalizedUserId);
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to load user profile", exception);
        }
    }

    @Override
    public void upsertUserProfile(String userId, String key, String value, String source, Instant updatedAt) {
        if (isBlank(key) || isBlank(value)) {
            return;
        }
        String normalizedUserId = normalizeUserId(userId);
        ensureSchema();
        try (Connection connection = openConnection()) {
            upsertProfileItem(connection, normalizedUserId, new UserProfileItem(
                    key.trim(),
                    value.trim(),
                    blankToDefault(source, "manual"),
                    updatedAt == null ? Instant.now() : updatedAt
            ));
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to upsert user profile", exception);
        }
    }

    @Override
    public void clearConversation(String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        ensureSchema();
        try (Connection connection = openConnection()) {
            executeUpdate(connection, "DELETE FROM agent_memory_message WHERE conversation_id = ?", normalizedConversationId);
            executeUpdate(connection, "DELETE FROM agent_memory_summary WHERE conversation_id = ?", normalizedConversationId);
            executeUpdate(connection, "DELETE FROM agent_long_term_memory WHERE conversation_id = ?", normalizedConversationId);
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to clear conversation memory", exception);
        }
    }

    @Override
    public void clearUserMemory(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        ensureSchema();
        try (Connection connection = openConnection()) {
            executeUpdate(connection, "DELETE FROM agent_user_profile WHERE user_id = ?", normalizedUserId);
            executeUpdate(connection, "DELETE FROM agent_long_term_memory WHERE user_id = ?", normalizedUserId);
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to clear user memory", exception);
        }
    }

    /**
     * 确保数据库表已经创建
     */
    private void ensureSchema() {
        if (schemaReady.get()) {
            return;
        }
        synchronized (schemaReady) {
            if (schemaReady.get()) {
                return;
            }
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_memory_message (
                            id BIGSERIAL PRIMARY KEY,
                            conversation_id TEXT NOT NULL,
                            user_id TEXT NOT NULL,
                            role TEXT NOT NULL,
                            content TEXT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_memory_summary (
                            conversation_id TEXT PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            summary TEXT NOT NULL,
                            summarized_message_count INTEGER NOT NULL DEFAULT 0,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
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
                            embedding_json TEXT,
                            access_count BIGINT NOT NULL DEFAULT 0,
                            last_accessed_at TIMESTAMPTZ,
                            expires_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS memory_key TEXT");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS importance DOUBLE PRECISION NOT NULL DEFAULT 0.5");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS embedding_json TEXT");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS access_count BIGINT NOT NULL DEFAULT 0");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS last_accessed_at TIMESTAMPTZ");
                statement.execute("ALTER TABLE agent_long_term_memory ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ");
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
                statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_memory_message_conversation ON agent_memory_message(conversation_id, id)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_long_term_conversation ON agent_long_term_memory(conversation_id, updated_at DESC)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_long_term_user ON agent_long_term_memory(user_id, updated_at DESC)");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_long_term_memory_key ON agent_long_term_memory(user_id, memory_key) WHERE memory_key IS NOT NULL");
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new MemoryException("Failed to initialize memory schema. Check PostgreSQL and DB credentials.", exception);
            }
        }
    }

    /**
     * 往数据库中插入数据
     */
    private void insertMessage(Connection connection, String conversationId, String userId, MemoryMessage message) throws SQLException {
        String sql = """
                INSERT INTO agent_memory_message(conversation_id, user_id, role, content, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            statement.setString(2, userId);
            statement.setString(3, message.role());
            statement.setString(4, message.content());
            statement.setTimestamp(5, Timestamp.from(message.createdAt()));
            statement.executeUpdate();
        }
    }

    /**
     * 提炼用户画像和长期记忆
     */
    private void storeExtraction(Connection connection,
                                 String conversationId,
                                 String userId,
                                 MemoryExtraction extraction) throws SQLException {
        for (LongTermMemoryDraft draft : extraction.longTermMemories()) {
            Instant now = Instant.now();
            // 插入长期记忆
            insertLongTermMemory(connection, new LongTermMemory(
                    UUID.randomUUID().toString(),
                    conversationId,
                    userId,
                    blankToDefault(draft.category(), "fact"),
                    draft.content(),
                    draft.confidence(),
                    now,
                    now
            ));
        }
        // 添加用户画像
        for (UserProfileItem item : extraction.profileItems()) {
            upsertProfileItem(connection, userId, item);
        }
    }

    /**
     * 插入长期记忆
     */
    private void insertLongTermMemory(Connection connection, LongTermMemory memory) throws SQLException {
        String sql = """
                INSERT INTO agent_long_term_memory(
                    memory_id, memory_key, conversation_id, user_id, category, content,
                    confidence, importance, embedding_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id, memory_key) WHERE memory_key IS NOT NULL DO UPDATE SET
                    conversation_id = EXCLUDED.conversation_id,
                    confidence = GREATEST(agent_long_term_memory.confidence, EXCLUDED.confidence),
                    importance = GREATEST(agent_long_term_memory.importance, EXCLUDED.importance),
                    embedding_json = COALESCE(EXCLUDED.embedding_json, agent_long_term_memory.embedding_json),
                    updated_at = EXCLUDED.updated_at
                """;
        double[] embedding = embedBestEffort(memory.content());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, memory.memoryId());
            statement.setString(2, memoryKey(memory.category(), memory.content()));
            statement.setString(3, memory.conversationId());
            statement.setString(4, memory.userId());
            statement.setString(5, memory.category());
            statement.setString(6, memory.content());
            statement.setDouble(7, memory.confidence());
            statement.setDouble(8, memory.confidence());
            statement.setString(9, embedding == null ? null : encodeVector(embedding));
            statement.setTimestamp(10, Timestamp.from(memory.createdAt()));
            statement.setTimestamp(11, Timestamp.from(memory.updatedAt()));
            statement.executeUpdate();
        }
    }

    /**
     * 添加用户画像
     */
    private void upsertProfileItem(Connection connection, String userId, UserProfileItem item) throws SQLException {
        if (item == null || isBlank(item.key()) || isBlank(item.value())) {
            return;
        }
        String sql = """
                INSERT INTO agent_user_profile(user_id, profile_key, profile_value, source, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, profile_key) DO UPDATE SET
                    profile_value = EXCLUDED.profile_value,
                    source = EXCLUDED.source,
                    updated_at = EXCLUDED.updated_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, item.key().trim());
            statement.setString(3, item.value().trim());
            statement.setString(4, blankToDefault(item.source(), "message"));
            statement.setTimestamp(5, Timestamp.from(item.updatedAt() == null ? Instant.now() : item.updatedAt()));
            statement.executeUpdate();
        }
    }

    /**
     * 如果消息超出窗口消息大小就进行消息压缩
     */
    private void updateSummaryIfNeeded(Connection connection, String conversationId, String userId) throws SQLException {
        // 查询总共有多少用户消息数
        int totalMessages = (int) count(connection, "SELECT COUNT(*) FROM agent_memory_message WHERE conversation_id = ?", conversationId);
        // 读取压缩消息
        SummaryState state = readSummaryState(connection, conversationId);
        int trigger = Math.max(2, memoryProperties.getSummaryTriggerMessages());
        // 窗口大小
        int windowSize = Math.max(1, memoryProperties.getWindowSize());
        // 要总结的消息数
        int targetSummarizedCount = Math.max(0, totalMessages - windowSize);
        int unsummarizedCount = targetSummarizedCount - state.summarizedMessageCount();
        if (unsummarizedCount < trigger) {
            return;
        }
        List<MemoryMessage> messagesToSummarize = readMessagesByOffset(connection, conversationId, state.summarizedMessageCount(), unsummarizedCount);
        if (messagesToSummarize.isEmpty()) {
            return;
        }
        // 压缩消息
        String nextSummary = conversationSummarizer.summarize(state.summary(), messagesToSummarize, memoryProperties.getSummaryMaxChars());
        // 插入/更新消息压缩
        upsertSummary(connection, conversationId, userId, nextSummary, targetSummarizedCount);
    }

    /**
     * 读取压缩消息
     */
    private SummaryState readSummaryState(Connection connection, String conversationId) throws SQLException {
        String sql = """
                SELECT summary, summarized_message_count
                FROM agent_memory_summary
                WHERE conversation_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new SummaryState(resultSet.getString("summary"), resultSet.getInt("summarized_message_count"));
                }
                return new SummaryState("", 0);
            }
        }
    }

    /**
     * 读取压缩消息
     */
    private String readSummary(Connection connection, String conversationId) throws SQLException {
        // 读取压缩消息
        return readSummaryState(connection, conversationId).summary();
    }

    /**
     * 插入/更新消息压缩
     */
    private void upsertSummary(Connection connection, String conversationId, String userId, String summary, int summarizedMessageCount) throws SQLException {
        String sql = """
                INSERT INTO agent_memory_summary(conversation_id, user_id, summary, summarized_message_count, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (conversation_id) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    summary = EXCLUDED.summary,
                    summarized_message_count = EXCLUDED.summarized_message_count,
                    updated_at = EXCLUDED.updated_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            statement.setString(2, userId);
            statement.setString(3, summary);
            statement.setInt(4, summarizedMessageCount);// 总结的消息数
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    /**
     * 从数据库读取最近的几条消息
     */
    private List<MemoryMessage> readRecentMessages(Connection connection, String conversationId, int limit) throws SQLException {
        String sql = """
                SELECT role, content, created_at
                FROM agent_memory_message
                WHERE conversation_id = ?
                ORDER BY id DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MemoryMessage> messages = new ArrayList<>();
                while (resultSet.next()) {
                    messages.add(readMemoryMessage(resultSet));
                }
                Collections.reverse(messages);
                return messages;
            }
        }
    }

    private List<MemoryMessage> readMessagesByOffset(Connection connection, String conversationId, int offset, int limit) throws SQLException {
        String sql = """
                SELECT role, content, created_at
                FROM agent_memory_message
                WHERE conversation_id = ?
                ORDER BY id ASC
                OFFSET ?
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            statement.setInt(2, Math.max(0, offset));
            statement.setInt(3, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MemoryMessage> messages = new ArrayList<>();
                while (resultSet.next()) {
                    messages.add(readMemoryMessage(resultSet));
                }
                return messages;
            }
        }
    }

    private MemoryMessage readMemoryMessage(ResultSet resultSet) throws SQLException {
        return new MemoryMessage(
                resultSet.getString("role"),
                resultSet.getString("content"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    /**
     * 读取长期记忆
     */
    private List<LongTermMemory> readLongTermMemories(Connection connection, String conversationId, String userId, int limit) throws SQLException {
        String sql = """
                SELECT memory_id, conversation_id, user_id, category, content, confidence, created_at, updated_at
                FROM agent_long_term_memory
                WHERE (conversation_id = ? OR user_id = ?)
                  AND (expires_at IS NULL OR expires_at > NOW())
                ORDER BY updated_at DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            statement.setString(2, userId);
            statement.setInt(3, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<LongTermMemory> memories = new ArrayList<>();
                while (resultSet.next()) {
                    memories.add(new LongTermMemory(
                            resultSet.getString("memory_id"),
                            resultSet.getString("conversation_id"),
                            resultSet.getString("user_id"),
                            resultSet.getString("category"),
                            resultSet.getString("content"),
                            resultSet.getDouble("confidence"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getTimestamp("updated_at").toInstant()
                    ));
                }
                return memories;
            }
        }
    }

    /**
     * 读取用户画像
     */
    private UserProfile readUserProfile(Connection connection, String userId) throws SQLException {
        String sql = """
                SELECT profile_key, profile_value, source, updated_at
                FROM agent_user_profile
                WHERE user_id = ?
                ORDER BY profile_key
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setInt(2, Math.max(1, memoryProperties.getProfileItemLimit()));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<UserProfileItem> items = new ArrayList<>();
                while (resultSet.next()) {
                    items.add(new UserProfileItem(
                            resultSet.getString("profile_key"),
                            resultSet.getString("profile_value"),
                            resultSet.getString("source"),
                            resultSet.getTimestamp("updated_at").toInstant()
                    ));
                }
                Instant updatedAt = items.stream()
                        .map(UserProfileItem::updatedAt)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
                return new UserProfile(userId, items, updatedAt);
            }
        }
    }

    private void addScoredResult(List<MemorySearchResult> results, String query, String type, String id, String content, Map<String, Object> metadata) {
        if (isBlank(content)) {
            return;
        }
        MemoryRecallScore score = recallScorer.scoreDetail(query, content);
        if (score.score() > 0) {
            Map<String, Object> enrichedMetadata = new LinkedHashMap<>(metadata);
            enrichedMetadata.put("recallMode", "hybrid_lexical_semantic");
            enrichedMetadata.put("lexicalScore", score.lexicalScore());
            enrichedMetadata.put("semanticScore", score.semanticScore());
            enrichedMetadata.put("matchedTerms", score.matchedTerms());
            results.add(new MemorySearchResult(type, id, content, score.score(), enrichedMetadata));
        }
    }

    private List<StoredMemory> readStoredMemories(Connection connection,
                                                   String conversationId,
                                                   String userId,
                                                   int limit) throws SQLException {
        String sql = """
                SELECT memory_id, category, content, confidence, importance, embedding_json,
                       created_at, updated_at, access_count, last_accessed_at
                FROM agent_long_term_memory
                WHERE (conversation_id = ? OR user_id = ?)
                  AND (expires_at IS NULL OR expires_at > NOW())
                ORDER BY importance DESC, updated_at DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            statement.setString(2, userId);
            statement.setInt(3, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredMemory> memories = new ArrayList<>();
                while (resultSet.next()) {
                    Timestamp lastAccessed = resultSet.getTimestamp("last_accessed_at");
                    memories.add(new StoredMemory(
                            resultSet.getString("memory_id"),
                            resultSet.getString("category"),
                            resultSet.getString("content"),
                            resultSet.getDouble("confidence"),
                            resultSet.getDouble("importance"),
                            decodeVector(resultSet.getString("embedding_json")),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getTimestamp("updated_at").toInstant(),
                            resultSet.getLong("access_count"),
                            lastAccessed == null ? null : lastAccessed.toInstant()
                    ));
                }
                return memories;
            }
        }
    }

    private void addSemanticMemoryResult(List<MemorySearchResult> results,
                                         String query,
                                         double[] queryEmbedding,
                                         StoredMemory memory) {
        MemoryRecallScore lexical = recallScorer.scoreDetail(query, memory.content());
        double semantic = cosineSimilarity(queryEmbedding, memory.embedding());
        double recency = recencyScore(memory.updatedAt());
        boolean semanticAvailable = queryEmbedding != null && memory.embedding() != null;
        double score = semanticAvailable
                ? semantic * 0.65 + lexical.lexicalScore() * 0.15
                + memory.confidence() * 0.10 + memory.importance() * 0.05 + recency * 0.05
                : lexical.lexicalScore() * 0.70 + memory.confidence() * 0.15
                + memory.importance() * 0.10 + recency * 0.05;
        if (score <= 0) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("category", memory.category());
        metadata.put("confidence", memory.confidence());
        metadata.put("importance", memory.importance());
        metadata.put("lexicalScore", lexical.lexicalScore());
        metadata.put("semanticScore", semantic);
        metadata.put("recencyScore", recency);
        metadata.put("recallMode", semanticAvailable ? "embedding_hybrid" : "lexical_fallback");
        metadata.put("accessCount", memory.accessCount());
        results.add(new MemorySearchResult("long_term", memory.memoryId(), memory.content(), Math.min(1, score), metadata));
    }

    private void touchSelectedMemories(Connection connection, List<MemorySearchResult> selected) throws SQLException {
        List<String> memoryIds = selected.stream()
                .filter(result -> "long_term".equals(result.type()))
                .map(MemorySearchResult::id)
                .distinct()
                .toList();
        if (memoryIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_long_term_memory
                SET access_count = access_count + 1, last_accessed_at = ?
                WHERE memory_id = ?
                """)) {
            for (String memoryId : memoryIds) {
                statement.setTimestamp(1, Timestamp.from(Instant.now()));
                statement.setString(2, memoryId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private double[] embedBestEffort(String text) {
        if (isBlank(text) || embeddingClientProvider == null) {
            return null;
        }
        EmbeddingClient client = embeddingClientProvider.getIfAvailable();
        if (client == null) {
            return null;
        }
        try {
            double[] vector = client.embed(text);
            return vector == null || vector.length == 0 ? null : vector;
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private double cosineSimilarity(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }

    private double recencyScore(Instant updatedAt) {
        if (updatedAt == null) {
            return 0;
        }
        long ageDays = Math.max(0, Duration.between(updatedAt, Instant.now()).toDays());
        return 1.0 / (1.0 + ageDays / 30.0);
    }

    private String encodeVector(double[] vector) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.toString();
    }

    private double[] decodeVector(String encoded) {
        if (isBlank(encoded)) {
            return null;
        }
        String[] parts = encoded.split(",");
        double[] vector = new double[parts.length];
        try {
            for (int index = 0; index < parts.length; index++) {
                vector[index] = Double.parseDouble(parts[index]);
            }
            return vector;
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private String memoryKey(String category, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((category + "\n" + content).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private long count(Connection connection, String sql, String... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setString(index + 1, args[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private void executeUpdate(Connection connection, String sql, String arg) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, arg);
            statement.executeUpdate();
        }
    }

    private Connection openConnection() throws SQLException {
        MemoryProperties.Datasource datasource = memoryProperties.getDatasource();
        return DriverManager.getConnection(datasource.getUrl(), datasource.getUsername(), datasource.getPassword());
    }

    private String normalizeConversationId(String conversationId) {
        return blankToDefault(conversationId, DEFAULT_CONVERSATION_ID);
    }

    private String normalizeUserId(String userId) {
        return blankToDefault(userId, DEFAULT_USER_ID);
    }

    private String normalizeRole(String role) {
        return blankToDefault(role, "user").toLowerCase();
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SummaryState(String summary, int summarizedMessageCount) {
    }

    private record StoredMemory(
            String memoryId,
            String category,
            String content,
            double confidence,
            double importance,
            double[] embedding,
            Instant createdAt,
            Instant updatedAt,
            long accessCount,
            Instant lastAccessedAt
    ) {
    }
}
