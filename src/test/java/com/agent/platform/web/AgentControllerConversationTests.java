package com.agent.platform.web;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.resilience.RateLimitResult;
import com.agent.platform.resilience.RateLimitService;
import com.agent.platform.runtime.AgentMessage;
import com.agent.platform.runtime.AgentMessageType;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.stream.StreamingAgentExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerConversationTests {

    @Test
    void postRunsWithoutAcceptHeaderRetainsJsonCompatibility() {
        AgentExecutor agentExecutor = mock(AgentExecutor.class);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        AgentRequest request = new AgentRequest("conversation-1", "user-1", "sync answer", Map.of());
        when(rateLimitService.acquire("user-1"))
                .thenReturn(new RateLimitResult(true, "user-1", 60, 59, System.currentTimeMillis() + 60_000));
        AgentController controller = new AgentController(
                agentExecutor,
                mock(AgentProperties.class),
                mock(StreamingAgentExecutor.class),
                rateLimitService,
                mock(AgentRunStore.class),
                mock(AgentRuntime.class),
                mock(AgentTimelineStore.class)
        );

        WebTestClient.bindToController(controller).build()
                .post()
                .uri("/api/agent/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);

        verify(agentExecutor).execute(org.mockito.ArgumentMatchers.any(AgentRequest.class));
    }

    @Test
    void postRunsUsesStructuredSseWhenClientAcceptsEventStream() {
        StreamingAgentExecutor streamingExecutor = mock(StreamingAgentExecutor.class);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        AgentRequest request = new AgentRequest("conversation-1", "user-1", "stream answer", Map.of());
        com.agent.platform.stream.AgentStreamEvent delta = new com.agent.platform.stream.AgentStreamEvent(
                "event-1", "run-1", "conversation-1", 7, "model_delta", "增量回答",
                Instant.parse("2026-07-18T00:00:00Z"), Map.of("deltaIndex", 1)
        );
        when(rateLimitService.acquire("user-1"))
                .thenReturn(new RateLimitResult(true, "user-1", 60, 59, System.currentTimeMillis() + 60_000));
        when(streamingExecutor.stream(org.mockito.ArgumentMatchers.any(AgentRequest.class)))
                .thenReturn(Flux.just(delta));
        AgentController controller = new AgentController(
                mock(AgentExecutor.class),
                mock(AgentProperties.class),
                streamingExecutor,
                rateLimitService,
                mock(AgentRunStore.class),
                mock(AgentRuntime.class),
                mock(AgentTimelineStore.class)
        );

        WebTestClient.bindToController(controller).build()
                .post()
                .uri("/api/agent/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBodyList(com.agent.platform.stream.AgentStreamEvent.class)
                .hasSize(1)
                .contains(delta);

        verify(streamingExecutor).stream(org.mockito.ArgumentMatchers.any(AgentRequest.class));
    }

    @Test
    void conversationMessagesExposeOnlyUserAndAssistantText() {
        AgentTimelineStore timelineStore = mock(AgentTimelineStore.class);
        when(timelineStore.loadMessages("conversation-1", 1000)).thenReturn(List.of(
                message("m-1", "run-1", 1, AgentMessageType.SYSTEM, "private system prompt"),
                message("m-2", "run-1", 2, AgentMessageType.USER, "first question"),
                message("m-3", "run-1", 3, AgentMessageType.ASSISTANT_TOOL_CALL, ""),
                message("m-4", "run-1", 4, AgentMessageType.TOOL_RESULT, "private tool result"),
                message("m-5", "run-1", 5, AgentMessageType.ASSISTANT_TEXT, "first answer")
        ));
        AgentController controller = new AgentController(
                mock(AgentExecutor.class),
                mock(AgentProperties.class),
                mock(StreamingAgentExecutor.class),
                mock(RateLimitService.class),
                mock(AgentRunStore.class),
                mock(AgentRuntime.class),
                timelineStore
        );

        ApiResponse<List<ConversationMessageView>> response = controller
                .conversationMessages("conversation-1", 100)
                .block(Duration.ofSeconds(2));

        assertNotNull(response);
        assertTrue(response.success());
        assertEquals(List.of("USER", "ASSISTANT"),
                response.data().stream().map(ConversationMessageView::role).toList());
        assertEquals(List.of("first question", "first answer"),
                response.data().stream().map(ConversationMessageView::content).toList());
        verify(timelineStore).loadMessages("conversation-1", 1000);
    }

    private AgentMessage message(String messageId,
                                 String runId,
                                 long sequence,
                                 AgentMessageType type,
                                 String content) {
        return new AgentMessage(
                messageId,
                "conversation-1",
                runId,
                sequence,
                type,
                content,
                type == AgentMessageType.ASSISTANT_TOOL_CALL || type == AgentMessageType.TOOL_RESULT
                        ? "tool-call-1"
                        : "",
                type == AgentMessageType.ASSISTANT_TOOL_CALL ? "test_tool" : "",
                Map.of(),
                Map.of(),
                10,
                Instant.parse("2026-07-18T00:00:00Z").plusSeconds(sequence)
        );
    }
}
