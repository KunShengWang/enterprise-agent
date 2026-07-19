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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class RoutingCoordinator {

    private final RoutingStore routingStore;
    private final WorkbenchStore workbenchStore;
    private final UnifiedTaskRouter router;
    private final RoutePolicyValidator validator;
    private final RouteContextResolver contextResolver;
    private final ExecutionTargetRegistry targetRegistry;
    private final WorkbenchRoutingProperties properties;
    private final RoutingFailureInjector failureInjector;

    public RoutingCoordinator(RoutingStore routingStore,
                              WorkbenchStore workbenchStore,
                              UnifiedTaskRouter router,
                              RoutePolicyValidator validator,
                              RouteContextResolver contextResolver,
                              ExecutionTargetRegistry targetRegistry,
                              WorkbenchRoutingProperties properties,
                              RoutingFailureInjector failureInjector) {
        this.routingStore = routingStore;
        this.workbenchStore = workbenchStore;
        this.router = router;
        this.validator = validator;
        this.contextResolver = contextResolver;
        this.targetRegistry = targetRegistry;
        this.properties = properties;
        this.failureInjector = failureInjector;
    }

    public Optional<RoutingDecisionRecord> route(AuthenticatedPrincipal principal,
                                                  String workItemId,
                                                  String routingRequestId) {
        if (!properties.isEnabled()) return Optional.empty();
        AgentWorkItem workItem = workbenchStore.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new IllegalArgumentException("work item not found"));
        Optional<RoutingAttempt> claimed = routingStore.claimRouting(
                principal,
                workItem.workItemId(),
                routingRequestId,
                Instant.now().minusMillis(properties.getStaleAfterMillis()),
                properties.getMaxAttempts(),
                properties.getUnknownResultTokenReserve(),
                properties.getCatalogVersion());
        if (claimed.isEmpty()) return routingStore.findEffectiveRouting(principal, workItemId);

        RoutingAttempt attempt = claimed.get();
        try {
            AgentWorkItem claimedWork = workbenchStore.findWorkItem(principal, workItemId).orElseThrow();
            ResolvedRouteContext context = contextResolver.resolve(principal, claimedWork);
            List<ExecutionTargetDefinition> targets = targetRegistry.enabledTargets(principal);
            RouterModelResult modelResult = router.route(new RoutingModelRequest(
                    claimedWork, claimedWork.normalizedGoal(), targets, context.conversationSummary()));
            failureInjector.afterModelResult(attempt, modelResult);
            RouteValidationResult validation = validator.validate(
                    modelResult.decision(),
                    new RouteValidationContext(
                            principal, claimedWork, claimedWork.originalGoal(),
                            context.trustedIdentifiers(), context.serverResolvedIdentifiers()));
            return Optional.of(routingStore.completeRouting(principal, attempt, modelResult, validation));
        }
        catch (RoutingResultPersistenceUnknownException exception) {
            throw exception;
        }
        catch (RouterInvocationException exception) {
            routingStore.failRouting(
                    principal, attempt, exception.failureCode(), safeMessage(exception), exception.observation(),
                    properties.getRetryBackoffMillis(), properties.getMaxAttempts());
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            routingStore.failRouting(
                    principal, attempt, failureCode(exception), safeMessage(exception),
                    RouterFailureObservation.empty(),
                    properties.getRetryBackoffMillis(), properties.getMaxAttempts());
            return Optional.empty();
        }
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
