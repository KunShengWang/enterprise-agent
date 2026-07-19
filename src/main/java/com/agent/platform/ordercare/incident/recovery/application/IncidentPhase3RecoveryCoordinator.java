package com.agent.platform.ordercare.incident.recovery.application;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanItemStatus;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class IncidentPhase3RecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IncidentPhase3RecoveryCoordinator.class);

    private final IncidentCommandProperties properties;
    private final IncidentRecoveryPlanStore planStore;
    private final IncidentRecoveryExecutionService executionService;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicLong scans = new AtomicLong();
    private final AtomicLong takeovers = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public IncidentPhase3RecoveryCoordinator(IncidentCommandProperties properties,
                                             IncidentRecoveryPlanStore planStore,
                                             IncidentRecoveryExecutionService executionService) {
        this.properties = properties;
        this.planStore = planStore;
        this.executionService = executionService;
    }

    @Scheduled(
            initialDelayString = "${enterprise-agent.ordercare.incident-command.stale-scan-interval-millis:5000}",
            fixedDelayString = "${enterprise-agent.ordercare.incident-command.stale-scan-interval-millis:5000}")
    public void scan() {
        if (!properties.isEnabled() || !properties.isRecoveryPlannerEnabled()
                || !properties.isPhase3Enabled() || properties.isExecutionKillSwitch()
                || !scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            scans.incrementAndGet();
            Instant now = Instant.now();
            for (IncidentRecoveryPlanRecord plan : planStore.listStaleExecuting(
                    now, properties.getStaleScanBatchSize())) {
                plan.items().stream()
                        .filter(item -> item.status() == RecoveryPlanItemStatus.EXECUTING)
                        .filter(item -> item.leaseUntil() == null || !item.leaseUntil().isAfter(now))
                        .forEach(item -> {
                            try {
                                IncidentRecoveryPlanRecord recovered = executionService.recoverStaleExecution(
                                        plan.planId(), item.itemId());
                                if (recovered.items().stream().anyMatch(current -> current.itemId().equals(item.itemId())
                                        && current.fencingToken() > item.fencingToken())) {
                                    takeovers.incrementAndGet();
                                }
                            } catch (RuntimeException exception) {
                                failures.incrementAndGet();
                                log.warn("stale recovery item takeover failed: planId={}, itemId={}",
                                        plan.planId(), item.itemId(), exception);
                            }
                        });
            }
        } finally {
            scanning.set(false);
        }
    }

    public Phase3RecoveryStatistics statistics() {
        return new Phase3RecoveryStatistics(scans.get(), takeovers.get(), failures.get(), scanning.get(),
                properties.isExecutionKillSwitch());
    }

    public record Phase3RecoveryStatistics(long scans,
                                           long recoveryTakeovers,
                                           long failures,
                                           boolean scanning,
                                           boolean killSwitchActive) { }
}
