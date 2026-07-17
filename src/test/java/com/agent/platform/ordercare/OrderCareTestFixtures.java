package com.agent.platform.ordercare;

import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;

import java.util.List;

public final class OrderCareTestFixtures {

    private OrderCareTestFixtures() {
    }

    public static OrderCareRecoveryProposal proposal(String proposalStatus,
                                                     String actionStatus,
                                                     String caseOutcome,
                                                     boolean canExecute) {
        return new OrderCareRecoveryProposal(
                "floworder-recovery-proposal-v1",
                "prop-00000000-0000-0000-0000-000000000001",
                1,
                proposalStatus,
                "act-00000000-0000-0000-0000-000000000001",
                actionStatus,
                caseOutcome,
                "floworder:request:req-1",
                "REQUEST_ID",
                "req-1",
                "REPLAY",
                "DEAD_LETTER",
                "101",
                "fingerprint-1",
                "effects-digest-1",
                "warnings-digest-1",
                "preview-digest-1",
                canExecute,
                List.of("replay original message"),
                List.of("human approval required"),
                "agent suggested replay",
                "",
                "",
                "",
                "",
                "2026-07-17T18:00:00",
                "2026-07-17T17:50:00",
                "2026-07-17T17:50:00"
        );
    }

    public static OrderCareCaseSnapshot recoveryCase(int deductStatus,
                                                     boolean inventoryInvariantOk,
                                                     int deadLetterStatus) {
        return new OrderCareCaseSnapshot(
                "floworder-recovery-case-v1",
                "floworder:request:req-1",
                "REQUEST_ID",
                "req-1",
                "req-1",
                true,
                deductStatus == 30 ? "ALREADY_CONVERGED" : "ACTION_IN_PROGRESS",
                true,
                false,
                "2026-07-17T17:51:00",
                null,
                new OrderCareCaseSnapshot.OrderFact(true, true, "order-1", 40, "TIMEOUT", ""),
                new OrderCareCaseSnapshot.DeductFact(
                        true, 1L, "deduct-1", "order-1", 1L, 1,
                        deductStatus, deductStatus == 30 ? "RELEASED" : "ORDER_CREATED",
                        "", "", "2026-07-17T17:51:00"
                ),
                new OrderCareCaseSnapshot.InventoryFact(
                        true, 1L, 100, 100, 0, 0, 0,
                        inventoryInvariantOk, 2, "2026-07-17T17:51:00"
                ),
                List.of(new OrderCareCaseSnapshot.DeadLetterFact(
                        101L, "message-1", "dlq", "order-service", "ORDER_TIMEOUT",
                        "deduct-1", deadLetterStatus,
                        deadLetterStatus == 20 ? "RESOLVED" : "REPLAYING",
                        1, "", "", "", "", "2026-07-17T17:51:00"
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
