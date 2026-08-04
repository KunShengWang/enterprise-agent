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
        // 分发 → 事故调查"的幂等初始化登记：生成快照、创建 Incident、幂等落库、初始化预算
        IncidentDispatchInitialization initialized = orchestrator.initializeForDispatch(dispatchRequestId, request);
        IncidentRecord incident = initialized.incident();
        // initializeForDispatch 是幂等的——如果之前已经创建过 Incident 并触发过调查，那么重试时 newlyCreated=false，就不会再启动一次调查。只有真正新建的（第一次）才触发执行
        if (initialized.newlyCreated()) {
            try {
                // 异步线程执行调查
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
