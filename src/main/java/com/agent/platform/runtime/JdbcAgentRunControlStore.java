package com.agent.platform.runtime;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL 会话租约和取消信号实现，支持多应用实例共享控制状态。
 */
@Repository
public class JdbcAgentRunControlStore implements AgentRunControlStore {

    private final AgentStorageProperties properties;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcAgentRunControlStore(AgentStorageProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取 session 租赁，防止同一 session 被多个 run 并发执行
     */
    @Override
    public void acquireSessionLease(String sessionId,
                                    String runId,
                                    String leaseOwnerId,
                                    Duration leaseDuration) {
        ensureSchema();
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(normalizeDuration(leaseDuration));
        try (Connection connection = openConnection()) {
            // 开启事务
            connection.setAutoCommit(false);
            try {
                // ① 注册 run control（就是这段 SQL） 建控制记录
                registerRunControl(connection, sessionId, runId, now);
                /*
                    ② 抢 session 租约 — 防止并发冲突
                    租约获取有四种情况：
                    1、无记录，直接插入，获取租约
                    2、有记录，满足条件一，是同一个 owner，续约
                    3、有记录，满足条件二，租约已经过期，抢走租约
                    4、有记录，其他人还持有，不能获取租约，抛异常
                 */
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO agent_session_lease(session_id, owner_run_id, lease_until, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(session_id) DO UPDATE SET
                            owner_run_id = EXCLUDED.owner_run_id, -- 换成新的持有者
                            lease_until = EXCLUDED.lease_until, -- 换成新的过期时间
                            updated_at = EXCLUDED.updated_at
                        WHERE agent_session_lease.owner_run_id = EXCLUDED.owner_run_id -- 同一个 session 的 owner
                           OR agent_session_lease.lease_until <= EXCLUDED.updated_at -- 租约过期
                        RETURNING owner_run_id -- 返回更新后的持有者 ID
                        """)) {
                    statement.setString(1, sessionId);
                    statement.setString(2, leaseOwnerId);
                    statement.setTimestamp(3, Timestamp.from(leaseUntil));
                    statement.setTimestamp(4, Timestamp.from(now));
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {// ← RETURNING 没返回任何行
                            connection.rollback();
                            throw new AgentSessionBusyException(sessionId);// 租约被占
                        }
                    }
                }
                connection.commit();// ①② 原子提交
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to acquire agent session lease: " + sessionId, exception);
        }
    }

    /**
     * 更新 session 租约
     */
    @Override
    public boolean renewSessionLease(String sessionId, String leaseOwnerId, Duration leaseDuration) {
        ensureSchema();
        Instant now = Instant.now();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_session_lease
                     SET lease_until = ?, updated_at = ?
                     WHERE session_id = ? AND owner_run_id = ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now.plus(normalizeDuration(leaseDuration))));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, sessionId);
            statement.setString(4, leaseOwnerId);
            return statement.executeUpdate() == 1;
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to renew agent session lease: " + sessionId, exception);
        }
    }

    /**
     * 释放 session 租约
     */
    @Override
    public void releaseSessionLease(String sessionId, String leaseOwnerId) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM agent_session_lease WHERE session_id = ? AND owner_run_id = ?
                     """)) {
            statement.setString(1, sessionId);
            statement.setString(2, leaseOwnerId);
            statement.executeUpdate();
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to release agent session lease: " + sessionId, exception);
        }
    }

    @Override
    public boolean requestCancellation(String runId) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_run_control
                     SET cancellation_requested = TRUE, updated_at = ?
                     WHERE run_id = ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, runId);
            return statement.executeUpdate() == 1;
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to request agent run cancellation: " + runId, exception);
        }
    }

    /**
     * 查看是否有 agent 的取消请求
     */
    @Override
    public boolean cancellationRequested(String runId) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT cancellation_requested FROM agent_run_control WHERE run_id = ?
                     """)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to read agent run cancellation: " + runId, exception);
        }
    }

    private void registerRunControl(Connection connection,
                                    String sessionId,
                                    String runId,
                                    Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_run_control(run_id, session_id, cancellation_requested, created_at, updated_at)
                VALUES (?, ?, FALSE, ?, ?)
                ON CONFLICT(run_id) DO UPDATE SET session_id = EXCLUDED.session_id, updated_at = EXCLUDED.updated_at
                """)) {
            statement.setString(1, runId);
            statement.setString(2, sessionId);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setTimestamp(4, Timestamp.from(now));
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
                        CREATE TABLE IF NOT EXISTS agent_run_control (
                            run_id TEXT PRIMARY KEY,
                            session_id TEXT NOT NULL,
                            cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS agent_session_lease (
                            session_id TEXT PRIMARY KEY,
                            owner_run_id TEXT NOT NULL,
                            lease_until TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_session_lease_until ON agent_session_lease(lease_until)");
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new AgentStorageException("Failed to initialize agent run control schema", exception);
            }
        }
    }

    private Duration normalizeDuration(Duration duration) {
        return duration == null || duration.isNegative() || duration.isZero()
                ? Duration.ofMinutes(3)
                : duration;
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
            // Preserve the original failure.
        }
    }
}
