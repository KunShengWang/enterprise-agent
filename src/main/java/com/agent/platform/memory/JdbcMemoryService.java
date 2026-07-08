package com.agent.platform.memory;

import com.agent.platform.config.MemoryProperties;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcMemoryService(MemoryProperties memoryProperties,
                             ConversationSummarizer conversationSummarizer,
                             MemoryExtractor memoryExtractor,
                             MemoryRecallScorer recallScorer) {
        this.memoryProperties = memoryProperties;
        this.conversationSummarizer = conversationSummarizer;
        this.memoryExtractor = memoryExtractor;
        this.recallScorer = recallScorer;
    }

    @Override
    public ConversationMemory load(String conversationId, String userId, String query) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        ensureSchema();
        try (Connection connection = openConnection()) {
            List<MemoryMessage> recentMessages = readRecentMessages(connection, normalizedConversationId, memoryProperties.getWindowSize());
            String summary = readSummary(connection, normalizedConversationId);
            List<LongTermMemory> longTerm = readLongTermMemories(connection, normalizedConversationId, normalizedUserId, memoryProperties.getLongTermLimit());
            UserProfile profile = readUserProfile(connection, normalizedUserId);
            List<MemorySearchResult> recalled = isBlank(query)
                    ? List.of()
                    : recall(normalizedConversationId, normalizedUserId, query, memoryProperties.getRecallLimit());
            return new ConversationMemory(normalizedConversationId, normalizedUserId, recentMessages, summary, longTerm, profile, recalled);
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to load conversation memory", exception);
        }
    }

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
        ensureSchema();
        try (Connection connection = openConnection()) {
            insertMessage(connection, normalizedConversationId, normalizedUserId, effectiveMessage);
            extractAndStore(connection, normalizedConversationId, normalizedUserId, effectiveMessage);
            updateSummaryIfNeeded(connection, normalizedConversationId, normalizedUserId);
        }
        catch (SQLException exception) {
            throw new MemoryException("Failed to append conversation memory", exception);
        }
    }

    @Override
    public List<MemorySearchResult> recall(String conversationId, String userId, String query, int limit) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        ensureSchema();
        try (Connection connection = openConnection()) {
            List<MemorySearchResult> results = new ArrayList<>();
            addScoredResult(results, query, "summary", normalizedConversationId, readSummary(connection, normalizedConversationId),
                    Map.of("conversationId", normalizedConversationId));
            List<MemoryMessage> recentMessages = readRecentMessages(connection, normalizedConversationId, 80);
            for (int index = 0; index < recentMessages.size(); index++) {
                MemoryMessage message = recentMessages.get(index);
                addScoredResult(results, query, "message", normalizedConversationId + ":" + index, message.content(),
                        Map.of("role", message.role(), "createdAt", message.createdAt()));
            }
            for (LongTermMemory memory : readLongTermMemories(connection, normalizedConversationId, normalizedUserId, memoryProperties.getLongTermLimit())) {
                addScoredResult(results, query, "long_term", memory.memoryId(), memory.content(),
                        Map.of("category", memory.category(), "confidence", memory.confidence()));
            }
            for (UserProfileItem item : readUserProfile(connection, normalizedUserId).items()) {
                addScoredResult(results, query, "user_profile", normalizedUserId + ":" + item.key(), item.key() + "=" + item.value(),
                        Map.of("key", item.key(), "source", item.source()));
            }
            return results.stream()
                    .filter(result -> result.score() > 0)
                    .sorted(Comparator.comparingDouble(MemorySearchResult::score).reversed())
                    .limit(Math.max(1, limit))
                    .toList();
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
                            conversation_id TEXT NOT NULL,
                            user_id TEXT NOT NULL,
                            category TEXT NOT NULL,
                            content TEXT NOT NULL,
                            confidence DOUBLE PRECISION NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
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
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new MemoryException("Failed to initialize memory schema. Check PostgreSQL and DB credentials.", exception);
            }
        }
    }

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

    private void extractAndStore(Connection connection, String conversationId, String userId, MemoryMessage message) throws SQLException {
        MemoryExtraction extraction = memoryExtractor.extract(conversationId, userId, message);
        for (LongTermMemoryDraft draft : extraction.longTermMemories()) {
            Instant now = Instant.now();
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
        for (UserProfileItem item : extraction.profileItems()) {
            upsertProfileItem(connection, userId, item);
        }
    }

    private void insertLongTermMemory(Connection connection, LongTermMemory memory) throws SQLException {
        String sql = """
                INSERT INTO agent_long_term_memory(memory_id, conversation_id, user_id, category, content, confidence, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, memory.memoryId());
            statement.setString(2, memory.conversationId());
            statement.setString(3, memory.userId());
            statement.setString(4, memory.category());
            statement.setString(5, memory.content());
            statement.setDouble(6, memory.confidence());
            statement.setTimestamp(7, Timestamp.from(memory.createdAt()));
            statement.setTimestamp(8, Timestamp.from(memory.updatedAt()));
            statement.executeUpdate();
        }
    }

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

    private void updateSummaryIfNeeded(Connection connection, String conversationId, String userId) throws SQLException {
        int totalMessages = (int) count(connection, "SELECT COUNT(*) FROM agent_memory_message WHERE conversation_id = ?", conversationId);
        SummaryState state = readSummaryState(connection, conversationId);
        int trigger = Math.max(2, memoryProperties.getSummaryTriggerMessages());
        int windowSize = Math.max(1, memoryProperties.getWindowSize());
        int targetSummarizedCount = Math.max(0, totalMessages - windowSize);
        int unsummarizedCount = targetSummarizedCount - state.summarizedMessageCount();
        if (unsummarizedCount < trigger) {
            return;
        }
        List<MemoryMessage> messagesToSummarize = readMessagesByOffset(connection, conversationId, state.summarizedMessageCount(), unsummarizedCount);
        if (messagesToSummarize.isEmpty()) {
            return;
        }
        String nextSummary = conversationSummarizer.summarize(state.summary(), messagesToSummarize, memoryProperties.getSummaryMaxChars());
        upsertSummary(connection, conversationId, userId, nextSummary, targetSummarizedCount);
    }

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

    private String readSummary(Connection connection, String conversationId) throws SQLException {
        return readSummaryState(connection, conversationId).summary();
    }

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
            statement.setInt(4, summarizedMessageCount);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

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

    private List<LongTermMemory> readLongTermMemories(Connection connection, String conversationId, String userId, int limit) throws SQLException {
        String sql = """
                SELECT memory_id, conversation_id, user_id, category, content, confidence, created_at, updated_at
                FROM agent_long_term_memory
                WHERE conversation_id = ? OR user_id = ?
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
}
