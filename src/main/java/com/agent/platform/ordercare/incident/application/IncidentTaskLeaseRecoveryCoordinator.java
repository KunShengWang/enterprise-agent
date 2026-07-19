package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.config.IncidentWorkerIdentity;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.TaskLeaseClaim;
import com.agent.platform.ordercare.incident.persistence.AgentTaskStore;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class IncidentTaskLeaseRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IncidentTaskLeaseRecoveryCoordinator.class);

    private final IncidentCommandProperties properties;
    private final IncidentWorkerIdentity identity;
    private final AgentTaskStore taskStore;
    private final IncidentStore incidentStore;
    private final IncidentTaskScheduler scheduler;
    private final IncidentInvestigationOrchestrator orchestrator;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicLong taskTakeovers = new AtomicLong();
    private final AtomicLong incidentResumes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public IncidentTaskLeaseRecoveryCoordinator(IncidentCommandProperties properties,
                                                IncidentWorkerIdentity identity,
                                                AgentTaskStore taskStore,
                                                IncidentStore incidentStore,
                                                IncidentTaskScheduler scheduler,
                                                IncidentInvestigationOrchestrator orchestrator) {
        this.properties = properties;
        this.identity = identity;
        this.taskStore = taskStore;
        this.incidentStore = incidentStore;
        this.scheduler = scheduler;
        this.orchestrator = orchestrator;
    }

    @Scheduled(
            initialDelayString = "${enterprise-agent.ordercare.incident-command.stale-scan-interval-millis:5000}",
            fixedDelayString = "${enterprise-agent.ordercare.incident-command.stale-scan-interval-millis:5000}")
    public void scan() {
        if (!properties.isEnabled() || !properties.isPhase3Enabled()
                || properties.isExecutionKillSwitch() || !scanning.compareAndSet(false, true)) return;
        Set<String> touchedIncidents = new LinkedHashSet<>();
        try {
            Instant now = Instant.now();
            for (AgentTaskRecord stale : taskStore.listStaleTasks(now, properties.getStaleScanBatchSize())) {
                try {
                    TaskLeaseClaim claim = taskStore.claimTask(
                            stale.taskId(), stale.version(), identity.value(),
                            Instant.now().plusSeconds(properties.getTaskLeaseSeconds()), true);
                    if (!claim.claimed()) {
                        if (claim.task().status().terminal()) touchedIncidents.add(claim.task().incidentId());
                        continue;
                    }
                    taskTakeovers.incrementAndGet();
                    touchedIncidents.add(claim.task().incidentId());
                    var incident = incidentStore.find(claim.task().incidentId()).orElse(null);
                    if (incident != null) scheduler.execute(java.util.List.of(claim.task()), incident.snapshot());
                } catch (RuntimeException exception) {
                    failures.incrementAndGet();
                    log.warn("stale incident task takeover failed: taskId={}", stale.taskId(), exception);
                }
            }
            for (String incidentId : touchedIncidents) {
                try {
                    if (orchestrator.resumeAfterRecoveredTasks(incidentId).isPresent()) {
                        incidentResumes.incrementAndGet();
                    }
                } catch (RuntimeException exception) {
                    failures.incrementAndGet();
                    log.warn("incident resume after task takeover failed: incidentId={}", incidentId, exception);
                }
            }
        } finally {
            scanning.set(false);
        }
    }

    public Statistics statistics() {
        return new Statistics(taskTakeovers.get(), incidentResumes.get(), failures.get(), scanning.get());
    }

    public record Statistics(long taskTakeovers, long incidentResumes, long failures, boolean scanning) { }
}
