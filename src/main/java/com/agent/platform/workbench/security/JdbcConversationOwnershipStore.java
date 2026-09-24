package com.agent.platform.workbench.security;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import com.agent.platform.workbench.persistence.WorkbenchAccessDeniedException;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** One global owner per Timeline ID. No changes to Runtime, Timeline or existing Case IDs. */
@Repository
public class JdbcConversationOwnershipStore implements ConversationOwnershipStore {
    private final AgentStorageProperties properties;
    private volatile boolean schemaReady;

    public JdbcConversationOwnershipStore(AgentStorageProperties properties) { this.properties = properties; }

    @Override
    public boolean check(AuthenticatedPrincipal principal, String id, boolean claim) {
        ensureSchema();
        try (var connection = open()) {
            connection.setAutoCommit(false);
            try {
                // Unique-key insertion serializes competing principals across processes, not just this JVM.
                boolean inserted;
                try (var statement = connection.prepareStatement("""
                        INSERT INTO agent_public_conversation_owner(session_id, tenant_id, principal_id)
                        VALUES (?, ?, ?) ON CONFLICT(session_id) DO NOTHING
                        """)) {
                    statement.setString(1, id); statement.setString(2, principal.tenantId());
                    statement.setString(3, principal.principalId());
                    inserted = statement.executeUpdate() == 1;
                }
                try (var statement = connection.prepareStatement("""
                        SELECT tenant_id, principal_id FROM agent_public_conversation_owner
                        WHERE session_id = ? FOR UPDATE
                        """)) {
                    statement.setString(1, id);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || !principal.tenantId().equals(rows.getString(1))
                                || !principal.principalId().equals(rows.getString(2))) deny();
                    }
                }
                // Adopt legacy IDs only with unambiguous tenant AND user evidence. userId alone is insufficient.
                boolean evidence = legacyOwners(connection, "agent_conversation_work_state", "owner_principal_id", id, principal);
                evidence |= legacyOwners(connection, "procurement_case_state", "user_id", id, principal);
                if (exists(connection, "agent_session")) {
                    try (var statement = connection.prepareStatement("SELECT user_id FROM agent_session WHERE session_id = ?")) {
                        statement.setString(1, id);
                        try (var rows = statement.executeQuery()) {
                            if (rows.next() && (!principal.principalId().equals(rows.getString(1))
                                    || (inserted && !evidence))) deny();
                        }
                    }
                }
                if (inserted && !evidence && !claim) {
                    connection.rollback();
                    return false;
                }
                connection.commit();
                return true;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new AgentStorageException("failed to verify conversation ownership", exception);
        }
    }

    private boolean legacyOwners(Connection connection, String table, String userColumn, String id,
                                 AuthenticatedPrincipal principal) throws SQLException {
        if (!exists(connection, table)) return false;
        boolean found = false;
        // Table/column identifiers are fixed server constants, never client input.
        try (var statement = connection.prepareStatement("SELECT tenant_id, " + userColumn
                + " FROM " + table + " WHERE conversation_id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    found = true;
                    if (!principal.tenantId().equals(rows.getString(1))
                            || !principal.principalId().equals(rows.getString(2))) deny();
                }
            }
        }
        return found;
    }

    private boolean exists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) { return rows.next() && rows.getString(1) != null; }
        }
    }

    private synchronized void ensureSchema() {
        if (schemaReady) return;
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS agent_public_conversation_owner (
                        session_id VARCHAR(256) PRIMARY KEY,
                        tenant_id VARCHAR(256) NOT NULL,
                        principal_id VARCHAR(256) NOT NULL
                    )
                    """);
            schemaReady = true;
        } catch (SQLException exception) {
            throw new AgentStorageException("failed to initialize conversation ownership", exception);
        }
    }

    protected Connection open() throws SQLException {
        var datasource = properties.getDatasource();
        return DriverManager.getConnection(datasource.getUrl(), datasource.getUsername(), datasource.getPassword());
    }

    private static void deny() { throw new WorkbenchAccessDeniedException("conversation is not owned by current identity"); }
}
