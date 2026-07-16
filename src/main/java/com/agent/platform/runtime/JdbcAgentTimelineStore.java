package com.agent.platform.runtime;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL Agent 时间线实现。
 *
 * <p>消息和事件序号都由数据库会话行在事务内分配，避免多实例并发追加时出现
 * 重复序号或乱序。ToolResult 在写入前必须能找到同一会话里的 ToolCall，数据库
 * 唯一索引进一步保证一个 toolCallId 只能有一个调用消息和一个结果消息。</p>
 */
@Primary
@Repository
public class JdbcAgentTimelineStore implements AgentTimelineStore {

    private static final String DEFAULT_SESSION_ID = "default-conversation";
    private static final String DEFAULT_USER_ID = "anonymous";

    private final AgentStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcAgentTimelineStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 开启聊天窗口，并确认下一个消息和事件的序号
     */
    @Override
    public AgentSession openSession(String sessionId, String userId) {
        // ① 建表（幂等）
        ensureSchema();
        // ② 标准化
        String normalizedSessionId = normalize(sessionId, DEFAULT_SESSION_ID);
        String normalizedUserId = normalize(userId, DEFAULT_USER_ID);
        try (Connection connection = openConnection()) {
            // ③ 开启事务
            connection.setAutoCommit(false);
            try {
                // ④ 确保会话行存在
                ensureSession(connection, normalizedSessionId, normalizedUserId);
                // ⑤ 读当前状态
                AgentSession session = readSession(connection, normalizedSessionId, false)// 读不加行锁
                        .orElseThrow(() -> new AgentStorageException(
                                "Failed to create agent session: " + normalizedSessionId,
                                new IllegalStateException("session row is missing after upsert")
                        ));
                // ⑥ 提交
                connection.commit();
                return session;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to open agent session: " + normalizedSessionId, exception);
        }
    }

    @Override
    public Optional<AgentSession> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            return readSession(connection, sessionId.trim(), false);
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to read agent session: " + sessionId, exception);
        }
    }

    /**
     * 往数据库中插入消息
     */
    @Override
    public List<AgentMessage> appendMessages(String sessionId,
                                             String userId,
                                             String runId,
                                             List<AgentMessageDraft> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        ensureSchema();
        String normalizedSessionId = normalize(sessionId, DEFAULT_SESSION_ID);
        String normalizedUserId = normalize(userId, DEFAULT_USER_ID);
        String normalizedRunId = normalize(runId, UUID.randomUUID().toString());
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureSession(connection, normalizedSessionId, normalizedUserId);
                // 从数据库中读取当前 session 信息，包含全部字段
                AgentSession lockedSession = readSession(connection, normalizedSessionId, true)
                        .orElseThrow(() -> new AgentStorageException(
                                "Agent session disappeared: " + normalizedSessionId,
                                new IllegalStateException("session row is missing while appending messages")
                        ));
                // 验证工具调用和工具结果的一一对应关系，保证写入的每一条 TOOL_RESULT 都有对应的 ASSISTANT_TOOL_CALL
                validateToolPairs(connection, normalizedSessionId, messages);

                long nextSequence = lockedSession.nextMessageSequence();
                List<AgentMessage> persisted = new ArrayList<>(messages.size());
                for (AgentMessageDraft draft : messages) {
                    // 把 AgentMessageDraft 转为 AgentMessage
                    AgentMessage message = toMessage(normalizedSessionId, normalizedRunId, nextSequence++, draft);
                    // 把 AgentMessage 插入数据库
                    insertMessage(connection, message);
                    persisted.add(message);
                }
                // 更新数据库中 next_message_sequence 字段 + 1
                updateMessageCursor(connection, normalizedSessionId, nextSequence);
                connection.commit();
                return List.copyOf(persisted);
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to append agent messages for session: " + normalizedSessionId, exception);
        }
    }

    /**
     * 从数据库加载有限的历史消息
     */
    @Override
    public List<AgentMessage> loadMessages(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM (
                         SELECT message_id, session_id, run_id, message_sequence, message_type,
                                content, tool_call_id, tool_name, arguments_json, metadata_json,
                                estimated_tokens, created_at
                         FROM agent_message
                         WHERE session_id = ?
                         ORDER BY message_sequence DESC
                         LIMIT ?
                     ) recent
                     ORDER BY message_sequence ASC
                     """)) {
            statement.setString(1, sessionId.trim());
            statement.setInt(2, Math.max(1, limit));
            List<AgentMessage> messages = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(readMessage(resultSet));
                }
            }
            return List.copyOf(messages);
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to load agent messages for session: " + sessionId, exception);
        }
    }

    /**
     * 往数据库中添加 agent 事件
     */
    @Override
    public AgentEvent appendEvent(String sessionId,
                                  String userId,
                                  String runId,
                                  AgentEventDraft event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        ensureSchema();
        String normalizedSessionId = normalize(sessionId, DEFAULT_SESSION_ID);
        String normalizedUserId = normalize(userId, DEFAULT_USER_ID);
        String normalizedRunId = normalize(runId, "unknown-run");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureSession(connection, normalizedSessionId, normalizedUserId);
                AgentSession lockedSession = readSession(connection, normalizedSessionId, true)
                        .orElseThrow(() -> new AgentStorageException(
                                "Agent session disappeared: " + normalizedSessionId,
                                new IllegalStateException("session row is missing while appending event")
                        ));
                AgentEvent persisted = new AgentEvent(
                        UUID.randomUUID().toString(),
                        normalizedRunId,
                        normalizedSessionId,
                        lockedSession.nextEventSequence(),
                        event.type(),
                        event.content(),
                        event.payload(),
                        Instant.now()
                );
                // 把 agent 时间插入数据库
                insertEvent(connection, persisted);
                // 更新数据库中 next_event_sequence 字段 + 1
                updateEventCursor(connection, normalizedSessionId, persisted.sequence() + 1);
                connection.commit();
                return persisted;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to append agent event for run: " + normalizedRunId, exception);
        }
    }

    @Override
    public List<AgentEvent> loadEvents(String runId, int limit) {
        return loadEventsAfter(runId, -1, limit);
    }

    @Override
    public List<AgentEvent> loadEventsAfter(String runId, long afterSequence, int limit) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT event_id, run_id, session_id, event_sequence, event_type,
                            content, payload_json, created_at
                     FROM agent_runtime_event
                      WHERE run_id = ? AND event_sequence > ?
                     ORDER BY event_sequence ASC
                     LIMIT ?
                     """)) {
            statement.setString(1, runId.trim());
            statement.setLong(2, afterSequence);
            statement.setInt(3, Math.max(1, Math.min(limit, 10_000)));
            List<AgentEvent> events = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(new AgentEvent(
                            resultSet.getString("event_id"),
                            resultSet.getString("run_id"),
                            resultSet.getString("session_id"),
                            resultSet.getLong("event_sequence"),
                            AgentEventType.valueOf(resultSet.getString("event_type")),
                            resultSet.getString("content"),
                            readMap(resultSet.getString("payload_json")),
                            resultSet.getTimestamp("created_at").toInstant()
                    ));
                }
            }
            return List.copyOf(events);
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to load agent events for run: " + runId, exception);
        }
    }

    /**
     * 验证工具调用和工具结果的一一对应关系，保证写入的每一条 TOOL_RESULT 都有对应的 ASSISTANT_TOOL_CALL
     */
    private void validateToolPairs(Connection connection,
                                   String sessionId,
                                   List<AgentMessageDraft> messages) throws SQLException {
        Set<String> callsInBatch = new HashSet<>();
        for (AgentMessageDraft draft : messages) {
            // 规则 ①：工具调用不能重复（本批次内 + 历史数据中）
            if (draft.type() == AgentMessageType.ASSISTANT_TOOL_CALL) {
                // 如果 set 集合插入失败 || 工具调用已经在数据库中存在
                if (!callsInBatch.add(draft.toolCallId()) || toolCallExists(connection, sessionId, draft.toolCallId())) {
                    throw new IllegalArgumentException("duplicate tool call message: " + draft.toolCallId());
                }
            }
            // 规则 ②：工具结果必须匹配一个存在的工具调用
            if (draft.type() == AgentMessageType.TOOL_RESULT
                    && !callsInBatch.contains(draft.toolCallId())
                    && !toolCallExists(connection, sessionId, draft.toolCallId())) {
                throw new IllegalArgumentException("tool result has no matching tool call: " + draft.toolCallId());
            }
        }
    }

    /**
     * select 1 只判断行是否存在，不取任何列数据
     */
    private boolean toolCallExists(Connection connection, String sessionId, String toolCallId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM agent_message
                WHERE session_id = ? AND tool_call_id = ? AND message_type = 'ASSISTANT_TOOL_CALL'
                """)) {
            statement.setString(1, sessionId);
            statement.setString(2, toolCallId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();// 有下一行 → true；没有 → false
            }
        }
    }

    /**
     * 把 AgentMessageDraft 转为 AgentMessage
     */
    private AgentMessage toMessage(String sessionId,
                                   String runId,
                                   long sequence,
                                   AgentMessageDraft draft) {
        return new AgentMessage(
                UUID.randomUUID().toString(),
                sessionId,
                runId,
                sequence,
                draft.type(),
                draft.content(),
                draft.toolCallId(),
                draft.toolName(),
                draft.arguments(),
                draft.metadata(),
                draft.estimatedTokens(),
                Instant.now()
        );
    }

    /**
     * 把 AgentMessage 插入数据库
     */
    private void insertMessage(Connection connection, AgentMessage message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_message(
                    message_id, session_id, run_id, message_sequence, message_type, content,
                    tool_call_id, tool_name, arguments_json, metadata_json, estimated_tokens, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, message.messageId());
            statement.setString(2, message.sessionId());
            statement.setString(3, message.runId());
            statement.setLong(4, message.sequence());
            statement.setString(5, message.type().name());
            statement.setString(6, message.content());
            statement.setString(7, blankToNull(message.toolCallId()));
            statement.setString(8, blankToNull(message.toolName()));
            statement.setString(9, toJson(message.arguments()));
            statement.setString(10, toJson(message.metadata()));
            statement.setLong(11, message.estimatedTokens());
            statement.setTimestamp(12, Timestamp.from(message.createdAt()));
            statement.executeUpdate();
        }
    }

    private void insertEvent(Connection connection, AgentEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_runtime_event(
                    event_id, run_id, session_id, event_sequence, event_type, content, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.eventId());
            statement.setString(2, event.runId());
            statement.setString(3, event.sessionId());
            statement.setLong(4, event.sequence());
            statement.setString(5, event.type().name());
            statement.setString(6, event.content());
            statement.setString(7, toJson(event.payload()));
            statement.setTimestamp(8, Timestamp.from(event.createdAt()));
            statement.executeUpdate();
        }
    }

    private AgentMessage readMessage(ResultSet resultSet) throws SQLException {
        return new AgentMessage(
                resultSet.getString("message_id"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getLong("message_sequence"),
                AgentMessageType.valueOf(resultSet.getString("message_type")),
                resultSet.getString("content"),
                resultSet.getString("tool_call_id"),
                resultSet.getString("tool_name"),
                readMap(resultSet.getString("arguments_json")),
                readMap(resultSet.getString("metadata_json")),
                resultSet.getLong("estimated_tokens"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private void ensureSession(Connection connection, String sessionId, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_session(
                    session_id, user_id, next_message_sequence, next_event_sequence,
                    version, created_at, updated_at
                ) VALUES (?, ?, 1, 1, 0, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    user_id = CASE
                        WHEN agent_session.user_id = 'anonymous' THEN EXCLUDED.user_id
                        ELSE agent_session.user_id
                    END,
                    updated_at = EXCLUDED.updated_at
                """)) {
            Instant now = Instant.now();
            statement.setString(1, sessionId);
            statement.setString(2, userId);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    /**
     * 从数据库中读取当前 session 信息，包含全部字段
     */
    private Optional<AgentSession> readSession(Connection connection,
                                               String sessionId,
                                               boolean forUpdate) throws SQLException {
        String sql = """
                SELECT session_id, user_id, next_message_sequence, next_event_sequence,
                       version, created_at, updated_at
                FROM agent_session
                WHERE session_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");// 如果 sql 加上  FOR UPDATE 表示对该行加上行锁
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AgentSession(
                        resultSet.getString("session_id"),
                        resultSet.getString("user_id"),
                        resultSet.getLong("next_message_sequence"),// 下一条消息的序号
                        resultSet.getLong("next_event_sequence"),// 下一个事件的序号
                        resultSet.getLong("version"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()
                ));
            }
        }
    }

    /**
     * 更新数据库中 next_message_sequence 字段 + 1
     */
    private void updateMessageCursor(Connection connection, String sessionId, long nextSequence) throws SQLException {
        updateCursor(connection, sessionId, "next_message_sequence", nextSequence);
    }

    /**
     * 更新数据库中 next_event_sequence 字段 + 1
     */
    private void updateEventCursor(Connection connection, String sessionId, long nextSequence) throws SQLException {
        updateCursor(connection, sessionId, "next_event_sequence", nextSequence);
    }

    /**
     * 更新数据库中 next_message_sequence 或者 next_event_sequence 字段 + 1
     */
    private void updateCursor(Connection connection,
                              String sessionId,
                              String column,
                              long nextSequence) throws SQLException {
        String sql = "UPDATE agent_session SET " + column + " = ?, version = version + 1, updated_at = ? WHERE session_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nextSequence);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setString(3, sessionId);
            statement.executeUpdate();
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
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_session (
                            session_id TEXT PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            next_message_sequence BIGINT NOT NULL DEFAULT 1,
                            next_event_sequence BIGINT NOT NULL DEFAULT 1,
                            version BIGINT NOT NULL DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_message (
                            message_id TEXT PRIMARY KEY,
                            session_id TEXT NOT NULL REFERENCES agent_session(session_id) ON DELETE CASCADE,
                            run_id TEXT NOT NULL,
                            message_sequence BIGINT NOT NULL,
                            message_type TEXT NOT NULL,
                            content TEXT NOT NULL,
                            tool_call_id TEXT,
                            tool_name TEXT,
                            arguments_json TEXT NOT NULL DEFAULT '{}',
                            metadata_json TEXT NOT NULL DEFAULT '{}',
                            estimated_tokens BIGINT NOT NULL DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL,
                            UNIQUE(session_id, message_sequence)
                        )
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_message_tool_call
                        ON agent_message(session_id, tool_call_id)
                        WHERE message_type = 'ASSISTANT_TOOL_CALL'
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_message_tool_result
                        ON agent_message(session_id, tool_call_id)
                        WHERE message_type = 'TOOL_RESULT'
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_agent_message_session_sequence
                        ON agent_message(session_id, message_sequence)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_runtime_event (
                            event_id TEXT PRIMARY KEY,
                            run_id TEXT NOT NULL,
                            session_id TEXT NOT NULL REFERENCES agent_session(session_id) ON DELETE CASCADE,
                            event_sequence BIGINT NOT NULL,
                            event_type TEXT NOT NULL,
                            content TEXT NOT NULL,
                            payload_json TEXT NOT NULL DEFAULT '{}',
                            created_at TIMESTAMPTZ NOT NULL,
                            UNIQUE(session_id, event_sequence)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_agent_runtime_event_run_sequence
                        ON agent_runtime_event(run_id, event_sequence)
                        """);
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new AgentStorageException("Failed to initialize agent timeline schema", exception);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getUsername(),
                properties.getDatasource().getPassword()
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        }
        catch (Exception exception) {
            throw new AgentStorageException("Failed to serialize agent timeline payload", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return Map.copyOf(result);
        }
        catch (Exception exception) {
            throw new AgentStorageException("Failed to deserialize agent timeline payload", exception);
        }
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        }
        catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }
}
