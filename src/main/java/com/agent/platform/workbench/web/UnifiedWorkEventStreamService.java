package com.agent.platform.workbench.web;

import com.agent.platform.config.WorkbenchStreamProperties;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkLinkRelation;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class UnifiedWorkEventStreamService {

    private final WorkbenchStore workbenchStore;
    private final AgentTimelineStore timelineStore;
    private final WorkbenchStreamProperties properties;

    public UnifiedWorkEventStreamService(WorkbenchStore workbenchStore,
                                         AgentTimelineStore timelineStore,
                                         WorkbenchStreamProperties properties) {
        this.workbenchStore = workbenchStore;
        this.timelineStore = timelineStore;
        this.properties = properties;
    }

    public Flux<ServerSentEvent<UnifiedWorkStreamItem>> stream(AuthenticatedPrincipal principal,
                                                               String workItemId,
                                                               UnifiedWorkStreamCursor initialCursor) {
        AtomicLong workCursor = new AtomicLong(initialCursor.workSequence());
        AtomicLong runCursor = new AtomicLong(initialCursor.primaryRunSequence());
        AtomicLong polls = new AtomicLong();
        return Flux.defer(() -> Mono.fromCallable(() -> poll(
                                principal, workItemId, workCursor, runCursor, polls.incrementAndGet()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .repeatWhen(completed -> completed.delayElements(
                        Duration.ofMillis(properties.getPollIntervalMillis())))
                .flatMapIterable(items -> items)
                .map(this::sse)
                .onErrorResume(error -> {
                    UnifiedWorkStreamCursor cursor = new UnifiedWorkStreamCursor(
                            workCursor.get(), runCursor.get());
                    UnifiedWorkStreamItem item = new UnifiedWorkStreamItem(
                            "SYNC_ERROR", "", cursor.workSequence(), "WORK_ITEM", workItemId, null,
                            "SYNC_ERROR", "Timeline synchronization is temporarily unavailable",
                            Map.of("errorType", error.getClass().getSimpleName()), Instant.now(), cursor.encode());
                    return Flux.just(ServerSentEvent.<UnifiedWorkStreamItem>builder(item)
                            .event("sync-error").id(cursor.encode()).build());
                });
    }

    List<UnifiedWorkStreamItem> poll(AuthenticatedPrincipal principal,
                                     String workItemId,
                                     AtomicLong workCursor,
                                     AtomicLong runCursor,
                                     long pollNumber) {
        AgentWorkItem workItem = workbenchStore.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
        List<UnifiedWorkStreamItem> items = new ArrayList<>();
        List<WorkEvent> workEvents = workbenchStore.loadEvents(
                principal, workItemId, workCursor.get(), properties.getBatchSize());
        if (!workEvents.isEmpty()) {
            long expected = workCursor.get() + 1;
            if (workEvents.get(0).sequence() != expected) {
                UnifiedWorkStreamCursor cursor = new UnifiedWorkStreamCursor(workCursor.get(), runCursor.get());
                return List.of(new UnifiedWorkStreamItem(
                        "GAP", "", cursor.workSequence(), "WORK_ITEM", workItemId, null,
                        "SEQUENCE_GAP", "WorkEvent sequence gap detected",
                        Map.of("expectedSequence", expected, "actualSequence", workEvents.get(0).sequence()),
                        Instant.now(), cursor.encode()));
            }
            for (WorkEvent event : workEvents) {
                if (event.sequence() != workCursor.get() + 1) {
                    UnifiedWorkStreamCursor cursor = new UnifiedWorkStreamCursor(
                            workCursor.get(), runCursor.get());
                    items.add(new UnifiedWorkStreamItem(
                            "GAP", "", cursor.workSequence(), "WORK_ITEM", workItemId, null,
                            "SEQUENCE_GAP", "WorkEvent sequence gap detected",
                            Map.of("expectedSequence", workCursor.get() + 1,
                                    "actualSequence", event.sequence()),
                            Instant.now(), cursor.encode()));
                    return List.copyOf(items);
                }
                workCursor.set(event.sequence());
                UnifiedWorkStreamCursor cursor = new UnifiedWorkStreamCursor(workCursor.get(), runCursor.get());
                items.add(workEvent(event, cursor));
            }
        }

        String primaryRunId = primaryRunId(principal, workItem);
        if (!primaryRunId.isBlank()) {
            for (AgentEvent event : timelineStore.loadEventsAfter(
                    primaryRunId, runCursor.get(), properties.getBatchSize())) {
                runCursor.accumulateAndGet(event.sequence(), Math::max);
                if (event.type() != AgentEventType.MODEL_DELTA) continue;
                UnifiedWorkStreamCursor cursor = new UnifiedWorkStreamCursor(workCursor.get(), runCursor.get());
                Map<String, Object> payload = new LinkedHashMap<>(event.payload());
                payload.put("primaryRun", true);
                items.add(new UnifiedWorkStreamItem(
                        "MODEL_DELTA", event.eventId(), cursor.workSequence(), "AGENT_RUN", event.runId(),
                        event.sequence(), event.type().name(), event.content(), payload,
                        event.createdAt(), cursor.encode()));
            }
        }
        if (items.isEmpty() && pollNumber % properties.getHeartbeatEveryPolls() == 0) {
            UnifiedWorkStreamCursor cursor = new UnifiedWorkStreamCursor(workCursor.get(), runCursor.get());
            items.add(new UnifiedWorkStreamItem(
                    "HEARTBEAT", "", cursor.workSequence(), "WORK_ITEM", workItemId, null,
                    "HEARTBEAT", "", Map.of(), Instant.now(), cursor.encode()));
        }
        return List.copyOf(items);
    }

    private UnifiedWorkStreamItem workEvent(WorkEvent event, UnifiedWorkStreamCursor cursor) {
        return new UnifiedWorkStreamItem(
                "WORK_EVENT", event.eventId(), event.sequence(), event.sourceType(), event.sourceId(),
                event.sourceSequence(), event.eventType().name(), event.summary(), event.payload(),
                event.sourceCreatedAt(), cursor.encode());
    }

    private String primaryRunId(AuthenticatedPrincipal principal, AgentWorkItem workItem) {
        return workbenchStore.listLinks(principal, workItem.workItemId()).stream()
                .filter(link -> link.linkType() == WorkLinkType.RUN)
                .filter(link -> link.relation() == WorkLinkRelation.PRIMARY)
                .map(link -> link.linkedId())
                .filter(runId -> workItem.activeRunId().isBlank() || workItem.activeRunId().equals(runId))
                .findFirst().orElse("");
    }

    private ServerSentEvent<UnifiedWorkStreamItem> sse(UnifiedWorkStreamItem item) {
        return ServerSentEvent.<UnifiedWorkStreamItem>builder(item)
                .id(item.resumeToken())
                .event(sseEventName(item.kind()))
                .build();
    }

    private String sseEventName(String kind) {
        return switch (kind) {
            case "WORK_EVENT" -> "work-event";
            case "MODEL_DELTA" -> "model-delta";
            case "GAP" -> "gap";
            case "HEARTBEAT" -> "heartbeat";
            default -> "sync-error";
        };
    }
}
