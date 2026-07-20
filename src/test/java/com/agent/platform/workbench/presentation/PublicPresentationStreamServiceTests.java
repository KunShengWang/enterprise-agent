package com.agent.platform.workbench.presentation;

import com.agent.platform.config.WorkbenchStreamProperties;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicPresentationStreamServiceTests {

    @Test
    void historyDtoAndSseDtoAreIdenticalAndCursorDoesNotReplay() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant", "alice", Set.of("USER"));
        PublicPresentationService presentations = mock(PublicPresentationService.class);
        PublicPresentation item = new PublicPresentation(
                "pp-1", "work-1", 20, 1, PublicPresentationKind.ACTION_STARTED,
                PublicPresentationStatus.ACTIVE, "开始执行", "Agent 已开始处理任务。", List.of(),
                PublicPresentationDetail.empty(), "AGENT_RUN", "run-1", "source-1",
                Instant.now(), PublicVisibility.PUBLIC);
        when(presentations.publicTimeline(principal, "work-1", 10, 500)).thenReturn(List.of(item));
        when(presentations.publicTimeline(principal, "work-1", 20, 500)).thenReturn(List.of());
        WorkbenchStreamProperties properties = new WorkbenchStreamProperties();
        properties.setPollIntervalMillis(20);
        PublicPresentationStreamService stream = new PublicPresentationStreamService(presentations, properties);
        AtomicLong cursor = new AtomicLong(10);

        assertEquals(List.of(item), stream.poll(principal, "work-1", cursor));
        assertEquals(20, cursor.get());
        assertEquals(List.of(), stream.poll(principal, "work-1", cursor));
        assertEquals(item, stream.stream(principal, "work-1", 10)
                .blockFirst(Duration.ofSeconds(2)).data());
        assertEquals(20, stream.resolveCursor(5, "p:20"));
    }
}
