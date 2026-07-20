package com.agent.platform.workbench.dispatch;

import com.agent.platform.config.WorkbenchDispatchProperties;
import com.agent.platform.workbench.model.DispatchRecoveryCandidate;
import com.agent.platform.workbench.persistence.DispatchStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DispatchReconciler {
    private final DispatchStore store;
    private final DispatchCoordinator coordinator;
    private final WorkbenchDispatchProperties properties;

    public DispatchReconciler(DispatchStore store,
                              DispatchCoordinator coordinator,
                              WorkbenchDispatchProperties properties) {
        this.store = store;
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${enterprise-agent.workbench.dispatch.scan-delay-millis:5000}")
    public void reconcileStaleDispatches() {
        if (!properties.isEnabled()) return;
        Instant staleBefore = Instant.now().minusMillis(properties.getStaleAfterMillis());
        for (DispatchRecoveryCandidate candidate : store.findStaleDispatch(staleBefore, properties.getScanBatchSize())) {
            try { coordinator.dispatch(candidate.principal(), candidate.workItem().workItemId()); }
            catch (RuntimeException ignored) { }
        }
    }
}
