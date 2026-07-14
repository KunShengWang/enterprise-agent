package com.agent.platform.storage;

import com.agent.platform.config.AgentStorageProperties;
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

@Component
public class JdbcAgentStoreSupport {

    private final AgentStorageProperties properties;

    private final ObjectMapper objectMapper;

    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcAgentStoreSupport(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void save(String category, String key, Object value, Instant createdAt, Instant updatedAt) {
        if (category == null || category.isBlank() || key == null || key.isBlank()) {
            return;
        }
        ensureSchema();
        Instant now = Instant.now();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_store_record(category, record_key, record_json, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT(category, record_key)
                     DO UPDATE SET record_json = EXCLUDED.record_json, updated_at = EXCLUDED.updated_at
                     """)) {
            statement.setString(1, category);
            statement.setString(2, key.trim());
            statement.setString(3, toJson(value));
            statement.setTimestamp(4, Timestamp.from(createdAt == null ? now : createdAt));
            statement.setTimestamp(5, Timestamp.from(updatedAt == null ? now : updatedAt));
            statement.executeUpdate();
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to save agent store record: " + category + "/" + key, exception);
        }
    }

    public <T> Optional<T> find(String category, String key, Class<T> type) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT record_json FROM agent_store_record
                     WHERE category = ? AND record_key = ?
                     """)) {
            statement.setString(1, category);
            statement.setString(2, key.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(fromJson(resultSet.getString("record_json"), type));
                }
                return Optional.empty();
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to read agent store record: " + category + "/" + key, exception);
        }
    }

    public boolean updateIfJsonFieldEquals(String category,
                                           String key,
                                           String field,
                                           String expectedValue,
                                           Object nextValue,
                                           Instant updatedAt) {
        if (category == null || category.isBlank()
                || key == null || key.isBlank()
                || field == null || field.isBlank()
                || expectedValue == null
                || nextValue == null) {
            return false;
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_store_record
                     SET record_json = ?, updated_at = ?
                     WHERE category = ?
                       AND record_key = ?
                       AND jsonb_extract_path_text(record_json::jsonb, ?) = ?
                     """)) {
            statement.setString(1, toJson(nextValue));
            statement.setTimestamp(2, Timestamp.from(updatedAt == null ? Instant.now() : updatedAt));
            statement.setString(3, category);
            statement.setString(4, key.trim());
            statement.setString(5, field);
            statement.setString(6, expectedValue);
            return statement.executeUpdate() == 1;
        }
        catch (SQLException exception) {
            throw new AgentStorageException(
                    "Failed conditional update for agent store record: " + category + "/" + key,
                    exception
            );
        }
    }

    /**
     * 读取数据库中最近的几条数据，类似是 type 确定的，会进行反序列化
     */
    public <T> List<T> recent(String category, Class<T> type, int limit) {
        // 确保 agent_store_record 表的存在
        ensureSchema();
        int effectiveLimit = limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, limit);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT record_json FROM agent_store_record
                     WHERE category = ?
                     ORDER BY updated_at DESC
                     LIMIT ?
                     """)) {
            statement.setString(1, category);
            statement.setInt(2, effectiveLimit);
            List<T> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(fromJson(resultSet.getString("record_json"), type));
                }
            }
            return records;
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to read recent agent store records: " + category, exception);
        }
    }

    public boolean delete(String category, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM agent_store_record WHERE category = ? AND record_key = ?")) {
            statement.setString(1, category);
            statement.setString(2, key.trim());
            return statement.executeUpdate() > 0;
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to delete agent store record: " + category + "/" + key, exception);
        }
    }

    public void clear(String category) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM agent_store_record WHERE category = ?")) {
            statement.setString(1, category);
            statement.executeUpdate();
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to clear agent store category: " + category, exception);
        }
    }

    public int count(String category) {
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM agent_store_record WHERE category = ?")) {
            statement.setString(1, category);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to count agent store category: " + category, exception);
        }
    }

    /**
     * 确保 agent_store_record 表的存在
     */
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
                        CREATE TABLE IF NOT EXISTS agent_store_record (
                            category VARCHAR(64) NOT NULL,
                            record_key VARCHAR(160) NOT NULL,
                            record_json TEXT NOT NULL,
                            created_at TIMESTAMP NOT NULL,
                            updated_at TIMESTAMP NOT NULL,
                            PRIMARY KEY(category, record_key)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_agent_store_category_updated
                        ON agent_store_record(category, updated_at DESC)
                        """);
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new AgentStorageException("Failed to initialize agent store schema", exception);
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
            throw new AgentStorageException("Failed to serialize agent store record", exception);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        }
        catch (Exception exception) {
            throw new AgentStorageException("Failed to deserialize agent store record as " + type.getSimpleName(), exception);
        }
    }
}
