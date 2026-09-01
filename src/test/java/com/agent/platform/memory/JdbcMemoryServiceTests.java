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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcMemoryServiceTests {

    @Test
    void rememberPersistsTheTypedEnumValueAndKeepsConversationAsProvenance() throws Exception {
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(extractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(new LongTermMemoryDraft(
                        DurableMemoryType.PREFERENCE,
                        "以后采购研发工作站时，我通常更看重交付速度",
                        0.9)), List.of()));
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
                    new MemoryMessage("USER", "以后采购研发工作站时，我通常更看重交付速度。", createdAt));
        }

        ArgumentCaptor<Integer> indexes = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(insert, atLeastOnce()).setString(indexes.capture(), values.capture());
        Map<Integer, String> bound = new HashMap<>();
        for (int index = 0; index < indexes.getAllValues().size(); index++) {
            bound.put(indexes.getAllValues().get(index), values.getAllValues().get(index));
        }
        assertEquals("conversation-a", bound.get(3));
        assertEquals("user-a", bound.get(4));
        assertEquals("PREFERENCE", bound.get(5));
        assertEquals("以后采购研发工作站时，我通常更看重交付速度", bound.get(6));
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
                        "budget", "600000", "llm-message:conversation-a;createdAt=2026-01-01T00:00:00Z",
                        Instant.parse("2026-01-01T00:00:00Z")))));
        JdbcMemoryService service = service(memoryProperties(), ragProperties(), extractor, noEmbeddingProvider());

        service.rememberLongTerm("conversation-a", "user-a",
                new MemoryMessage("user", "请记住我的预算偏好", Instant.parse("2026-01-01T00:00:00Z")));

        verify(extractor).extract(anyString(), anyString(), any(MemoryMessage.class));
    }

    @Test
    void rememberRejectsUnGroundedExtractorResultBeforeOpeningDb() throws Exception {
        MemoryProperties properties = memoryProperties();
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(extractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(new LongTermMemoryDraft(
                        DurableMemoryType.PREFERENCE, "我喜欢最低价供应商", 0.95)), List.of()));
        JdbcMemoryService service = service(properties, ragProperties(), extractor, noEmbeddingProvider());
        Connection connection = mock(Connection.class);

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.rememberLongTerm("conversation-a", "user-a",
                    new MemoryMessage("user", "以后默认使用中文回答。", Instant.now()));
            driverManager.verify(() -> DriverManager.getConnection(
                    properties.getDatasource().getUrl(),
                    properties.getDatasource().getUsername(),
                    properties.getDatasource().getPassword()), never());
        }

        verifyNoInteractions(connection);
    }

    @Test
    void rememberRejectsExactButNonDurableCandidateBeforeOpeningDb() throws Exception {
        MemoryProperties properties = memoryProperties();
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(extractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(new LongTermMemoryDraft(
                        DurableMemoryType.PREFERENCE, "交付优先", 0.95)), List.of()));
        JdbcMemoryService service = service(properties, ragProperties(), extractor, noEmbeddingProvider());
        Connection connection = mock(Connection.class);

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.rememberLongTerm("conversation-a", "user-a",
                    new MemoryMessage("user", "以后默认使用中文回答，交付优先。", Instant.now()));
            driverManager.verify(() -> DriverManager.getConnection(
                    properties.getDatasource().getUrl(),
                    properties.getDatasource().getUsername(),
                    properties.getDatasource().getPassword()), never());
        }

        verifyNoInteractions(connection);
    }

    @Test
    void rememberRejectsLowConfidenceAndDynamicExtractorCandidatesBeforeOpeningDb() throws Exception {
        MemoryProperties properties = memoryProperties();
        Connection connection = mock(Connection.class);
        for (LongTermMemoryDraft draft : List.of(
                new LongTermMemoryDraft(DurableMemoryType.PREFERENCE, "以后默认使用中文回答", 0.1),
                new LongTermMemoryDraft(DurableMemoryType.PREFERENCE, "以后采购时 Supplier D 报价 58 万", 0.95))) {
            MemoryExtractor extractor = mock(MemoryExtractor.class);
            when(extractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                    new MemoryExtraction(List.of(draft), List.of()));
            JdbcMemoryService service = service(properties, ragProperties(), extractor, noEmbeddingProvider());

            try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
                service.rememberLongTerm("conversation-a", "user-a",
                        new MemoryMessage("user", "以后默认使用中文回答。以后采购时 Supplier D 报价 58 万", Instant.now()));
                driverManager.verify(() -> DriverManager.getConnection(
                        properties.getDatasource().getUrl(),
                        properties.getDatasource().getUsername(),
                        properties.getDatasource().getPassword()), never());
            }
        }
        verifyNoInteractions(connection);
    }

    @Test
    void automaticProfileMustBeExactSourceGroundedAndUseCurrentMessageProvenance() throws Exception {
        MemoryProperties properties = memoryProperties();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        String source = DurableMemoryAdmission.automaticProfileSource("conversation-a", createdAt);
        MemoryMessage message = new MemoryMessage("user", "以后默认使用中文回答。", createdAt);

        MemoryExtractor invalidExtractor = mock(MemoryExtractor.class);
        when(invalidExtractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(), List.of(new UserProfileItem(
                        "language", "English", source, createdAt))));
        JdbcMemoryService invalidService = service(properties, ragProperties(), invalidExtractor, noEmbeddingProvider());
        Connection invalidConnection = mock(Connection.class);
        try (MockedStatic<DriverManager> driverManager = driverManager(properties, invalidConnection)) {
            invalidService.rememberLongTerm("conversation-a", "user-a", message);
            driverManager.verify(() -> DriverManager.getConnection(
                    properties.getDatasource().getUrl(),
                    properties.getDatasource().getUsername(),
                    properties.getDatasource().getPassword()), never());
        }
        verifyNoInteractions(invalidConnection);

        MemoryExtractor validExtractor = mock(MemoryExtractor.class);
        when(validExtractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(), List.of(new UserProfileItem(
                        "language", "以后默认使用中文回答", source, createdAt))));
        JdbcMemoryService validService = service(properties, ragProperties(), validExtractor, noEmbeddingProvider());
        Connection validConnection = mock(Connection.class);
        Statement schema = mock(Statement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        when(validConnection.createStatement()).thenReturn(schema);
        when(validConnection.prepareStatement(anyString())).thenReturn(upsert);
        when(upsert.executeUpdate()).thenReturn(1);

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, validConnection)) {
            validService.rememberLongTerm("conversation-a", "user-a", message);
        }

        ArgumentCaptor<Integer> indexes = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(upsert, atLeastOnce()).setString(indexes.capture(), values.capture());
        Map<Integer, String> bound = new HashMap<>();
        for (int index = 0; index < indexes.getAllValues().size(); index++) {
            bound.put(indexes.getAllValues().get(index), values.getAllValues().get(index));
        }
        assertEquals("user-a", bound.get(1));
        assertEquals("language", bound.get(2));
        assertEquals("以后默认使用中文回答", bound.get(3));
        assertEquals(source, bound.get(4));
    }

    @Test
    void automaticProfileWithWrongProvenanceIsRejectedBeforeOpeningDb() throws Exception {
        MemoryProperties properties = memoryProperties();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(extractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(), List.of(new UserProfileItem(
                        "language", "以后默认使用中文回答", "arbitrary-source", createdAt))));
        JdbcMemoryService service = service(properties, ragProperties(), extractor, noEmbeddingProvider());
        Connection connection = mock(Connection.class);

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.rememberLongTerm("conversation-a", "user-a",
                    new MemoryMessage("user", "以后默认使用中文回答。", createdAt));
            driverManager.verify(() -> DriverManager.getConnection(
                    properties.getDatasource().getUrl(),
                    properties.getDatasource().getUsername(),
                    properties.getDatasource().getPassword()), never());
        }
        verifyNoInteractions(connection);
    }

    @Test
    void automaticProfileWithStaleTimestampIsRejectedBeforeOpeningDb() throws Exception {
        MemoryProperties properties = memoryProperties();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        String source = DurableMemoryAdmission.automaticProfileSource("conversation-a", createdAt);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(extractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(), List.of(new UserProfileItem(
                        "language", "以后默认使用中文回答", source, createdAt.plusSeconds(1)))));
        JdbcMemoryService service = service(properties, ragProperties(), extractor, noEmbeddingProvider());
        Connection connection = mock(Connection.class);

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.rememberLongTerm("conversation-a", "user-a",
                    new MemoryMessage("user", "以后默认使用中文回答。", createdAt));
            driverManager.verify(() -> DriverManager.getConnection(
                    properties.getDatasource().getUrl(),
                    properties.getDatasource().getUsername(),
                    properties.getDatasource().getPassword()), never());
        }
        verifyNoInteractions(connection);
    }

    @Test
    void automaticProfileRejectsExactButNonDurableValueBeforeOpeningDb() throws Exception {
        MemoryProperties properties = memoryProperties();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        String source = DurableMemoryAdmission.automaticProfileSource("conversation-a", createdAt);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(extractor.extract(anyString(), anyString(), any(MemoryMessage.class))).thenReturn(
                new MemoryExtraction(List.of(), List.of(new UserProfileItem(
                        "language", "中文", source, createdAt))));
        JdbcMemoryService service = service(properties, ragProperties(), extractor, noEmbeddingProvider());
        Connection connection = mock(Connection.class);

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.rememberLongTerm("conversation-a", "user-a",
                    new MemoryMessage("user", "以后默认使用中文回答。", createdAt));
            driverManager.verify(() -> DriverManager.getConnection(
                    properties.getDatasource().getUrl(),
                    properties.getDatasource().getUsername(),
                    properties.getDatasource().getPassword()), never());
        }

        verifyNoInteractions(connection);
    }

    @Test
    void unstableUserIdsFailClosedWithoutExtractorEmbeddingOrDb() throws Exception {
        MemoryProperties properties = memoryProperties();
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        ObjectProvider<EmbeddingClient> embeddings = mock(ObjectProvider.class);
        JdbcMemoryService service = service(properties, ragProperties(), extractor, embeddings);
        Connection connection = mock(Connection.class);
        List<String> unstableUserIds = Arrays.asList(
                null, "", "  ", "anonymous", "anonymous-user", "ANONYMOUS", " ANONYMOUS-USER ");

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            for (String userId : unstableUserIds) {
                service.rememberLongTerm("conversation-a", userId,
                        new MemoryMessage("user", "以后默认使用中文回答。", Instant.now()));
                assertTrue(service.recall("conversation-a", userId, "中文", 5).isEmpty());
                assertTrue(service.loadUserProfile(userId).items().isEmpty());
            }
            driverManager.verify(() -> DriverManager.getConnection(
                    properties.getDatasource().getUrl(),
                    properties.getDatasource().getUsername(),
                    properties.getDatasource().getPassword()), never());
        }

        verifyNoInteractions(extractor, embeddings, connection);
    }

    @Test
    void manualUpsertUserProfileRestoresExplicitFallbackContract() throws Exception {
        MemoryProperties properties = memoryProperties();
        JdbcMemoryService service = service(properties, ragProperties(),
                mock(MemoryExtractor.class), noEmbeddingProvider());
        Connection connection = mock(Connection.class);
        Statement schema = mock(Statement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        when(connection.createStatement()).thenReturn(schema);
        when(connection.prepareStatement(anyString())).thenReturn(upsert);
        when(upsert.executeUpdate()).thenReturn(1);
        Instant updatedAt = Instant.parse("2026-01-01T00:00:00Z");

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.upsertUserProfile(null, "language", "中文", null, updatedAt);
            service.upsertUserProfile("  ", "response_style", "简洁", "  ", updatedAt);
            service.upsertUserProfile(" anonymous ", "language", "中文", "manual-source", updatedAt);
            service.upsertUserProfile(" user-a ", "language", "中文", "manual-source", updatedAt);
        }

        ArgumentCaptor<String> userIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sources = ArgumentCaptor.forClass(String.class);
        verify(upsert, times(4)).setString(eq(1), userIds.capture());
        verify(upsert, times(4)).setString(eq(4), sources.capture());
        assertEquals(List.of("anonymous-user", "anonymous-user", "anonymous", "user-a"), userIds.getAllValues());
        assertEquals(List.of("manual", "manual", "manual-source", "manual-source"), sources.getAllValues());
    }

    @Test
    void clearUserMemoryRestoresAnonymousDeletionAndCommitsBothDeletes() throws Exception {
        MemoryProperties properties = memoryProperties();
        JdbcMemoryService service = service(properties, ragProperties(),
                mock(MemoryExtractor.class), noEmbeddingProvider());
        Connection connection = mock(Connection.class);
        Statement schema = mock(Statement.class);
        PreparedStatement profile = mock(PreparedStatement.class);
        PreparedStatement memory = mock(PreparedStatement.class);
        when(connection.createStatement()).thenReturn(schema);
        when(connection.prepareStatement("DELETE FROM agent_user_profile WHERE user_id = ?")).thenReturn(profile);
        when(connection.prepareStatement("DELETE FROM agent_long_term_memory WHERE user_id = ?")).thenReturn(memory);
        when(profile.executeUpdate()).thenReturn(1);
        when(memory.executeUpdate()).thenReturn(1);

        try (MockedStatic<DriverManager> driverManager = driverManager(properties, connection)) {
            service.clearUserMemory("anonymous");
            service.clearUserMemory(null);
        }

        ArgumentCaptor<String> profileUserIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> memoryUserIds = ArgumentCaptor.forClass(String.class);
        verify(profile, times(2)).setString(eq(1), profileUserIds.capture());
        verify(memory, times(2)).setString(eq(1), memoryUserIds.capture());
        assertEquals(List.of("anonymous", "anonymous-user"), profileUserIds.getAllValues());
        assertEquals(List.of("anonymous", "anonymous-user"), memoryUserIds.getAllValues());
        verify(memory, times(2)).executeUpdate();
        verify(profile, times(2)).executeUpdate();
        verify(connection, times(2)).setAutoCommit(false);
        verify(connection, times(2)).commit();
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
