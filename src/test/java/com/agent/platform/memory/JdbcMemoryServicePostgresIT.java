package com.agent.platform.memory;

import com.agent.platform.config.MemoryProperties;
import com.agent.platform.config.RagProperties;
import com.agent.platform.rag.EmbeddingClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 需要显式打开 MEMORY_POSTGRES_IT=true，验证 typed memory 的 user scope 与 recall allowlist。 */
@EnabledIfEnvironmentVariable(named = "MEMORY_POSTGRES_IT", matches = "true")
class JdbcMemoryServicePostgresIT {

    private static final String CONVERSATION_PREFIX = "typed-memory-it-";
    private final MemoryProperties properties = memoryProperties();

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM agent_long_term_memory WHERE conversation_id LIKE ?")) {
            statement.setString(1, CONVERSATION_PREFIX + "%");
            statement.executeUpdate();
        }
    }

    @Test
    void durableMemoryIsUserScopedAcrossConversationsAndLegacyRowsAreNotRecalled() throws Exception {
        String sharedConversation = CONVERSATION_PREFIX + UUID.randomUUID();
        String otherConversation = CONVERSATION_PREFIX + UUID.randomUUID();
        JdbcMemoryService service = service();
        MemoryMessage userAMessage = new MemoryMessage(
                "user", "以后采购研发工作站时，user-a 更看重交付速度", Instant.now());
        MemoryMessage userBMessage = new MemoryMessage(
                "user", "以后采购研发工作站时，user-b 更看重价格", Instant.now());

        service.rememberLongTerm(sharedConversation, "user-a", userAMessage);
        service.rememberLongTerm(sharedConversation, "user-b", userBMessage);
        seedLegacyRows(sharedConversation);

        List<MemorySearchResult> userAFromNewConversation = service.recall(
                otherConversation, "user-a", "交付", 10);
        List<MemorySearchResult> userBFromSharedConversation = service.recall(
                sharedConversation, "user-b", "交付", 10);

        assertTrue(userAFromNewConversation.stream().anyMatch(value ->
                value.content().contains("user-a")));
        assertTrue(userAFromNewConversation.stream().allMatch(value ->
                Set.of("PREFERENCE", "STABLE_INSTRUCTION").contains(value.metadata().get("category"))));
        assertTrue(userAFromNewConversation.stream().noneMatch(value ->
                value.content().contains("user-b") || value.content().contains("legacy")));
        assertTrue(userBFromSharedConversation.stream().anyMatch(value ->
                value.content().contains("user-b")));
        assertTrue(userBFromSharedConversation.stream().noneMatch(value ->
                value.content().contains("user-a") || value.content().contains("legacy")));
        assertFalse(userAFromNewConversation.isEmpty());
    }

    private JdbcMemoryService service() {
        ObjectProvider<EmbeddingClient> embeddings = mock(ObjectProvider.class);
        when(embeddings.getIfAvailable()).thenReturn(null);
        MemoryExtractor extractor = (conversationId, userId, message) -> new MemoryExtraction(
                List.of(new LongTermMemoryDraft(DurableMemoryType.PREFERENCE, message.content(), 0.9)),
                List.of());
        return new JdbcMemoryService(properties, new RagProperties(), extractor,
                new MemoryRecallScorer(), embeddings);
    }

    private void seedLegacyRows(String conversationId) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_long_term_memory(
                         memory_id, memory_key, conversation_id, user_id, category, content,
                         confidence, importance, access_count, created_at, updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                     """)) {
            for (String category : List.of(
                    "business_fact", "decision", "open_task", "identity", "preference", "instruction")) {
                statement.setString(1, "legacy-" + category + "-" + UUID.randomUUID());
                statement.setString(2, "legacy-key-" + category + "-" + UUID.randomUUID());
                statement.setString(3, conversationId);
                statement.setString(4, "user-a");
                statement.setString(5, category);
                statement.setString(6, "legacy " + category);
                statement.setDouble(7, 0.99);
                statement.setDouble(8, 0.5);
                statement.setObject(9, java.sql.Timestamp.from(Instant.now()));
                statement.setObject(10, java.sql.Timestamp.from(Instant.now()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword());
    }

    private MemoryProperties memoryProperties() {
        MemoryProperties value = new MemoryProperties();
        value.getDatasource().setUrl(environment("MEMORY_POSTGRES_URL",
                environment("AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent")));
        value.getDatasource().setUsername(environment("MEMORY_POSTGRES_USERNAME",
                environment("AGENT_STORAGE_POSTGRES_USERNAME", "postgres")));
        value.getDatasource().setPassword(environment("MEMORY_POSTGRES_PASSWORD",
                environment("AGENT_STORAGE_POSTGRES_PASSWORD", "")));
        return value;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
