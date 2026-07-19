package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.workbench.model.RoutingRecoveryCandidate;
import com.agent.platform.workbench.persistence.RoutingStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class RoutingRecoveryScanner {

    private final RoutingStore store;
    private final RoutingCoordinator coordinator;
    private final WorkbenchRoutingProperties properties;

    public RoutingRecoveryScanner(RoutingStore store,
                                  RoutingCoordinator coordinator,
                                  WorkbenchRoutingProperties properties) {
        this.store = store;
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${enterprise-agent.workbench.routing.scan-delay-millis:5000}")
    public void reconcileStaleRoutingWorkItems() {
        if (!properties.isEnabled()) return;
        Instant staleBefore = Instant.now().minusMillis(properties.getStaleAfterMillis());
        for (RoutingRecoveryCandidate candidate
                : store.findStaleRouting(staleBefore, properties.getScanBatchSize())) {
            try {
                coordinator.route(candidate.principal(), candidate.workItem().workItemId(),
                        candidate.workItem().routingRequestId());
            }
            catch (RuntimeException ignored) {
                // The persisted STARTED attempt remains recoverable; the next bounded scan retries it.
            }
        }
    }
}
