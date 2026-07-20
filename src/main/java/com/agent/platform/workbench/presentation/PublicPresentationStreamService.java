package com.agent.platform.workbench.presentation;

import com.agent.platform.config.WorkbenchStreamProperties;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PublicPresentationStreamService {

    private final PublicPresentationService presentations;
    private final WorkbenchStreamProperties properties;

    public PublicPresentationStreamService(PublicPresentationService presentations,
                                           WorkbenchStreamProperties properties) {
        this.presentations = presentations;
        this.properties = properties;
    }

    public Flux<ServerSentEvent<PublicPresentation>> stream(AuthenticatedPrincipal principal,
                                                             String workItemId,
                                                             long initialSequence) {
        AtomicLong cursor = new AtomicLong(initialSequence);
        return Flux.defer(() -> Mono.fromCallable(() -> poll(principal, workItemId, cursor))
                        .subscribeOn(Schedulers.boundedElastic()))
                .repeatWhen(completed -> completed.delayElements(
                        Duration.ofMillis(properties.getPollIntervalMillis())))
                .flatMapIterable(items -> items)
                .map(item -> ServerSentEvent.<PublicPresentation>builder(item)
                        .id("p:" + item.sequence()).event("presentation").build());
    }

    List<PublicPresentation> poll(AuthenticatedPrincipal principal, String workItemId, AtomicLong cursor) {
        List<PublicPresentation> items = presentations.publicTimeline(
                principal, workItemId, cursor.get(), properties.getBatchSize());
        items.forEach(item -> cursor.accumulateAndGet(item.sequence(), Math::max));
        return items;
    }

    public long resolveCursor(long querySequence, String lastEventId) {
        long headerSequence = -1;
        if (lastEventId != null && lastEventId.startsWith("p:")) {
            try {
                headerSequence = Long.parseLong(lastEventId.substring(2));
            } catch (NumberFormatException ignored) {
                headerSequence = -1;
            }
        }
        return Math.max(querySequence, headerSequence);
    }
}
