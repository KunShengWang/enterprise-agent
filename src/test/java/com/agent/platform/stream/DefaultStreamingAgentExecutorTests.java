package com.agent.platform.stream;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventListener;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentStopReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultStreamingAgentExecutorTests {

    @Test
    void exposesPersistedSequenceAndEmitsHeartbeatWithLastSequence() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentProperties properties = new AgentProperties();
        properties.setStreamHeartbeatSeconds(1);
        when(runtime.run(any(AgentRequest.class), any(AgentEventListener.class))).thenAnswer(invocation -> {
            AgentEventListener listener = invocation.getArgument(1);
            listener.onEvent(new AgentEvent(
                    "event-1", "run-1", "session-1", 42,
                    AgentEventType.MODEL_STARTED, "started", Map.of(), Instant.now()
            ));
            Thread.sleep(1_200);
            return new AgentRuntimeResult(
                    "run-1", "session-1", AgentRunState.COMPLETED, AgentStopReason.COMPLETED,
                    "done", "", null, List.of()
            );
        });
        DefaultStreamingAgentExecutor executor = new DefaultStreamingAgentExecutor(runtime, properties);

        List<AgentStreamEvent> events = executor.stream(
                        new AgentRequest("session-1", "user-1", "question", Map.of()))
                .collectList()
                .block(Duration.ofSeconds(4));

        assertEquals(42, events.get(0).sequence());
        assertTrue(events.stream().anyMatch(event ->
                event.type().equals("heartbeat")
                        && event.sequence() == 42
                        && Boolean.FALSE.equals(event.metadata().get("persisted"))));
    }

    @Test
    void resumesExistingRunThroughTheSameStructuredEventStream() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentProperties properties = new AgentProperties();
        when(runtime.resume(eq("run-1"), any(AgentEventListener.class))).thenAnswer(invocation -> {
            AgentEventListener listener = invocation.getArgument(1);
            listener.onEvent(new AgentEvent(
                    "event-43", "run-1", "session-1", 43,
                    AgentEventType.TOOL_COMPLETED, "approved tool completed", Map.of(), Instant.now()
            ));
            listener.onEvent(new AgentEvent(
                    "event-44", "run-1", "session-1", 44,
                    AgentEventType.RUN_COMPLETED, "done", Map.of(), Instant.now()
            ));
            return new AgentRuntimeResult(
                    "run-1", "session-1", AgentRunState.COMPLETED, AgentStopReason.COMPLETED,
                    "done", "", null, List.of()
            );
        });
        DefaultStreamingAgentExecutor executor = new DefaultStreamingAgentExecutor(runtime, properties);

        List<AgentStreamEvent> events = executor.resume("run-1")
                .collectList()
                .block(Duration.ofSeconds(2));

        assertEquals(List.of("tool_completed", "run_completed"),
                events.stream().map(AgentStreamEvent::type).toList());
        assertTrue(events.stream().allMatch(event ->
                event.traceId().equals("run-1") && event.conversationId().equals("session-1")));
        verify(runtime).resume(eq("run-1"), any(AgentEventListener.class));
    }

    @Test
    void clientDisconnectRequestsPauseInsteadOfTerminalCancellation() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentProperties properties = new AgentProperties();
        CountDownLatch pauseRequested = new CountDownLatch(1);
        when(runtime.pause("run-1")).thenAnswer(invocation -> {
            pauseRequested.countDown();
            return true;
        });
        when(runtime.run(any(AgentRequest.class), any(AgentEventListener.class))).thenAnswer(invocation -> {
            AgentEventListener listener = invocation.getArgument(1);
            listener.onEvent(new AgentEvent(
                    "event-1", "run-1", "session-1", 1,
                    AgentEventType.MODEL_STARTED, "started", Map.of(), Instant.now()
            ));
            try {
                Thread.sleep(5_000);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new AgentRuntimeResult(
                    "run-1", "session-1", AgentRunState.PAUSED, AgentStopReason.PAUSED,
                    "paused", "", null, List.of()
            );
        });
        DefaultStreamingAgentExecutor executor = new DefaultStreamingAgentExecutor(runtime, properties);

        AgentStreamEvent first = executor.stream(
                        new AgentRequest("session-1", "user-1", "question", Map.of()))
                .next()
                .block(Duration.ofSeconds(2));

        assertEquals("model_started", first.type());
        assertTrue(pauseRequested.await(2, TimeUnit.SECONDS));
        verify(runtime).pause("run-1");
        verify(runtime, never()).cancel(any());
    }
}
