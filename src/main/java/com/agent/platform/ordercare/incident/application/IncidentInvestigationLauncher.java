package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.IncidentInvestigationRequest;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentStartResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class IncidentInvestigationLauncher {

    private static final Logger log = LoggerFactory.getLogger(IncidentInvestigationLauncher.class);

    private final IncidentInvestigationOrchestrator orchestrator;
    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),
            new ThreadPoolExecutor.AbortPolicy());

    public IncidentInvestigationLauncher(IncidentInvestigationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public IncidentStartResponse start(IncidentInvestigationRequest request) {
        IncidentRecord incident = orchestrator.initialize(request);
        try {
            executor.execute(() -> execute(incident.incidentId(), request));
        }
        catch (RuntimeException rejected) {
            orchestrator.rejectBeforeExecution(
                    incident.incidentId(), "incident executor queue is full");
            throw new IllegalStateException("incident executor queue is full", rejected);
        }
        return new IncidentStartResponse(
                incident.incidentId(), incident.status(), incident.createdAt());
    }

    public IncidentStartResponse startForDispatch(String dispatchRequestId,
                                                  IncidentInvestigationRequest request) {
        IncidentDispatchInitialization initialized = orchestrator.initializeForDispatch(dispatchRequestId, request);
        IncidentRecord incident = initialized.incident();
        if (initialized.newlyCreated()) {
            try {
                executor.execute(() -> execute(incident.incidentId(), request));
            }
            catch (RuntimeException rejected) {
                orchestrator.rejectBeforeExecution(incident.incidentId(), "incident executor queue is full");
                throw new IllegalStateException("incident executor queue is full", rejected);
            }
        }
        return new IncidentStartResponse(incident.incidentId(), incident.status(), incident.createdAt());
    }

    public java.util.Optional<IncidentStartResponse> findByDispatchRequestId(String dispatchRequestId) {
        return orchestrator.findByDispatchRequestId(dispatchRequestId)
                .map(incident -> new IncidentStartResponse(
                        incident.incidentId(), incident.status(), incident.createdAt()));
    }

    private void execute(String incidentId, IncidentInvestigationRequest request) {
        try {
            orchestrator.investigate(incidentId, request);
        }
        catch (RuntimeException exception) {
            log.warn("incident investigation failed: incidentId={}", incidentId, exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
