package com.agent.platform.runtime;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

@Primary
@Component
public class JdbcAgentRuntimeStore implements AgentRunStore, ToolExecutionStore {

    private final AgentStorageProperties properties;

    private final ObjectMapper objectMapper;

    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcAgentRuntimeStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 把 AgentRunRecord 保存到数据库
     */
    @Override
    public AgentRunRecord create(AgentRunRecord record) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_run_state(
                         run_id, trace_id, conversation_id, status, current_node,
                         record_json, version, created_at, updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            // 给 sql 插入数据
            bindRun(statement, record);
            statement.executeUpdate();
            return record;
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to create agent run: " + record.runId(), exception);
        }
    }

    @Override
    public Optional<AgentRunRecord> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT record_json FROM agent_run_state WHERE run_id = ?")) {
            statement.setString(1, runId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(fromJson(resultSet.getString("record_json"), AgentRunRecord.class))
                        : Optional.empty();
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to read agent run: " + runId, exception);
        }
    }

    @Override
    public List<AgentRunRecord> recent(int limit) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT record_json FROM agent_run_state
                     ORDER BY updated_at DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, Math.max(1, limit));
            List<AgentRunRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(fromJson(resultSet.getString("record_json"), AgentRunRecord.class));
                }
            }
            return records;
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to read recent agent runs", exception);
        }
    }

    /**
     * 根据新的 AgentRunRecord 更新数据库
     */
    @Override
    public AgentRunRecord update(String runId, UnaryOperator<AgentRunRecord> updater) {
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                // 从数据库中加载出当前的 agent 运行状态 AgentRunRecord
                AgentRunRecord current = loadRunForUpdate(connection, runId)
                        .orElseThrow(() -> new IllegalArgumentException("agent run not found: " + runId));
                // 根据 AgentRequest 更新 AgentRunRecord
                AgentRunRecord next = updater.apply(current)
                        .withVersion(current.version() + 1, Instant.now());
                // 根据新的 AgentRunRecord 更新数据库
                writeRun(connection, next);
                connection.commit();// ← 正常提交
                return next;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);// ← 事务失败，回滚
                throw exception;// ← 抛出的是业务异常，不是回滚的异常
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to update agent run: " + runId, exception);
        }
    }

    @Override
    public Optional<AgentRunRecord> claimForResume(String runId) {
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentRunRecord current = loadRunForUpdate(connection, runId).orElse(null);
                if (current == null || current.state() != AgentRunState.WAITING_APPROVAL) {
                    connection.rollback();
                    return Optional.empty();
                }
                AgentRunRecord claimed = current.claimedForResume()
                        .withVersion(current.version() + 1, Instant.now());
                writeRun(connection, claimed);
                connection.commit();
                return Optional.of(claimed);
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to claim agent run for resume: " + runId, exception);
        }
    }

    /**
     * "工具调用 claim 就是分布式幂等锁——同一个 toolCallId 全局只执行一次，已经执行过的直接返回缓存结果；如果同时多个请求抢执行权，数据库行锁保证只有一个赢
     */
    @Override
    public ToolExecutionClaim claim(String runId, ToolCallRequest request) {
        if (request == null || request.requestId() == null || request.requestId().isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                // ① 查数据库：这个 toolCallId 之前见过吗？
                ToolExecutionRecord current = loadToolForUpdate(connection, request.requestId()).orElse(null);
                if (current == null) {
                    // ② 没见过 → 插入 RUNNING 记录 → 拿到执行权
                    insertTool(connection, ToolExecutionRecord.running(runId, request));
                    connection.commit();
                    return ToolExecutionClaim.acquired();// ✅ 允许执行
                }
                if (!runId.equals(current.runId())) {
                    connection.commit();
                    // ③ 被另一个 run 占用 → 拒绝
                    return ToolExecutionClaim.crossRunConflict(
                            "tool execution id already belongs to another run"
                    );
                }
                if (current.state() == ToolExecutionState.SUCCEEDED) {
                    connection.commit();
                    // ④ 已经成功执行过 → 返回缓存结果
                    return ToolExecutionClaim.existing(current, "toolCallId already succeeded");
                }
                if (current.state() == ToolExecutionState.FAILED) {
                    // ⑤ 上次失败了 → 允许重试
                    writeTool(connection, current.retrying());
                    connection.commit();
                    return ToolExecutionClaim.acquired();
                }
                connection.commit();
                // ⑥ RUNNING 或 MANUAL_REVIEW → 正在进行中
                return ToolExecutionClaim.existing(current, "toolCallId has uncertain or in-progress result");
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to claim tool execution: " + request.requestId(), exception);
        }
    }

    @Override
    public void markSucceeded(String toolCallId, ToolCallResult result) {
        updateTool(toolCallId, current -> current.withResult(ToolExecutionState.SUCCEEDED, result, ""));
    }

    @Override
    public void markFailed(String toolCallId, ToolCallResult result) {
        String error = result == null ? "tool failed" : result.errorMessage();
        updateTool(toolCallId, current -> current.withResult(ToolExecutionState.FAILED, result, error));
    }

    @Override
    public void markManualReview(String toolCallId, String reason) {
        updateTool(toolCallId, current -> current.state() == ToolExecutionState.SUCCEEDED
                ? current
                : current.withResult(ToolExecutionState.MANUAL_REVIEW, current.result(), reason));
    }

    @Override
    public Optional<ToolExecutionRecord> findToolExecution(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT record_json FROM agent_tool_execution WHERE tool_call_id = ?")) {
            statement.setString(1, toolCallId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(fromJson(resultSet.getString("record_json"), ToolExecutionRecord.class))
                        : Optional.empty();
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to read tool execution: " + toolCallId, exception);
        }
    }

    @Override
    public List<ToolExecutionRecord> findByRun(String runId) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT record_json FROM agent_tool_execution
                     WHERE run_id = ?
                     ORDER BY created_at
                     """)) {
            statement.setString(1, runId);
            List<ToolExecutionRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(fromJson(resultSet.getString("record_json"), ToolExecutionRecord.class));
                }
            }
            return records;
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to read tool executions for run: " + runId, exception);
        }
    }

    private void updateTool(String toolCallId, UnaryOperator<ToolExecutionRecord> updater) {
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ToolExecutionRecord current = loadToolForUpdate(connection, toolCallId)
                        .orElseThrow(() -> new IllegalArgumentException("tool execution not found: " + toolCallId));
                ToolExecutionRecord next = updater.apply(current);
                writeTool(connection, next);
                connection.commit();
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to update tool execution: " + toolCallId, exception);
        }
    }

    /**
     * 从 agent_run_state 表中读取 record_json 并反序列化为 AgentRunRecord
     */
    private Optional<AgentRunRecord> loadRunForUpdate(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT record_json FROM agent_run_state WHERE run_id = ? FOR UPDATE")) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(fromJson(resultSet.getString("record_json"), AgentRunRecord.class))
                        : Optional.empty();
            }
        }
    }

    private Optional<ToolExecutionRecord> loadToolForUpdate(Connection connection, String toolCallId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT record_json FROM agent_tool_execution WHERE tool_call_id = ? FOR UPDATE")) {
            statement.setString(1, toolCallId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(fromJson(resultSet.getString("record_json"), ToolExecutionRecord.class))
                        : Optional.empty();
            }
        }
    }

    private void bindRun(PreparedStatement statement, AgentRunRecord record) throws SQLException {
        statement.setString(1, record.runId());
        statement.setString(2, record.traceId());
        statement.setString(3, record.conversationId());
        statement.setString(4, record.state().name());
        statement.setString(5, record.phase().name());
        statement.setString(6, toJson(record));
        statement.setLong(7, record.version());
        statement.setTimestamp(8, Timestamp.from(record.createdAt()));
        statement.setTimestamp(9, Timestamp.from(record.updatedAt()));
    }

    /**
     * 根据新的 AgentRunRecord 更新数据库
     */
    private void writeRun(Connection connection, AgentRunRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_run_state
                SET status = ?, current_node = ?, record_json = ?, version = ?, updated_at = ?
                WHERE run_id = ?
                """)) {
            statement.setString(1, record.state().name());
            statement.setString(2, record.phase().name());
            statement.setString(3, toJson(record));
            statement.setLong(4, record.version());
            statement.setTimestamp(5, Timestamp.from(record.updatedAt()));
            statement.setString(6, record.runId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("agent run update lost: " + record.runId());
            }
        }
    }

    private void insertTool(Connection connection, ToolExecutionRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_tool_execution(
                    tool_call_id, run_id, tool_name, status, record_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindTool(statement, record);
            statement.executeUpdate();
        }
    }

    private void writeTool(Connection connection, ToolExecutionRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE agent_tool_execution
                SET status = ?, record_json = ?, updated_at = ?
                WHERE tool_call_id = ?
                """)) {
            statement.setString(1, record.state().name());
            statement.setString(2, toJson(record));
            statement.setTimestamp(3, Timestamp.from(record.updatedAt()));
            statement.setString(4, record.toolCallId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("tool execution update lost: " + record.toolCallId());
            }
        }
    }

    private void bindTool(PreparedStatement statement, ToolExecutionRecord record) throws SQLException {
        statement.setString(1, record.toolCallId());
        statement.setString(2, record.runId());
        statement.setString(3, record.toolName());
        statement.setString(4, record.state().name());
        statement.setString(5, toJson(record));
        statement.setTimestamp(6, Timestamp.from(record.createdAt()));
        statement.setTimestamp(7, Timestamp.from(record.updatedAt()));
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
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS agent_run_state (
                            run_id VARCHAR(64) PRIMARY KEY,
                            trace_id VARCHAR(64) NOT NULL UNIQUE,
                            conversation_id VARCHAR(160) NOT NULL,
                            status VARCHAR(32) NOT NULL,
                            current_node VARCHAR(64) NOT NULL,
                            record_json TEXT NOT NULL,
                            version BIGINT NOT NULL DEFAULT 0,
                            created_at TIMESTAMP NOT NULL,
                            updated_at TIMESTAMP NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_agent_run_status_updated
                        ON agent_run_state(status, updated_at DESC)
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS agent_tool_execution (
                            tool_call_id VARCHAR(64) PRIMARY KEY,
                            run_id VARCHAR(64) NOT NULL,
                            tool_name VARCHAR(160) NOT NULL,
                            status VARCHAR(32) NOT NULL,
                            record_json TEXT NOT NULL,
                            created_at TIMESTAMP NOT NULL,
                            updated_at TIMESTAMP NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_agent_tool_execution_run
                        ON agent_tool_execution(run_id, created_at)
                        """);
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new AgentStorageException("Failed to initialize agent runtime schema", exception);
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
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new AgentStorageException("Failed to serialize agent runtime record", exception);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        }
        catch (Exception exception) {
            throw new AgentStorageException("Failed to deserialize agent runtime record", exception);
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        }
        catch (SQLException ignored) {
            // Preserve the original storage failure.
        }
    }
}
