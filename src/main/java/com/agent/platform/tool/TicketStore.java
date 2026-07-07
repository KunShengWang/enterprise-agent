package com.agent.platform.tool;

import java.util.Optional;

public interface TicketStore {

    Optional<SupportTicket> findById(String ticketId);

    SupportTicket create(String title, String priority);

    Optional<SupportTicket> updatePriority(String ticketId, String priority);

    Optional<SupportTicket> close(String ticketId, String reason);
}
