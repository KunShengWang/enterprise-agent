package com.agent.platform.resilience;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.config.ResilienceProperties;
import com.agent.platform.storage.AgentStorageException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多实例共享的 PostgreSQL 固定窗口限流器。
 */
@Primary
@Service
public class JdbcRateLimitService implements RateLimitService {

    private static final long WINDOW_MILLIS = 60_000;

    private final ResilienceProperties resilienceProperties;
    private final AgentStorageProperties storageProperties;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcRateLimitService(ResilienceProperties resilienceProperties,
                                AgentStorageProperties storageProperties) {
        this.resilienceProperties = resilienceProperties;
        this.storageProperties = storageProperties;
    }

    /**
     * "同一分钟窗口内累加计数，跨分钟自动重置"的固定窗口限流
     *  当一分钟内的请求次数小于最大限制的话就把 RateLimitResult 中的 allowed 设为 true 说明允许请求
     */
    @Override
    public RateLimitResult acquire(String key) {
        String effectiveKey = key == null || key.isBlank() ? "anonymous" : key.trim();
        if (!resilienceProperties.getRateLimit().isEnabled()) {
            return new RateLimitResult(true, effectiveKey, Integer.MAX_VALUE, Integer.MAX_VALUE,
                    System.currentTimeMillis() + WINDOW_MILLIS);
        }
        ensureSchema();
        int limit = Math.max(1, resilienceProperties.getRateLimit().getMaxRequestsPerMinute());
        long now = System.currentTimeMillis();
        long windowStart = now - now % WINDOW_MILLIS;
        long resetAt = windowStart + WINDOW_MILLIS;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     -- ① 尝试插入新记录，计数初始为 1
                     INSERT INTO agent_rate_limit(rate_key, window_start_millis, request_count, updated_at)
                     VALUES (?, ?, 1, ?)
                     -- ② 如果 rate_key 已存在（冲突），走 UPDATE
                     ON CONFLICT(rate_key) DO UPDATE SET
                         -- ③ 关键判断：新旧窗口是否属于同一分钟？
                         request_count = CASE
                             WHEN agent_rate_limit.window_start_millis = EXCLUDED.window_start_millis
                             THEN agent_rate_limit.request_count + 1 -- 同一分钟窗口 → 计数 +1
                             ELSE 1 -- 跨分钟了 → 重置为 1
                         END,
                         -- ④ 不管哪种情况，都更新窗口起始时间和时间戳
                         window_start_millis = EXCLUDED.window_start_millis,
                         updated_at = EXCLUDED.updated_at
                     -- ⑤ 返回更新后的 count，用于立即判断是否超限
                     RETURNING request_count
                     """)) {
            statement.setString(1, effectiveKey);
            statement.setLong(2, windowStart);
            statement.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(now)));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                int count = resultSet.getInt(1);
                return new RateLimitResult(count <= limit, effectiveKey, limit, Math.max(0, limit - count), resetAt);
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to acquire distributed rate limit for key: " + effectiveKey, exception);
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
                        CREATE TABLE IF NOT EXISTS agent_rate_limit (
                            rate_key TEXT PRIMARY KEY,
                            window_start_millis BIGINT NOT NULL,
                            request_count INTEGER NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                // 在 updated_at 字段上建立 B-tree 索引，索引名称为 idx_agent_rate_limit_updated
                statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_rate_limit_updated ON agent_rate_limit(updated_at)");
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new AgentStorageException("Failed to initialize rate limit schema", exception);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                storageProperties.getDatasource().getUrl(),
                storageProperties.getDatasource().getUsername(),
                storageProperties.getDatasource().getPassword()
        );
    }
}
