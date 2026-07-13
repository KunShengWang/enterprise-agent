package com.agent.platform.tool;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.storage.AgentStorageException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

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

@Primary
@Repository
public class JdbcTicketStore implements TicketStore {

    private final AgentStorageProperties properties;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcTicketStore(AgentStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<SupportTicket> findById(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT ticket_id, title, priority, status, assignee, created_at, updated_at
                     FROM support_ticket WHERE ticket_id = ?
                     """)) {
            statement.setString(1, ticketId.trim().toUpperCase());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readTicket(resultSet)) : Optional.empty();
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to read support ticket: " + ticketId, exception);
        }
    }

    @Override
    public SupportTicket create(String title, String priority) {
        ensureSchema();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                long number = nextTicketNumber(connection);
                SupportTicket ticket = new SupportTicket(
                        "T" + number,
                        blankToDefault(title, "用户问题待处理"),
                        normalizePriority(priority),
                        "待处理",
                        "未分配",
                        Instant.now(),
                        Instant.now()
                );
                insertTicket(connection, ticket);
                connection.commit();
                return ticket;
            }
            catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to create support ticket", exception);
        }
    }

    @Override
    public Optional<SupportTicket> updatePriority(String ticketId, String priority) {
        return updateReturning(ticketId,
                "UPDATE support_ticket SET priority = ?, updated_at = ? WHERE ticket_id = ? RETURNING ticket_id, title, priority, status, assignee, created_at, updated_at",
                normalizePriority(priority));
    }

    @Override
    public Optional<SupportTicket> close(String ticketId, String reason) {
        ensureSchema();
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE support_ticket
                     SET status = '已关闭', close_reason = ?, updated_at = ?
                     WHERE ticket_id = ? AND status <> '已关闭'
                     RETURNING ticket_id, title, priority, status, assignee, created_at, updated_at
                     """)) {
            statement.setString(1, blankToDefault(reason, "用户请求关闭"));
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setString(3, ticketId.trim().toUpperCase());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(readTicket(resultSet));
                }
            }
            return findById(ticketId).filter(ticket -> "已关闭".equals(ticket.status()));
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to close support ticket: " + ticketId, exception);
        }
    }

    private Optional<SupportTicket> updateReturning(String ticketId, String sql, String value) {
        ensureSchema();
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setString(3, ticketId.trim().toUpperCase());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readTicket(resultSet)) : Optional.empty();
            }
        }
        catch (SQLException exception) {
            throw new AgentStorageException("Failed to update support ticket: " + ticketId, exception);
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
                statement.execute("CREATE SEQUENCE IF NOT EXISTS support_ticket_number_seq START WITH 2001");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS support_ticket (
                            ticket_id TEXT PRIMARY KEY,
                            title TEXT NOT NULL,
                            priority TEXT NOT NULL,
                            status TEXT NOT NULL,
                            assignee TEXT NOT NULL,
                            close_reason TEXT NOT NULL DEFAULT '',
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                Instant now = Instant.now();
                seedTicket(connection, new SupportTicket("T1001", "登录失败影响客服工作台", "P1", "处理中", "张三", now, now));
                seedTicket(connection, new SupportTicket("T1002", "退款审批页面响应慢", "P2", "待处理", "李四", now, now));
                statement.execute("CREATE INDEX IF NOT EXISTS idx_support_ticket_status_priority ON support_ticket(status, priority, updated_at DESC)");
                schemaReady.set(true);
            }
            catch (SQLException exception) {
                throw new AgentStorageException("Failed to initialize support ticket schema", exception);
            }
        }
    }

    private void seedTicket(Connection connection, SupportTicket ticket) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO support_ticket(ticket_id, title, priority, status, assignee, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(ticket_id) DO NOTHING
                """)) {
            bindTicket(statement, ticket);
            statement.executeUpdate();
        }
    }

    private void insertTicket(Connection connection, SupportTicket ticket) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO support_ticket(ticket_id, title, priority, status, assignee, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindTicket(statement, ticket);
            statement.executeUpdate();
        }
    }

    private void bindTicket(PreparedStatement statement, SupportTicket ticket) throws SQLException {
        statement.setString(1, ticket.ticketId());
        statement.setString(2, ticket.title());
        statement.setString(3, ticket.priority());
        statement.setString(4, ticket.status());
        statement.setString(5, ticket.assignee());
        statement.setTimestamp(6, Timestamp.from(ticket.createdAt()));
        statement.setTimestamp(7, Timestamp.from(ticket.updatedAt()));
    }

    private SupportTicket readTicket(ResultSet resultSet) throws SQLException {
        return new SupportTicket(
                resultSet.getString("ticket_id"),
                resultSet.getString("title"),
                resultSet.getString("priority"),
                resultSet.getString("status"),
                resultSet.getString("assignee"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private long nextTicketNumber(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT nextval('support_ticket_number_seq')")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String normalizePriority(String priority) {
        String normalized = blankToDefault(priority, "P2").toUpperCase();
        return switch (normalized) {
            case "P0", "P1", "P2", "P3" -> normalized;
            default -> "P2";
        };
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
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
        }
    }
}
