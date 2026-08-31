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
                "SELECT record_json FROM procurement_case_state WHERE tenant_id = ? AND conversation_id = ?")) {
            statement.setString(1, tenantId.trim()); statement.setString(2, conversationId.trim());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(objectMapper.readValue(result.getString(1), ProcurementCase.class)) : Optional.empty();
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
                "SELECT record_json FROM procurement_case_state WHERE tenant_id = ? AND user_id = ? AND conversation_id = ?")) {
            statement.setString(1, tenantId.trim()); statement.setString(2, userId.trim()); statement.setString(3, conversationId.trim());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(objectMapper.readValue(result.getString(1), ProcurementCase.class)) : Optional.empty();
            }
        }
        catch (SQLException exception) { throw new AgentStorageException("failed to find user procurement case", exception); }
    }

    @Override
    public ProcurementCase save(ProcurementCase procurementCase) {
        if (procurementCase == null) throw new IllegalArgumentException("procurement case is required");
        ensureSchema();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO procurement_case_state(case_id, tenant_id, conversation_id, user_id, status, record_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, conversation_id) DO UPDATE SET
                    case_id = EXCLUDED.case_id, user_id = EXCLUDED.user_id, status = EXCLUDED.status,
                    record_json = EXCLUDED.record_json, updated_at = EXCLUDED.updated_at
                """)) {
            statement.setString(1, procurementCase.caseId());
            statement.setString(2, procurementCase.tenantId());
            statement.setString(3, procurementCase.conversationId());
            statement.setString(4, procurementCase.userId());
            statement.setString(5, procurementCase.status().name());
            statement.setString(6, objectMapper.writeValueAsString(procurementCase));
            statement.setTimestamp(7, Timestamp.from(procurementCase.createdAt()));
            statement.setTimestamp(8, Timestamp.from(procurementCase.updatedAt()));
            statement.executeUpdate();
            return procurementCase;
        }
        catch (Exception exception) { throw new AgentStorageException("failed to save procurement case", exception); }
    }

    private void ensureSchema() {
        if (schemaReady.get()) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                CREATE TABLE IF NOT EXISTS procurement_case_state (
                    case_id VARCHAR(128) PRIMARY KEY,
                    tenant_id VARCHAR(256) NOT NULL,
                    conversation_id VARCHAR(256) NOT NULL,
                    user_id VARCHAR(256) NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    record_json TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (tenant_id, user_id, conversation_id)
                )
                """)) {
            statement.executeUpdate(); schemaReady.set(true);
        }
        catch (SQLException exception) { throw new AgentStorageException("failed to initialize procurement case schema", exception); }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(properties.getDatasource().getUrl(), properties.getDatasource().getUsername(), properties.getDatasource().getPassword());
    }
}
