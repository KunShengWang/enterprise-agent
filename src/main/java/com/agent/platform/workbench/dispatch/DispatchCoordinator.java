package com.agent.platform.workbench.dispatch;

import com.agent.platform.config.WorkbenchDispatchProperties;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.persistence.DispatchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.budget.BudgetExceededException;
import com.agent.platform.workbench.budget.BudgetReservationHandle;
import com.agent.platform.workbench.budget.WorkItemBudgetGate;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class DispatchCoordinator {
    private static final ScheduledExecutorService LEASE_HEARTBEAT = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "workbench-dispatch-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final DispatchStore store;
    private final ExecutionAdapterRegistry adapters;
    private final WorkbenchDispatchProperties properties;
    private final DispatchFailureInjector failureInjector;
    private final WorkItemBudgetGate budgets;

    @Autowired
    public DispatchCoordinator(DispatchStore store,
                               ExecutionAdapterRegistry adapters,
                               WorkbenchDispatchProperties properties,
                               DispatchFailureInjector failureInjector,
                               WorkItemBudgetGate budgets) {
        this.store = store;
        this.adapters = adapters;
        this.properties = properties;
        this.failureInjector = failureInjector;
        this.budgets = budgets;
    }

    public DispatchCoordinator(DispatchStore store,
                               ExecutionAdapterRegistry adapters,
                               WorkbenchDispatchProperties properties,
                               DispatchFailureInjector failureInjector) {
        this(store, adapters, properties, failureInjector, WorkItemBudgetGate.NOOP);
    }

    public Optional<WorkLink> dispatch(AuthenticatedPrincipal principal, String workItemId) {
        if (!properties.isEnabled()) return Optional.empty();
        String leaseOwner = "dispatch-" + UUID.randomUUID();
        // 抢占"分发（Dispatch）执行权"——为一个已经路由好、就绪待派发的 WorkItem，拿到"把它真正派发给底层执行器启动 Agent"的独占权
        Optional<DispatchClaim> claimed = store.claimDispatch(
                principal, workItemId, Instant.now().minusMillis(properties.getStaleAfterMillis()),
                properties.getMaxAttempts(), leaseOwner,
                Instant.now().plusMillis(properties.getLeaseMillis()));
        if (claimed.isEmpty()) return Optional.empty();
        DispatchClaim claim = claimed.get();
        // 租约续约心跳
        ScheduledFuture<?> heartbeat = startHeartbeat(claim);
        // 寻找 agent 执行适配器
        ExecutionAdapter adapter = adapters.require(claim.request().targetId());
        BudgetReservationHandle budget;
        try {
            // 预算预留
            budget = budgets.reserveTarget(principal,
                    claim.request().workItemId(), ExecutionTargetId.valueOf(claim.request().targetId()),
                    "dispatch:" + claim.request().dispatchRequestId());
        }
        catch (BudgetExceededException exhausted) {
            store.failDispatch(principal, claim, exhausted.code(), safeMessage(exhausted),
                    properties.getRetryBackoffMillis(), claim.attempt().attemptNo());
            if (heartbeat != null) heartbeat.cancel(false);
            return Optional.empty();
        }
        try {
            // 区分"对账接管"还是"全新派发"，防止崩溃恢复时重复派发导致副作用（比如同一个任务被启动了两次 Agent）
            DispatchResult result;
            // 对账接管（reconciliation=true，之前崩溃残留）：先 adapter.reconcile(...) 查下游是否已经派发过——只有确认没派发过（返回空 Optional），才重新 dispatch(
            if (claim.attempt().reconciliation()) {
                result = adapter.reconcile(claim.request()).orElseGet(() -> adapter.dispatch(claim.request()));
            }
            // 全新派发（reconciliation=false）：直接调 adapter.dispatch(...) 启动 Agent Run
            else {
                result = adapter.dispatch(claim.request());
            }
            // 结算预算 + 完成分发
            budgets.settleTarget(budget, result);
            failureInjector.afterAdapterResult(claim, result);
            return Optional.of(store.completeDispatch(principal, claim, result));
        }
        catch (DispatchResultPersistenceUnknownException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            store.failDispatch(principal, claim, "ADAPTER_FAILURE", safeMessage(exception),
                    properties.getRetryBackoffMillis(), properties.getMaxAttempts());
            return Optional.empty();
        }
        finally {
            if (heartbeat != null) heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> startHeartbeat(DispatchClaim claim) {
        if (claim.fencingToken() <= 0 || claim.leaseOwner().isBlank()) return null;
        long period = Math.max(250, properties.getLeaseMillis() / 3);
        return LEASE_HEARTBEAT.scheduleAtFixedRate(() -> {
            try {
                store.renewDispatchLease(claim, Instant.now().plusMillis(properties.getLeaseMillis()));
            } catch (RuntimeException ignored) {
                // A newer owner is authoritative and fenced terminal writes reject this worker.
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
