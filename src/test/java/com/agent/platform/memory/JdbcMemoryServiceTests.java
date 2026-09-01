package com.agent.platform.memory;

import com.agent.platform.config.MemoryProperties;
import com.agent.platform.config.RagProperties;
import com.agent.platform.rag.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcMemoryServiceTests {

    @Test
    void rememberPersistsTheTypedEnumValueAndKeepsConversationAsProvenance() throws Exception {
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(extractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(new LongTermMemoryDraft(
                        DurableMemoryType.PREFERENCE, "交付速度优先", 0.9)), List.of()));
        MemoryProperties properties = memoryProperties();
        RagProperties ragProperties = ragProperties();
        ObjectProvider<EmbeddingClient> embeddings = noEmbeddingProvider();
        JdbcMemoryService service = service(properties, ragProperties, extractor, embeddings);

        Connection connection = mock(Connection.class);
        Statement schema = mock(Statement.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        when(connection.createStatement()).thenReturn(schema);
        when(connection.prepareStatement(anyString())).thenReturn(insert);
        when(insert.executeUpdate()).thenReturn(1);
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.rememberLongTerm("conversation-a", "user-a",
                    new MemoryMessage("USER", "以后采购时通常交付优先", createdAt));
        }

        ArgumentCaptor<Integer> indexes = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(insert, atLeastOnce()).setString(indexes.capture(), values.capture());
        Map<Integer, String> bound = new java.util.HashMap<>();
        for (int index = 0; index < indexes.getAllValues().size(); index++) {
            bound.put(indexes.getAllValues().get(index), values.getAllValues().get(index));
        }
        assertEquals("conversation-a", bound.get(3));
        assertEquals("user-a", bound.get(4));
        assertEquals("PREFERENCE", bound.get(5));
        assertEquals("交付速度优先", bound.get(6));
    }

    @Test
    void rememberRejectsNonUserAndEphemeralMessagesBeforeCallingTheExtractor() {
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        JdbcMemoryService service = service(memoryProperties(), ragProperties(), extractor, noEmbeddingProvider());

        service.rememberLongTerm("conversation-shared", "user-a",
                new MemoryMessage("assistant", "以后采购通常交付优先", Instant.now()));
        service.rememberLongTerm("conversation-shared", "user-a",
                new MemoryMessage("user", "这次预算 60 万", Instant.now()));
        service.rememberLongTerm("conversation-shared", "user-a",
                new MemoryMessage("user", "交付优先", Instant.now()));

        verifyNoInteractions(extractor);
    }

    @Test
    void rememberRejectsAutomaticProfileKeysOutsideTheSmallAllowlist() {
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(extractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(), List.of(new UserProfileItem(
                        "budget", "600000", "llm-message", Instant.now()))));
        JdbcMemoryService service = service(memoryProperties(), ragProperties(), extractor, noEmbeddingProvider());

        service.rememberLongTerm("conversation-a", "user-a",
                new MemoryMessage("user", "请记住我的预算偏好", Instant.now()));

        verify(extractor).extract(anyString(), anyString(), any(MemoryMessage.class));
    }

    @Test
    void fallbackRecallUsesUserScopeAndOnlyDurableCategories() throws Exception {
        MemoryProperties properties = memoryProperties();
        RagProperties ragProperties = ragProperties();
        JdbcMemoryService service = service(properties, ragProperties,
                mock(MemoryExtractor.class), noEmbeddingProvider());
        Connection connection = mock(Connection.class);
        Statement schema = mock(Statement.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(schema);
        when(connection.prepareStatement(anyString())).thenReturn(select);
        when(select.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        String sql;
        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.recall("conversation-shared", "user-b", "交付", 5);
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(sqlCaptor.capture());
            sql = sqlCaptor.getValue();
        }

        assertTrue(sql.contains("WHERE user_id = ?"));
        assertTrue(sql.contains("category IN ('PREFERENCE', 'STABLE_INSTRUCTION')"));
        assertTrue(!sql.contains("conversation_id = ? OR user_id = ?"));
        verify(select).setString(1, "user-b");
        verify(select).setInt(2, 40);
    }

    @Test
    void semanticRecallKeepsTheSameUserScopeAndCandidateLimit() throws Exception {
        MemoryProperties properties = memoryProperties();
        RagProperties ragProperties = ragProperties();
        ragProperties.getEmbedding().setDimension(1);
        ObjectProvider<EmbeddingClient> embeddings = mock(ObjectProvider.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embedding.embed("交付")).thenReturn(new double[]{1.0});
        JdbcMemoryService service = service(properties, ragProperties,
                mock(MemoryExtractor.class), embeddings);
        Connection connection = mock(Connection.class);
        Statement schema = mock(Statement.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(schema);
        when(connection.prepareStatement(anyString())).thenReturn(select);
        when(select.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        String sql;
        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.recall("conversation-shared", "user-a", "交付", 5);
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(sqlCaptor.capture());
            sql = sqlCaptor.getValue();
        }

        assertTrue(sql.contains("WHERE user_id = ?"));
        assertTrue(sql.contains("category IN ('PREFERENCE', 'STABLE_INSTRUCTION')"));
        assertTrue(!sql.contains("conversation_id = ? OR user_id = ?"));
        verify(select).setString(2, "user-a");
        verify(select).setInt(3, 40);
    }

    @Test
    void javaSideRecallFilterDropsLegacyRowsEvenIfAStorageDriverReturnsThem() throws Exception {
        MemoryProperties properties = memoryProperties();
        RagProperties ragProperties = ragProperties();
        JdbcMemoryService service = service(properties, ragProperties,
                mock(MemoryExtractor.class), noEmbeddingProvider());
        Connection connection = mock(Connection.class);
        Statement schema = mock(Statement.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(schema);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0, String.class).startsWith("UPDATE") ? update : select);
        when(select.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, true, true, false);
        when(resultSet.getString("category")).thenReturn(
                "business_fact", "preference", "instruction", "PREFERENCE", "STABLE_INSTRUCTION");
        when(resultSet.getString("memory_id")).thenReturn("allowed-memory");
        when(resultSet.getString("content")).thenReturn(
                "交付优先", "交付后先给结论");
        when(resultSet.getDouble("confidence")).thenReturn(0.9);
        when(resultSet.getDouble("importance")).thenReturn(0.5);
        when(resultSet.getDouble("semantic_score")).thenReturn(0.0);
        when(resultSet.getBoolean("semantic_available")).thenReturn(false);
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.now()));
        when(resultSet.getTimestamp("last_accessed_at")).thenReturn(null);

        List<MemorySearchResult> recalled;
        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            recalled = service.recall("conversation-shared", "user-a", "交付", 5);
        }

        assertEquals(2, recalled.size());
        assertTrue(recalled.stream().allMatch(value ->
                Set.of("PREFERENCE", "STABLE_INSTRUCTION").contains(value.metadata().get("category"))));
        verify(update).executeBatch();
    }

    private JdbcMemoryService service(MemoryProperties properties,
                                      RagProperties ragProperties,
                                      MemoryExtractor extractor,
                                      ObjectProvider<EmbeddingClient> embeddings) {
        return new JdbcMemoryService(properties, ragProperties, extractor,
                new MemoryRecallScorer(), embeddings);
    }

    private MemoryProperties memoryProperties() {
        MemoryProperties properties = new MemoryProperties();
        properties.getDatasource().setUrl("jdbc:postgresql://memory-test/enterprise_agent");
        properties.getDatasource().setUsername("test-user");
        properties.getDatasource().setPassword("test-password");
        return properties;
    }

    private RagProperties ragProperties() {
        return new RagProperties();
    }

    private ObjectProvider<EmbeddingClient> noEmbeddingProvider() {
        ObjectProvider<EmbeddingClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private MockedStatic<DriverManager> driverManager(MemoryProperties properties,
                                                       Connection connection) throws Exception {
        MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class);
        driverManager.when(() -> DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword())).thenReturn(connection);
        return driverManager;
    }
}
