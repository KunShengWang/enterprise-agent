package com.agent.platform.approval;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.tool.ToolCallRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void conflictingConcurrentDecisionsCannotOverwriteTheWinner() throws Exception {
        AgentProperties properties = new AgentProperties();
        AtomicReference<ApprovalRecord> persisted = new AtomicReference<>();
        LocalApprovalService service = new LocalApprovalService(new TestApprovalStore(persisted), properties);
        service.requestApproval(new ApprovalRequest(
                "approval-1", "run-1", "session-1",
                new ToolCallRequest("ticket_close", "call-1", Map.of()),
                "high risk", Instant.now()
        ));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<DecisionAttempt> approve = executor.submit(
                    () -> decideAfter(start, service, true, "approve-reviewer")
            );
            Future<DecisionAttempt> reject = executor.submit(
                    () -> decideAfter(start, service, false, "reject-reviewer")
            );
            start.countDown();

            DecisionAttempt first = approve.get(5, TimeUnit.SECONDS);
            DecisionAttempt second = reject.get(5, TimeUnit.SECONDS);
            List<DecisionAttempt> attempts = List.of(first, second);
            List<DecisionAttempt> winners = attempts.stream().filter(DecisionAttempt::success).toList();

            assertEquals(1, winners.size());
            assertEquals(winners.get(0).status(), persisted.get().status());
            assertEquals(winners.get(0).reviewer(), persisted.get().reviewer());
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void decisionCannotSucceedWhenApprovalExpiresBetweenReadAndDatabaseCas() {
        Instant beforeExpiry = Instant.parse("2026-07-14T06:00:00Z");
        Instant expiresAt = beforeExpiry.plusSeconds(1);
        Instant afterExpiry = beforeExpiry.plusSeconds(2);
        AtomicReference<ApprovalRecord> persisted = new AtomicReference<>(new ApprovalRecord(
                "approval-1", "run-1", "session-1",
                new ToolCallRequest("ticket_close", "execution-1", Map.of()),
                "high risk", ApprovalStatus.REQUESTED, "", "",
                beforeExpiry.minusSeconds(10), expiresAt, null
        ));
        LocalApprovalService service = new LocalApprovalService(
                new TestApprovalStore(persisted),
                new AgentProperties(),
                new SequenceClock(beforeExpiry, afterExpiry, afterExpiry)
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.decide("approval-1", true, "reviewer", "too late"));
        assertEquals(ApprovalStatus.EXPIRED, persisted.get().status());
    }

    @Test
    void recentApprovalsAlsoConvergeExpiredRequestedRecords() {
        AgentProperties properties = new AgentProperties();
        properties.setApprovalTtlSeconds(60);
        AtomicReference<ApprovalRecord> persisted = new AtomicReference<>();
        LocalApprovalService service = new LocalApprovalService(new TestApprovalStore(persisted), properties);
        service.requestApproval(new ApprovalRequest(
                "approval-1", "run-1", "session-1",
                new ToolCallRequest("ticket_close", "execution-1", Map.of()),
                "high risk", Instant.now().minusSeconds(120)
        ));

        List<ApprovalRecord> approvals = service.recent(10);

        assertEquals(1, approvals.size());
        assertEquals(ApprovalStatus.EXPIRED, approvals.get(0).status());
        assertEquals(ApprovalStatus.EXPIRED, persisted.get().status());
    }

    private DecisionAttempt decideAfter(CountDownLatch start,
                                        LocalApprovalService service,
                                        boolean approved,
                                        String reviewer) throws InterruptedException {
        start.await();
        try {
            ApprovalDecision decision = service.decide("approval-1", approved, reviewer, "decision");
            return new DecisionAttempt(true, decision.status(), decision.reviewer());
        }
        catch (IllegalArgumentException alreadyDecided) {
            return new DecisionAttempt(false, null, "");
        }
    }

    private record DecisionAttempt(boolean success, ApprovalStatus status, String reviewer) {
    }

    private record TestApprovalStore(AtomicReference<ApprovalRecord> record) implements ApprovalStore {

        @Override
        public void save(ApprovalRecord approvalRecord) {
            record.set(approvalRecord);
        }

        @Override
        public boolean decideIfRequestedAndNotExpired(String approvalId,
                                                      ApprovalRecord nextRecord,
                                                      Instant decisionTime) {
            while (true) {
                ApprovalRecord current = record.get();
                if (current == null
                        || !current.approvalId().equals(approvalId)
                        || current.status() != ApprovalStatus.REQUESTED
                        || !current.expiresAt().isAfter(decisionTime)) {
                    return false;
                }
                if (record.compareAndSet(current, nextRecord)) {
                    return true;
                }
            }
        }

        @Override
        public boolean expireIfRequested(String approvalId,
                                         ApprovalRecord expiredRecord,
                                         Instant expirationCheckTime) {
            while (true) {
                ApprovalRecord current = record.get();
                if (current == null
                        || !current.approvalId().equals(approvalId)
                        || current.status() != ApprovalStatus.REQUESTED
                        || current.expiresAt().isAfter(expirationCheckTime)) {
                    return false;
                }
                if (record.compareAndSet(current, expiredRecord)) {
                    return true;
                }
            }
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

    private static final class SequenceClock extends Clock {

        private final List<Instant> instants;
        private final AtomicInteger index = new AtomicInteger();

        private SequenceClock(Instant... instants) {
            this.instants = List.of(instants);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            int current = Math.min(index.getAndIncrement(), instants.size() - 1);
            return instants.get(current);
        }
    }
}
