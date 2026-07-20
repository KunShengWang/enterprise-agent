package com.agent.platform.workbench.dispatch;

import com.agent.platform.config.WorkbenchDispatchProperties;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.persistence.DispatchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class DispatchCoordinator {
    private final DispatchStore store;
    private final ExecutionAdapterRegistry adapters;
    private final WorkbenchDispatchProperties properties;
    private final DispatchFailureInjector failureInjector;

    public DispatchCoordinator(DispatchStore store,
                               ExecutionAdapterRegistry adapters,
                               WorkbenchDispatchProperties properties,
                               DispatchFailureInjector failureInjector) {
        this.store = store;
        this.adapters = adapters;
        this.properties = properties;
        this.failureInjector = failureInjector;
    }

    public Optional<WorkLink> dispatch(AuthenticatedPrincipal principal, String workItemId) {
        if (!properties.isEnabled()) return Optional.empty();
        Optional<DispatchClaim> claimed = store.claimDispatch(
                principal, workItemId, Instant.now().minusMillis(properties.getStaleAfterMillis()),
                properties.getMaxAttempts());
        if (claimed.isEmpty()) return Optional.empty();
        DispatchClaim claim = claimed.get();
        ExecutionAdapter adapter = adapters.require(claim.request().targetId());
        try {
            DispatchResult result;
            if (claim.attempt().reconciliation()) {
                result = adapter.reconcile(claim.request()).orElseGet(() -> adapter.dispatch(claim.request()));
            }
            else {
                result = adapter.dispatch(claim.request());
            }
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
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
