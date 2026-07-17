package com.agent.platform.ordercare.application;

import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.agent.platform.ordercare.model.OrderCareConvergenceResult;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 固定次数、固定间隔地验证业务收敛。模型不参与轮询次数、停止条件或成功判定。
 */
@Component
public class RecoveryConvergenceChecker {

    private static final int DEDUCT_RELEASED = 30;
    private static final int DEAD_PENDING = 0;
    private static final int DEAD_REPLAYING = 10;

    private final FlowOrderClient flowOrderClient;
    private final OrderCareProperties properties;

    public RecoveryConvergenceChecker(FlowOrderClient flowOrderClient,
                                      OrderCareProperties properties) {
        this.flowOrderClient = flowOrderClient;
        this.properties = properties;
    }

    public OrderCareConvergenceResult await(String proposalId, String traceId) {
        OrderCareRecoveryProposal lastProposal = null;
        OrderCareCaseSnapshot lastCase = null;
        int maxAttempts = properties.getConvergenceMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            lastProposal = flowOrderClient.getProposal(proposalId, traceId);
            lastCase = flowOrderClient.inspectCase(
                    lastProposal.identifierType(),
                    lastProposal.identifierValue(),
                    traceId
            );
            Evidence evidence = evidence(lastCase);
            if ("SUBMITTED".equals(lastProposal.actionStatus())
                    && "RESOLVED".equals(lastProposal.caseOutcome())
                    && evidence.deductReleased()
                    && evidence.inventoryInvariantOk()
                    && evidence.relatedDeadLettersTerminal()) {
                return result(lastProposal, "RESOLVED", attempt, evidence);
            }
            if ("FAILED".equals(lastProposal.actionStatus())
                    || "MANUAL_REVIEW".equals(lastProposal.caseOutcome())
                    || "INVALIDATED".equals(lastProposal.proposalStatus())
                    || "EXPIRED".equals(lastProposal.proposalStatus())) {
                return result(lastProposal, "MANUAL_REVIEW", attempt, evidence);
            }
            if (attempt < maxAttempts) {
                pause();
            }
        }
        Evidence evidence = evidence(lastCase);
        return result(lastProposal, "NOT_CONVERGED", maxAttempts, evidence);
    }

    private Evidence evidence(OrderCareCaseSnapshot snapshot) {
        if (snapshot == null) {
            return new Evidence(false, false, false);
        }
        boolean deductReleased = snapshot.deduct() != null
                && Objects.equals(snapshot.deduct().status(), DEDUCT_RELEASED);
        boolean inventoryInvariantOk = snapshot.inventory() != null
                && Boolean.TRUE.equals(snapshot.inventory().invariantOk());
        boolean deadLettersTerminal = !snapshot.deadLetters().isEmpty()
                && snapshot.deadLetters().stream().noneMatch(item ->
                Objects.equals(item.status(), DEAD_PENDING)
                        || Objects.equals(item.status(), DEAD_REPLAYING));
        return new Evidence(deductReleased, inventoryInvariantOk, deadLettersTerminal);
    }

    private OrderCareConvergenceResult result(OrderCareRecoveryProposal proposal,
                                              String status,
                                              int attempts,
                                              Evidence evidence) {
        return new OrderCareConvergenceResult(
                proposal == null ? "" : proposal.proposalId(),
                status,
                attempts,
                proposal == null ? "UNKNOWN" : proposal.proposalStatus(),
                proposal == null ? "UNKNOWN" : proposal.actionStatus(),
                proposal == null ? "MANUAL_REVIEW" : proposal.caseOutcome(),
                evidence.deductReleased(),
                evidence.inventoryInvariantOk(),
                evidence.relatedDeadLettersTerminal()
        );
    }

    private void pause() {
        long interval = properties.getConvergenceIntervalMillis();
        if (interval <= 0) {
            return;
        }
        try {
            Thread.sleep(interval);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("recovery convergence check interrupted", exception);
        }
    }

    private record Evidence(
            boolean deductReleased,
            boolean inventoryInvariantOk,
            boolean relatedDeadLettersTerminal
    ) {
    }
}
