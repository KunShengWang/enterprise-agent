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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
