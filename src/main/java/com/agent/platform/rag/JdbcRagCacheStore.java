package com.agent.platform.rag;

import com.agent.platform.config.RagProperties;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL-backed RAG result cache shared by all application instances.
 */
@Component
public class JdbcRagCacheStore {

    private static final String MISS_METRIC = "misses";

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcRagCacheStore(RagProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Optional<RagResult> find(String cacheKey) {
        if (!properties.getCache().isEnabled()) {
            return Optional.empty();
        }
        ensureSchema();
        Instant now = Instant.now();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_rag_cache
                     SET hit_count = hit_count + 1, last_accessed_at = ?
                     WHERE cache_key = ? AND expires_at > ?
                     RETURNING result_json
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, cacheKey);
            statement.setTimestamp(3, Timestamp.from(now));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(readResult(resultSet.getString("result_json")));
                }
            }
            deleteExpired(now);
            return Optional.empty();
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to read PostgreSQL RAG cache", exception);
        }
    }

    public void recordMiss() {
        if (!properties.getCache().isEnabled()) {
            return;
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_rag_cache_metric(metric_key, metric_value)
                     VALUES (?, 1)
                     ON CONFLICT(metric_key)
                     DO UPDATE SET metric_value = agent_rag_cache_metric.metric_value + 1
                     """)) {
            statement.setString(1, MISS_METRIC);
            statement.executeUpdate();
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to record PostgreSQL RAG cache miss", exception);
        }
    }

    public void save(String cacheKey, RagResult result) {
        if (!properties.getCache().isEnabled() || result == null) {
            return;
        }
        ensureSchema();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(1, properties.getCache().getTtlSeconds()));
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_rag_cache(
                         cache_key, result_json, expires_at, created_at, last_accessed_at, hit_count
                     ) VALUES (?, ?, ?, ?, ?, 0)
                     ON CONFLICT(cache_key)
                     DO UPDATE SET
                         result_json = EXCLUDED.result_json,
                         expires_at = EXCLUDED.expires_at,
                         last_accessed_at = EXCLUDED.last_accessed_at
                     """)) {
            statement.setString(1, cacheKey);
            statement.setString(2, writeResult(result));
            statement.setTimestamp(3, Timestamp.from(expiresAt));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
            trimToMaxEntries(connection, Math.max(1, properties.getCache().getMaxEntries()));
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to write PostgreSQL RAG cache", exception);
        }
    }

    public RagCacheStats stats() {
        if (!properties.getCache().isEnabled()) {
            return new RagCacheStats(false, 0, 0, 0, 0,
                    Math.max(1, properties.getCache().getTtlSeconds()),
                    Math.max(1, properties.getCache().getMaxEntries()));
        }
        ensureSchema();
        deleteExpired(Instant.now());
        try (Connection connection = openConnection()) {
            long hits;
            int size;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) AS cache_size, COALESCE(SUM(hit_count), 0) AS hits
                    FROM agent_rag_cache
                    WHERE expires_at > ?
                    """)) {
                statement.setTimestamp(1, Timestamp.from(Instant.now()));
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    size = resultSet.getInt("cache_size");
                    hits = resultSet.getLong("hits");
                }
            }
            long misses = readMetric(connection, MISS_METRIC);
            long total = hits + misses;
            return new RagCacheStats(
                    true,
                    size,
                    hits,
                    misses,
                    total == 0 ? 0 : (double) hits / total,
                    Math.max(1, properties.getCache().getTtlSeconds()),
                    Math.max(1, properties.getCache().getMaxEntries())
            );
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to read PostgreSQL RAG cache stats", exception);
        }
    }

    public void clear() {
        ensureSchema();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM agent_rag_cache");
            statement.executeUpdate("DELETE FROM agent_rag_cache_metric");
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to clear PostgreSQL RAG cache", exception);
        }
    }

    private void deleteExpired(Instant now) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM agent_rag_cache WHERE expires_at <= ?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.executeUpdate();
        }
        catch (SQLException exception) {
            throw new PgVectorException("Failed to remove expired PostgreSQL RAG cache entries", exception);
        }
    }

    private void trimToMaxEntries(Connection connection, int maxEntries) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM agent_rag_cache
                WHERE cache_key IN (
                    SELECT cache_key
                    FROM agent_rag_cache
                    ORDER BY last_accessed_at DESC
                    OFFSET ?
                )
                """)) {
            statement.setInt(1, maxEntries);
            statement.executeUpdate();
        }
    }

    private long readMetric(Connection connection, String metricKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT metric_value FROM agent_rag_cache_metric WHERE metric_key = ?")) {
            statement.setString(1, metricKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong("metric_value") : 0;
            }
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
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS agent_rag_cache (
                            cache_key VARCHAR(64) PRIMARY KEY,
                            result_json TEXT NOT NULL,
                            expires_at TIMESTAMP NOT NULL,
                            created_at TIMESTAMP NOT NULL,
                            last_accessed_at TIMESTAMP NOT NULL,
                            hit_count BIGINT NOT NULL DEFAULT 0
                        )
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_agent_rag_cache_expiry_access
                        ON agent_rag_cache(expires_at, last_accessed_at DESC)
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS agent_rag_cache_metric (
                            metric_key VARCHAR(64) PRIMARY KEY,
                            metric_value BIGINT NOT NULL
                        )
                        """);
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new PgVectorException("Failed to initialize PostgreSQL RAG cache schema", exception);
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

    private String writeResult(RagResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception exception) {
            throw new PgVectorException("Failed to serialize RAG cache entry", exception);
        }
    }

    private RagResult readResult(String json) {
        try {
            return objectMapper.readValue(json, RagResult.class);
        }
        catch (Exception exception) {
            throw new PgVectorException("Failed to deserialize RAG cache entry", exception);
        }
    }
}
