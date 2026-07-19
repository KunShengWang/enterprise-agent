package com.agent.platform.ordercare.incident.recovery.application;

import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class IncidentRecoveryPlanLauncher {

    private static final Logger log = LoggerFactory.getLogger(IncidentRecoveryPlanLauncher.class);

    private final IncidentRecoveryPlanner planner;
    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),
            new ThreadPoolExecutor.AbortPolicy());

    public IncidentRecoveryPlanLauncher(IncidentRecoveryPlanner planner) {
        this.planner = planner;
    }

    public RecoveryPlanStartResponse start(String incidentId, RecoveryPlanStartRequest request) {
        RecoveryPlanStartResponse started = planner.initialize(incidentId, request);
        if (!started.newlyCreated()) {
            return started;
        }
        try {
            executor.execute(() -> execute(started.planId(), request == null ? "" : request.objective()));
        } catch (RuntimeException rejected) {
            planner.failBeforePlanning(started.planId(), rejected);
            throw new IllegalStateException("incident recovery planner queue is full", rejected);
        }
        return started;
    }

    private void execute(String planId, String objective) {
        try {
            planner.plan(planId, objective);
        } catch (RuntimeException exception) {
            log.warn("incident recovery planning failed: planId={}", planId, exception);
            try {
                planner.failBeforePlanning(planId, exception);
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
