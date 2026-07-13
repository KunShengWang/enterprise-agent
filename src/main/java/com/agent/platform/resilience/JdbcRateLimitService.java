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
                     INSERT INTO agent_rate_limit(rate_key, window_start_millis, request_count, updated_at)
                     VALUES (?, ?, 1, ?)
                     ON CONFLICT(rate_key) DO UPDATE SET
                         request_count = CASE
                             WHEN agent_rate_limit.window_start_millis = EXCLUDED.window_start_millis
                             THEN agent_rate_limit.request_count + 1
                             ELSE 1
                         END,
                         window_start_millis = EXCLUDED.window_start_millis,
                         updated_at = EXCLUDED.updated_at
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
