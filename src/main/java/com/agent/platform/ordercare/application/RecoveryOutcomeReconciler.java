package com.agent.platform.ordercare.application;

import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.model.OrderCareActionReconcileCommand;
import com.agent.platform.ordercare.model.OrderCareConvergenceResult;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryAction;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.ordercare.model.OrderCareRecoveryReconciliationResult;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 写请求结果未知后的确定性协调器。只使用原 proposalId/actionRequestId/toolExecutionId，
 * 不生成第二个副作用标识，也不把轮询和停止条件交给模型。
 */
@Component
public class RecoveryOutcomeReconciler {

    private static final Set<String> MANUAL_ACTION_STATES = Set.of("FAILED", "MANUAL_REVIEW");

    private final FlowOrderClient flowOrderClient;
    private final RecoveryConvergenceChecker convergenceChecker;
    private final OrderCareProperties properties;

    public RecoveryOutcomeReconciler(FlowOrderClient flowOrderClient,
                                     RecoveryConvergenceChecker convergenceChecker,
                                     OrderCareProperties properties) {
        this.flowOrderClient = flowOrderClient;
        this.convergenceChecker = convergenceChecker;
        this.properties = properties;
    }

    public OrderCareRecoveryReconciliationResult reconcile(
            OrderCareRecoveryProposal immutableProposal,
            OrderCareProposalExecuteCommand originalCommand,
            String executionOwner,
            String traceId,
            boolean responseLost
    ) {
        String actionRequestId = immutableProposal.actionRequestId();
        boolean reissued = false;
        OrderCareRecoveryAction lastAction = null;
        int maxAttempts = properties.getReconciliationMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                lastAction = flowOrderClient.getAction(actionRequestId, traceId);
                if (isResolved(lastAction)) {
                    return convergedResult(lastAction, attempt, responseLost, reissued, traceId);
                }
                if (MANUAL_ACTION_STATES.contains(lastAction.actionStatus())) {
                    return result("MANUAL_REVIEW", attempt, responseLost, reissued, lastAction, null);
                }

                if ("NOT_STARTED".equals(lastAction.actionStatus()) && !reissued) {
                    reissued = true;
                    try {
                        flowOrderClient.executeProposal(originalCommand, traceId);
                    } catch (FlowOrderApiException exception) {
                        if (!exception.outcomeUnknown()) {
                            return result("MANUAL_REVIEW", attempt, responseLost, true, lastAction, null);
                        }
                        // 请求仍可能已经到达 FlowOrder；下一轮只查询原 actionRequestId。
                    }
                } else {
                    try {
                        lastAction = flowOrderClient.reconcileAction(
                                actionRequestId,
                                new OrderCareActionReconcileCommand(executionOwner),
                                traceId
                        );
                    } catch (FlowOrderApiException exception) {
                        if (!exception.outcomeUnknown() && !exception.retryable()) {
                            return result("MANUAL_REVIEW", attempt, responseLost, reissued, lastAction, null);
                        }
                        // reconcile 自身响应丢失时，下一轮仍然只查询同一 Action。
                    }
                    if (isResolved(lastAction)) {
                        return convergedResult(lastAction, attempt, responseLost, reissued, traceId);
                    }
                    if (lastAction != null && MANUAL_ACTION_STATES.contains(lastAction.actionStatus())) {
                        return result("MANUAL_REVIEW", attempt, responseLost, reissued, lastAction, null);
                    }
                }
            } catch (FlowOrderApiException exception) {
                if (!exception.retryable() && !exception.outcomeUnknown()) {
                    return result("MANUAL_REVIEW", attempt, responseLost, reissued, lastAction, null);
                }
            }
            if (attempt < maxAttempts) {
                pause();
            }
        }
        return result("UNKNOWN", maxAttempts, responseLost, reissued, lastAction, null);
    }

    private OrderCareRecoveryReconciliationResult convergedResult(
            OrderCareRecoveryAction action,
            int attempts,
            boolean responseLost,
            boolean reissued,
            String traceId
    ) {
        OrderCareConvergenceResult convergence = convergenceChecker.await(action.proposalId(), traceId);
        String status = "RESOLVED".equals(convergence.status())
                ? "RESOLVED"
                : ("MANUAL_REVIEW".equals(convergence.status()) ? "MANUAL_REVIEW" : "NOT_CONVERGED");
        return result(status, attempts, responseLost, reissued, action, convergence);
    }

    private boolean isResolved(OrderCareRecoveryAction action) {
        return action != null
                && "SUBMITTED".equals(action.actionStatus())
                && ("RESOLVED".equals(action.caseOutcome())
                || "RESOLVED".equals(action.reconciliationStatus()));
    }

    private OrderCareRecoveryReconciliationResult result(
            String status,
            int attempts,
            boolean responseLost,
            boolean reissued,
            OrderCareRecoveryAction action,
            OrderCareConvergenceResult convergence
    ) {
        return new OrderCareRecoveryReconciliationResult(
                status, attempts, responseLost, reissued, action, convergence
        );
    }

    private void pause() {
        long interval = properties.getReconciliationIntervalMillis();
        if (interval <= 0) return;
        try {
            Thread.sleep(interval);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("recovery reconciliation interrupted", exception);
        }
    }
}
