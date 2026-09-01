package com.agent.platform.procurement.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.storage.AgentStorageException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 与 Agent Runtime 共享既有 storage datasource，仅增加一张当前采购 Case 表。 */
@Component
public class JdbcProcurementCaseStore implements ProcurementCaseStore {
    private final AgentStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean();

    public JdbcProcurementCaseStore(AgentStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties; this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ProcurementCase> findByTenantAndConversationId(String tenantId, String conversationId) {
        if (tenantId == null || tenantId.isBlank() || conversationId == null || conversationId.isBlank()) return Optional.empty();
        ensureSchema();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT record_json, version FROM procurement_case_state WHERE tenant_id = ? AND conversation_id = ?")) {
            statement.setString(1, tenantId.trim()); statement.setString(2, conversationId.trim());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readCase(result.getString(1), result.getLong(2))) : Optional.empty();
            }
        }
        catch (SQLException exception) { throw new AgentStorageException("failed to find tenant procurement case", exception); }
    }

    @Override
    public Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId, String userId, String conversationId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()
                || conversationId == null || conversationId.isBlank()) return Optional.empty();
        ensureSchema();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT record_json, version FROM procurement_case_state WHERE tenant_id = ? AND user_id = ? AND conversation_id = ?")) {
            statement.setString(1, tenantId.trim()); statement.setString(2, userId.trim()); statement.setString(3, conversationId.trim());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readCase(result.getString(1), result.getLong(2))) : Optional.empty();
            }
        }
        catch (SQLException exception) { throw new AgentStorageException("failed to find user procurement case", exception); }
    }

    @Override
    public ProcurementCase save(ProcurementCase procurementCase) {
        if (procurementCase == null) throw new IllegalArgumentException("procurement case is required");
        ensureSchema();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO procurement_case_state(case_id, tenant_id, conversation_id, user_id, status, version, record_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, conversation_id) DO UPDATE SET
                    case_id = EXCLUDED.case_id, user_id = EXCLUDED.user_id, status = EXCLUDED.status,
                    version = EXCLUDED.version, record_json = EXCLUDED.record_json, updated_at = EXCLUDED.updated_at
                """)) {
            statement.setString(1, procurementCase.caseId());
            statement.setString(2, procurementCase.tenantId());
            statement.setString(3, procurementCase.conversationId());
            statement.setString(4, procurementCase.userId());
            statement.setString(5, procurementCase.status().name());
            statement.setLong(6, procurementCase.version());
            statement.setString(7, objectMapper.writeValueAsString(procurementCase));
            statement.setTimestamp(8, Timestamp.from(procurementCase.createdAt()));
            statement.setTimestamp(9, Timestamp.from(procurementCase.updatedAt()));
            statement.executeUpdate();
            return procurementCase;
        }
        catch (Exception exception) { throw new AgentStorageException("failed to save procurement case", exception); }
    }

    @Override
    public boolean saveIfVersion(ProcurementCase procurementCase, long expectedVersion) {
        if (procurementCase == null) throw new IllegalArgumentException("procurement case is required");
        ensureSchema();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE procurement_case_state
                   SET case_id = ?, status = ?, version = ?, record_json = ?, updated_at = ?
                 WHERE tenant_id = ? AND user_id = ? AND conversation_id = ? AND version = ?
                """)) {
            statement.setString(1, procurementCase.caseId());
            statement.setString(2, procurementCase.status().name());
            statement.setLong(3, procurementCase.version());
            statement.setString(4, objectMapper.writeValueAsString(procurementCase));
            statement.setTimestamp(5, Timestamp.from(procurementCase.updatedAt()));
            statement.setString(6, procurementCase.tenantId());
            statement.setString(7, procurementCase.userId());
            statement.setString(8, procurementCase.conversationId());
            statement.setLong(9, expectedVersion);
            return statement.executeUpdate() == 1;
        }
        catch (Exception exception) { throw new AgentStorageException("failed to conditionally save procurement case", exception); }
    }

    @Override
    public boolean createIfAbsent(ProcurementCase procurementCase) {
        if (procurementCase == null) throw new IllegalArgumentException("procurement case is required");
        ensureSchema();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO procurement_case_state(case_id, tenant_id, conversation_id, user_id, status, version, record_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, conversation_id) DO NOTHING
                """)) {
            statement.setString(1, procurementCase.caseId());
            statement.setString(2, procurementCase.tenantId());
            statement.setString(3, procurementCase.conversationId());
            statement.setString(4, procurementCase.userId());
            statement.setString(5, procurementCase.status().name());
            statement.setLong(6, procurementCase.version());
            statement.setString(7, objectMapper.writeValueAsString(procurementCase));
            statement.setTimestamp(8, Timestamp.from(procurementCase.createdAt()));
            statement.setTimestamp(9, Timestamp.from(procurementCase.updatedAt()));
            return statement.executeUpdate() == 1;
        }
        catch (Exception exception) { throw new AgentStorageException("failed to create procurement case", exception); }
    }

    private void ensureSchema() {
        if (schemaReady.get()) return;
        synchronized (schemaReady) {
            if (schemaReady.get()) return;
            try (Connection connection = open();
                 PreparedStatement create = connection.prepareStatement("""
                         CREATE TABLE IF NOT EXISTS procurement_case_state (
                             case_id VARCHAR(128) PRIMARY KEY,
                             tenant_id VARCHAR(256) NOT NULL,
                             conversation_id VARCHAR(256) NOT NULL,
                             user_id VARCHAR(256) NOT NULL,
                             status VARCHAR(64) NOT NULL,
                             version BIGINT NOT NULL DEFAULT 0,
                             record_json TEXT NOT NULL,
                             created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                             updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                             UNIQUE (tenant_id, user_id, conversation_id)
                         )
                         """);
                 PreparedStatement addVersion = connection.prepareStatement(
                         "ALTER TABLE procurement_case_state ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0");
                 PreparedStatement syncVersions = connection.prepareStatement("""
                         UPDATE procurement_case_state
                            SET version = CASE
                                WHEN (record_json::jsonb ->> 'version') ~ '^[0-9]+$'
                                THEN (record_json::jsonb ->> 'version')::bigint
                                ELSE version
                            END
                         """)) {
                create.executeUpdate();
                addVersion.executeUpdate();
                syncVersions.executeUpdate();
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new AgentStorageException("failed to initialize or migrate procurement case schema", exception);
            }
        }
    }

    private ProcurementCase readCase(String json, long storedVersion) {
        ProcurementCase value = objectMapper.readValue(json, ProcurementCase.class);
        if (value.version() == storedVersion) return value;
        return new ProcurementCase(value.caseId(), value.tenantId(), value.conversationId(), value.userId(),
                value.status(), value.state(), value.createdAt(), value.updatedAt(), storedVersion,
                value.lastAppliedInputId(), value.appliedInputIds());
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(properties.getDatasource().getUrl(), properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }
}
