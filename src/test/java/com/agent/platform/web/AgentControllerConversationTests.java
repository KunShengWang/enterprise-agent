package com.agent.platform.web;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.resilience.RateLimitService;
import com.agent.platform.runtime.AgentMessage;
import com.agent.platform.runtime.AgentMessageType;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.stream.StreamingAgentExecutor;
import org.junit.jupiter.api.Test;

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
