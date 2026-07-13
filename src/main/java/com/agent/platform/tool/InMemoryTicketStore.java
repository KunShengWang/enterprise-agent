package com.agent.platform.tool;


import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Deprecated(forRemoval = true)
public class InMemoryTicketStore implements TicketStore {

    private final ConcurrentMap<String, SupportTicket> tickets = new ConcurrentHashMap<>();

    private final AtomicInteger sequence = new AtomicInteger(2000);

    public InMemoryTicketStore() {
        Instant now = Instant.now();
        tickets.put("T1001", new SupportTicket("T1001", "登录失败影响客服工作台", "P1", "处理中", "张三", now, now));
        tickets.put("T1002", new SupportTicket("T1002", "退款审批页面响应慢", "P2", "待处理", "李四", now, now));
    }

    @Override
    public Optional<SupportTicket> findById(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tickets.get(ticketId.trim().toUpperCase()));
    }

    @Override
    public SupportTicket create(String title, String priority) {
        Instant now = Instant.now();
        String ticketId = "T" + sequence.incrementAndGet();
        SupportTicket ticket = new SupportTicket(
                ticketId,
                blankToDefault(title, "用户问题待处理"),
                normalizePriority(priority),
                "待处理",
                "未分配",
                now,
                now
        );
        tickets.put(ticketId, ticket);
        return ticket;
    }

    @Override
    public Optional<SupportTicket> updatePriority(String ticketId, String priority) {
        return findById(ticketId).map(ticket -> {
            SupportTicket updated = new SupportTicket(
                    ticket.ticketId(),
                    ticket.title(),
                    normalizePriority(priority),
                    ticket.status(),
                    ticket.assignee(),
                    ticket.createdAt(),
                    Instant.now()
            );
            tickets.put(ticket.ticketId(), updated);
            return updated;
        });
    }

    @Override
    public Optional<SupportTicket> close(String ticketId, String reason) {
        return findById(ticketId).map(ticket -> {
            SupportTicket updated = new SupportTicket(
                    ticket.ticketId(),
                    ticket.title(),
                    ticket.priority(),
                    "已关闭",
                    ticket.assignee(),
                    ticket.createdAt(),
                    Instant.now()
            );
            tickets.put(ticket.ticketId(), updated);
            return updated;
        });
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return "P2";
        }
        String normalized = priority.trim().toUpperCase();
        return switch (normalized) {
            case "P0", "P1", "P2", "P3" -> normalized;
            default -> "P2";
        };
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
