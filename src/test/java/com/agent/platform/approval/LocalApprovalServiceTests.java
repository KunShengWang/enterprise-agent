package com.agent.platform.approval;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.tool.ToolCallRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalApprovalServiceTests {

    @Test
    void approvalExpiryIsIndependentFromRunExecutionTimeout() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxRunDurationMillis(120_000);
        properties.setApprovalTtlSeconds(86_400);
        AtomicReference<ApprovalRecord> persisted = new AtomicReference<>();
        LocalApprovalService service = new LocalApprovalService(new TestApprovalStore(persisted), properties);
        Instant createdAt = Instant.now();

        service.requestApproval(new ApprovalRequest(
                "approval-1", "run-1", "session-1",
                new ToolCallRequest("ticket_close", "call-1", Map.of()),
                "high risk", createdAt
        ));

        assertEquals(createdAt.plusSeconds(86_400), persisted.get().expiresAt());
        assertEquals(ApprovalStatus.REQUESTED, service.find("approval-1").orElseThrow().status());
    }

    @Test
    void expiredApprovalIsPersistedAndCannotBeApproved() {
        AgentProperties properties = new AgentProperties();
        properties.setApprovalTtlSeconds(60);
        AtomicReference<ApprovalRecord> persisted = new AtomicReference<>();
        LocalApprovalService service = new LocalApprovalService(new TestApprovalStore(persisted), properties);
        service.requestApproval(new ApprovalRequest(
                "approval-1", "run-1", "session-1",
                new ToolCallRequest("ticket_close", "call-1", Map.of()),
                "high risk", Instant.now().minusSeconds(120)
        ));

        ApprovalRecord expired = service.find("approval-1").orElseThrow();

        assertEquals(ApprovalStatus.EXPIRED, expired.status());
        assertEquals(ApprovalStatus.EXPIRED, persisted.get().status());
        assertThrows(IllegalArgumentException.class,
                () -> service.decide("approval-1", true, "reviewer", "late approval"));
    }

    private record TestApprovalStore(AtomicReference<ApprovalRecord> record) implements ApprovalStore {

        @Override
        public void save(ApprovalRecord approvalRecord) {
            record.set(approvalRecord);
        }

        @Override
        public Optional<ApprovalRecord> find(String approvalId) {
            return Optional.ofNullable(record.get())
                    .filter(value -> value.approvalId().equals(approvalId));
        }

        @Override
        public List<ApprovalRecord> recent(int limit) {
            return record.get() == null ? List.of() : List.of(record.get());
        }
    }
}
