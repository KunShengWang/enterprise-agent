package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.llm.LlmCallException;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.RouteValidationResult;
import com.agent.platform.workbench.model.RoutingAttempt;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetDefinition;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import com.agent.platform.workbench.budget.BudgetExceededException;
import com.agent.platform.workbench.budget.BudgetReservationHandle;
import com.agent.platform.workbench.budget.WorkItemBudgetGate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class RoutingCoordinator {

    private static final ScheduledExecutorService LEASE_HEARTBEAT = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "workbench-routing-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private final RoutingStore routingStore;
    private final WorkbenchStore workbenchStore;
    private final UnifiedTaskRouter router;
    private final RoutePolicyValidator validator;
    private final RouteContextResolver contextResolver;
    private final ExecutionTargetRegistry targetRegistry;
    private final WorkbenchRoutingProperties properties;
    private final RoutingFailureInjector failureInjector;
    private final RouteDecisionPostProcessor postProcessor;
    private final WorkItemBudgetGate budgets;
    private final IncidentScopeRoutePreflight incidentScopePreflight;

    @Autowired
    public RoutingCoordinator(RoutingStore routingStore,
                              WorkbenchStore workbenchStore,
                              UnifiedTaskRouter router,
                              RoutePolicyValidator validator,
                              RouteContextResolver contextResolver,
                              ExecutionTargetRegistry targetRegistry,
                              WorkbenchRoutingProperties properties,
                              RoutingFailureInjector failureInjector,
                              RouteDecisionPostProcessor postProcessor,
                              WorkItemBudgetGate budgets,
                              IncidentScopeRoutePreflight incidentScopePreflight) {
        this.routingStore = routingStore;
        this.workbenchStore = workbenchStore;
        this.router = router;
        this.validator = validator;
        this.contextResolver = contextResolver;
        this.targetRegistry = targetRegistry;
        this.properties = properties;
        this.failureInjector = failureInjector;
        this.postProcessor = postProcessor;
        this.budgets = budgets;
        this.incidentScopePreflight = incidentScopePreflight;
    }

    public RoutingCoordinator(RoutingStore routingStore,
                              WorkbenchStore workbenchStore,
                              UnifiedTaskRouter router,
                              RoutePolicyValidator validator,
                              RouteContextResolver contextResolver,
                              ExecutionTargetRegistry targetRegistry,
                              WorkbenchRoutingProperties properties,
                              RoutingFailureInjector failureInjector) {
        this(routingStore, workbenchStore, router, validator, contextResolver, targetRegistry,
                properties, failureInjector, (principal, workItem, decision) -> { }, WorkItemBudgetGate.NOOP,
                IncidentScopeRoutePreflight.NOOP);
    }

    public RoutingCoordinator(RoutingStore routingStore,
                              WorkbenchStore workbenchStore,
                              UnifiedTaskRouter router,
                              RoutePolicyValidator validator,
                              RouteContextResolver contextResolver,
                              ExecutionTargetRegistry targetRegistry,
                              WorkbenchRoutingProperties properties,
                              RoutingFailureInjector failureInjector,
                              RouteDecisionPostProcessor postProcessor) {
        this(routingStore, workbenchStore, router, validator, contextResolver, targetRegistry,
                properties, failureInjector, postProcessor, WorkItemBudgetGate.NOOP,
                IncidentScopeRoutePreflight.NOOP);
    }

    public RoutingCoordinator(RoutingStore routingStore,
                              WorkbenchStore workbenchStore,
                              UnifiedTaskRouter router,
                              RoutePolicyValidator validator,
                              RouteContextResolver contextResolver,
                              ExecutionTargetRegistry targetRegistry,
                              WorkbenchRoutingProperties properties,
                              RoutingFailureInjector failureInjector,
                              RouteDecisionPostProcessor postProcessor,
                              WorkItemBudgetGate budgets) {
        this(routingStore, workbenchStore, router, validator, contextResolver, targetRegistry,
                properties, failureInjector, postProcessor, budgets, IncidentScopeRoutePreflight.NOOP);
    }

    public Optional<RoutingDecisionRecord> route(AuthenticatedPrincipal principal,
                                                  String workItemId,
                                                  String routingRequestId) {
        if (!properties.isEnabled()) return Optional.empty();
        AgentWorkItem workItem = workbenchStore.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new IllegalArgumentException("work item not found"));
        String leaseOwner = "routing-" + UUID.randomUUID();
        Optional<RoutingAttempt> claimed = routingStore.claimRouting(
                principal,
                workItem.workItemId(),
                routingRequestId,
                Instant.now().minusMillis(properties.getStaleAfterMillis()),
                properties.getMaxAttempts(),
                properties.getUnknownResultTokenReserve(),
                properties.getCatalogVersion(), leaseOwner,
                Instant.now().plusMillis(properties.getLeaseMillis()));
        if (claimed.isEmpty()) return routingStore.findEffectiveRouting(principal, workItemId);

        RoutingAttempt attempt = claimed.get();
        ScheduledFuture<?> heartbeat = startHeartbeat(attempt);
        RoutingDecisionRecord completed;
        BudgetReservationHandle budget;
        try {
            budget = budgets.reserveRouter(principal, workItem, "router:" + attempt.decisionId());
        }
        catch (BudgetExceededException exhausted) {
            routingStore.failRouting(principal, attempt, exhausted.code(), safeMessage(exhausted),
                    RouterFailureObservation.empty(), properties.getRetryBackoffMillis(), attempt.attemptNo());
            if (heartbeat != null) heartbeat.cancel(false);
            return Optional.empty();
        }
        try {
            AgentWorkItem claimedWork = workbenchStore.findWorkItem(principal, workItemId).orElseThrow();
            ResolvedRouteContext context = contextResolver.resolve(principal, claimedWork);
            List<ExecutionTargetDefinition> targets = targetRegistry.enabledTargets(principal);
            RouterModelResult modelResult = router.route(new RoutingModelRequest(
                    claimedWork, claimedWork.normalizedGoal(), targets, context.conversationSummary()));
            budgets.settleRouter(budget, modelResult);
            failureInjector.afterModelResult(attempt, modelResult);
            RouteValidationResult validation = incidentScopePreflight
                    .resolve(principal, claimedWork, modelResult.decision(), context)
                    .orElseGet(() -> validator.validate(
                            modelResult.decision(),
                            new RouteValidationContext(
                                    principal, claimedWork, claimedWork.originalGoal(),
                                    context.trustedIdentifiers(), context.serverResolvedIdentifiers())));
            completed = routingStore.completeRouting(principal, attempt, modelResult, validation);
        }
        catch (RoutingResultPersistenceUnknownException exception) {
            throw exception;
        }
        catch (RouterInvocationException exception) {
            budgets.settleRouterFailure(budget, exception.observation());
            routingStore.failRouting(
                    principal, attempt, exception.failureCode(), safeMessage(exception), exception.observation(),
                    properties.getRetryBackoffMillis(), properties.getMaxAttempts());
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            budgets.release(budget);
            routingStore.failRouting(
                    principal, attempt, failureCode(exception), safeMessage(exception),
                    RouterFailureObservation.empty(),
                    properties.getRetryBackoffMillis(), properties.getMaxAttempts());
            return Optional.empty();
        }
        finally {
            if (heartbeat != null) heartbeat.cancel(false);
        }
        // Routing is already authoritative. A downstream preview/dispatch-preparation
        // failure must never rewrite its EFFECTIVE attempt as a routing failure.
        AgentWorkItem routed = workbenchStore.findWorkItem(principal, workItemId).orElseThrow();
        postProcessor.afterEffectiveDecision(principal, routed, completed);
        return Optional.of(completed);
    }

    private ScheduledFuture<?> startHeartbeat(RoutingAttempt attempt) {
        if (attempt.fencingToken() <= 0 || attempt.leaseOwner().isBlank()) return null;
        long period = Math.max(250, properties.getLeaseMillis() / 3);
        return LEASE_HEARTBEAT.scheduleAtFixedRate(() -> {
            try {
                routingStore.renewRoutingLease(attempt, Instant.now().plusMillis(properties.getLeaseMillis()));
            } catch (RuntimeException ignored) {
                // A takeover changes the fencing token; terminal persistence remains the authority.
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof LlmCallException llm) {
            String type = llm.errorType() == null ? "" : llm.errorType().toUpperCase();
            if (type.contains("TIMEOUT")) return "MODEL_TIMEOUT";
            if (type.contains("FALLBACK")) return "MODEL_FALLBACK";
            return "PROVIDER_ERROR";
        }
        String type = exception.getClass().getName();
        if (type.contains("Json") || type.contains("Jackson") || type.contains("IllegalArgumentException")) {
            return "STRUCTURED_OUTPUT_INVALID";
        }
        return "PROVIDER_ERROR";
    }

    private String safeMessage(RuntimeException exception) {
        if (exception instanceof LlmCallException llm) return llm.safeMessage();
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
