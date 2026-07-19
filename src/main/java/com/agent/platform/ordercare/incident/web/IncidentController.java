package com.agent.platform.ordercare.incident.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.common.ErrorCode;
import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentEventStreamItem;
import com.agent.platform.ordercare.incident.model.IncidentInvestigationRequest;
import com.agent.platform.ordercare.incident.model.IncidentStartResponse;
import com.agent.platform.ordercare.incident.model.IncidentTrace;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.application.IncidentInvestigationLauncher;
import com.agent.platform.ordercare.incident.application.IncidentTraceProjector;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.ordercare.incident.recovery.application.IncidentRecoveryExecutionService;
import com.agent.platform.ordercare.incident.recovery.application.IncidentRecoveryPlanLauncher;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanDecisionRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartResponse;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentStore incidentStore;
    private final TaskEventStore taskEventStore;
    private final IncidentInvestigationLauncher launcher;
    private final IncidentTraceProjector traceProjector;
    private final IncidentCommandProperties properties;
    private final IncidentRecoveryPlanStore recoveryPlanStore;
    private final IncidentRecoveryPlanLauncher recoveryPlanLauncher;
    private final IncidentRecoveryExecutionService recoveryExecutionService;

    public IncidentController(IncidentStore incidentStore,
                              TaskEventStore taskEventStore,
                              IncidentInvestigationLauncher launcher,
                              IncidentTraceProjector traceProjector,
                              IncidentCommandProperties properties,
                              IncidentRecoveryPlanStore recoveryPlanStore,
                              IncidentRecoveryPlanLauncher recoveryPlanLauncher,
                              IncidentRecoveryExecutionService recoveryExecutionService) {
        this.incidentStore = incidentStore;
        this.taskEventStore = taskEventStore;
        this.launcher = launcher;
        this.traceProjector = traceProjector;
        this.properties = properties;
        this.recoveryPlanStore = recoveryPlanStore;
        this.recoveryPlanLauncher = recoveryPlanLauncher;
        this.recoveryExecutionService = recoveryExecutionService;
    }

    @PostMapping("/{incidentId}/recovery-plans")
    public Mono<ApiResponse<RecoveryPlanStartResponse>> createRecoveryPlan(
            @PathVariable String incidentId,
            @RequestBody RecoveryPlanStartRequest request) {
        return Mono.fromSupplier(() -> {
                    if (!properties.isRecoveryPlannerEnabled()) {
                        return ApiResponse.<RecoveryPlanStartResponse>failure(
                                ErrorCode.BAD_REQUEST,
                                "Incident Recovery Planner is disabled; set ORDERCARE_INCIDENT_RECOVERY_PLANNER_ENABLED=true");
                    }
                    return ApiResponse.success(recoveryPlanLauncher.start(incidentId, request));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{incidentId}/recovery-plans")
    public Mono<ApiResponse<List<IncidentRecoveryPlanRecord>>> recoveryPlans(
            @PathVariable String incidentId) {
        return Mono.fromSupplier(() -> ApiResponse.success(recoveryPlanStore.listByIncident(incidentId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{incidentId}/recovery-plans/{planId}")
    public Mono<ApiResponse<IncidentRecoveryPlanRecord>> recoveryPlan(
            @PathVariable String incidentId,
            @PathVariable String planId) {
        return Mono.fromSupplier(() -> recoveryPlanStore.find(planId)
                        .filter(plan -> incidentId.equals(plan.incidentId()))
                        .map(ApiResponse::success)
                        .orElseGet(() -> ApiResponse.failure(
                                ErrorCode.NOT_FOUND, "recovery plan not found: " + planId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{incidentId}/recovery-plans/{planId}/items/{itemId}/decision")
    public Mono<ApiResponse<IncidentRecoveryPlanRecord>> decideRecoveryPlanItem(
            @PathVariable String incidentId,
            @PathVariable String planId,
            @PathVariable String itemId,
            @RequestBody RecoveryPlanDecisionRequest request) {
        return Mono.fromSupplier(() -> {
                    IncidentRecoveryPlanRecord plan = recoveryPlanStore.find(planId)
                            .filter(current -> incidentId.equals(current.incidentId()))
                            .orElseThrow(() -> new IllegalArgumentException("recovery plan not found for incident"));
                    return ApiResponse.success(recoveryExecutionService.decideAndExecute(
                            plan.planId(), itemId, request));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/investigate")
    public Mono<ApiResponse<IncidentStartResponse>> investigate(
            @RequestBody IncidentInvestigationRequest request) {
        return Mono.fromSupplier(() -> {
                    if (!properties.isEnabled()) {
                        return ApiResponse.<IncidentStartResponse>failure(
                                ErrorCode.BAD_REQUEST,
                                "ordercare-incident-command-v1 is disabled; set ORDERCARE_INCIDENT_COMMAND_ENABLED=true");
                    }
                    return ApiResponse.success(launcher.start(request));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{incidentId}")
    public Mono<ApiResponse<IncidentAggregate>> find(@PathVariable String incidentId,
                                                     @RequestParam(defaultValue = "500") int eventLimit) {
        return Mono.fromSupplier(() -> incidentStore.findAggregate(
                        incidentId,
                        Math.max(1, Math.min(eventLimit, 10_000)))
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.failure(
                        ErrorCode.NOT_FOUND,
                        "incident not found: " + incidentId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{incidentId}/events")
    public Mono<ApiResponse<List<TaskEventRecord>>> events(@PathVariable String incidentId,
                                                           @RequestParam(defaultValue = "-1") long afterSequence,
                                                           @RequestParam(defaultValue = "500") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(taskEventStore.loadEventsAfter(
                        incidentId,
                        afterSequence,
                        Math.max(1, Math.min(limit, 10_000)))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{incidentId}/trace")
    public Mono<ApiResponse<IncidentTrace>> trace(@PathVariable String incidentId) {
        return Mono.fromSupplier(() -> traceProjector.project(incidentId)
                        .map(ApiResponse::success)
                        .orElseGet(() -> ApiResponse.failure(
                                ErrorCode.NOT_FOUND,
                                "incident not found: " + incidentId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/{incidentId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<IncidentEventStreamItem> eventStream(@PathVariable String incidentId,
                                                      @RequestParam(defaultValue = "-1") long afterSequence) {
        AtomicLong cursor = new AtomicLong(afterSequence);
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(2))
                .concatMap(ignored -> Mono.fromCallable(() -> taskEventStore.loadEventsAfter(
                                incidentId,
                                cursor.get(),
                                500))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapIterable(events -> toStreamItems(events, cursor));
    }

    private List<IncidentEventStreamItem> toStreamItems(List<TaskEventRecord> events, AtomicLong cursor) {
        if (events.isEmpty()) {
            return List.of(IncidentEventStreamItem.heartbeat(cursor.get()));
        }
        return events.stream()
                .map(event -> {
                    cursor.set(event.eventSequence());
                    return IncidentEventStreamItem.event(event);
                })
                .toList();
    }
}
