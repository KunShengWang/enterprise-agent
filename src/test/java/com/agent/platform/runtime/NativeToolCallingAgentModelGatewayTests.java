package com.agent.platform.runtime;

import com.agent.platform.llm.LlmCallException;
import com.agent.platform.llm.NativeChatModelClient;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeToolCallingAgentModelGatewayTests {

    @Test
    void sendsNativeToolSchemaAndReturnsStructuredToolCallWithoutPublishingIt() {
        NativeChatModelClient client = mock(NativeChatModelClient.class);
        AssistantMessage.ToolCall nativeCall = new AssistantMessage.ToolCall(
                "provider-call-1", "function", "ticket_status", "{\"id\":\"T1\"}"
        );
        ChatResponse toolCallResponse = response(
                AssistantMessage.builder().content("").toolCalls(List.of(nativeCall)).build(), "tool_calls");
        when(client.streamNative(any(Prompt.class))).thenReturn(Flux.just(toolCallResponse));
        NativeToolCallingAgentModelGateway gateway = gateway(client);
        List<String> deltas = new ArrayList<>();

        AgentModelTurn turn = gateway.nextTurn(requestWithTool(List.of()), deltas::add);

        assertEquals(1, turn.toolCalls().size());
        assertEquals("provider-call-1", turn.toolCalls().get(0).toolCallId());
        assertEquals("ticket_status", turn.toolCalls().get(0).toolName());
        assertEquals("T1", turn.toolCalls().get(0).arguments().get("id"));
        assertTrue(deltas.isEmpty());

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(client).streamNative(promptCaptor.capture());
        DeepSeekChatOptions options = assertInstanceOf(
                DeepSeekChatOptions.class, promptCaptor.getValue().getOptions());
        assertEquals(1, options.getToolCallbacks().size());
        assertEquals("ticket_status",
                options.getToolCallbacks().get(0).getToolDefinition().name());
        assertTrue(options.getToolCallbacks().get(0).getToolDefinition().inputSchema().contains("required"));
        assertThrows(IllegalStateException.class,
                () -> options.getToolCallbacks().get(0).call("{\"id\":\"T1\"}"));
    }

    @Test
    void replaysPersistedToolCallAndResultAsNativeMessages() {
        NativeChatModelClient client = mock(NativeChatModelClient.class);
        ChatResponse finalResponse = response(new AssistantMessage("done"), "stop");
        when(client.completeNative(any(Prompt.class))).thenReturn(finalResponse);
        NativeToolCallingAgentModelGateway gateway = gateway(client);
        AgentMessage toolCall = message(
                1, AgentMessageType.ASSISTANT_TOOL_CALL, "", "runtime-call-1", "ticket_status",
                Map.of("id", "T1"), Map.of("modelToolCallId", "provider-call-1"));
        AgentMessage toolResult = message(
                2, AgentMessageType.TOOL_RESULT, "status=OPEN", "runtime-call-1", "ticket_status",
                Map.of(), Map.of("success", true, "error", ""));

        AgentModelTurn turn = gateway.nextTurn(requestWithTool(List.of(toolCall, toolResult)));

        assertEquals("done", turn.assistantText());
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(client).completeNative(promptCaptor.capture());
        List<Message> messages = promptCaptor.getValue().getInstructions();
        AssistantMessage assistant = messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .findFirst()
                .orElseThrow();
        assertEquals("runtime-call-1", assistant.getToolCalls().get(0).id());
        ToolResponseMessage toolResponse = messages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("runtime-call-1", toolResponse.getResponses().get(0).id());
        assertTrue(toolResponse.getResponses().get(0).responseData().contains("status=OPEN"));
        assertTrue(toolResponse.getResponses().get(0).responseData().contains("success"));
    }

    @Test
    void mergesStreamingToolCallArgumentFragments() {
        NativeChatModelClient client = mock(NativeChatModelClient.class);
        AssistantMessage.ToolCall first = new AssistantMessage.ToolCall(
                "provider-call-1", "function", "ticket_", "{\"id\":"
        );
        AssistantMessage.ToolCall second = new AssistantMessage.ToolCall(
                "", "function", "status", "\"T1\"}"
        );
        ChatResponse firstChunk = response(
                AssistantMessage.builder().content("").toolCalls(List.of(first)).build(), "unknown");
        ChatResponse secondChunk = response(
                AssistantMessage.builder().content("").toolCalls(List.of(second)).build(), "tool_calls");
        when(client.streamNative(any(Prompt.class))).thenReturn(Flux.just(firstChunk, secondChunk));

        AgentModelTurn turn = gateway(client).nextTurn(requestWithTool(List.of()), ignored -> { });

        assertEquals(1, turn.toolCalls().size());
        assertEquals("ticket_status", turn.toolCalls().get(0).toolName());
        assertEquals("T1", turn.toolCalls().get(0).arguments().get("id"));
    }

    @Test
    void buffersFinalTextWhileToolsAreAvailableAndPublishesOnlyAfterProtocolIsKnown() {
        NativeChatModelClient client = mock(NativeChatModelClient.class);
        ChatResponse firstChunk = response(new AssistantMessage("first "), "unknown");
        ChatResponse secondChunk = response(new AssistantMessage("second"), "stop");
        when(client.streamNative(any(Prompt.class))).thenReturn(Flux.just(firstChunk, secondChunk));
        List<String> deltas = new ArrayList<>();

        AgentModelTurn turn = gateway(client).nextTurn(requestWithTool(List.of()), deltas::add);

        assertEquals("first second", turn.assistantText());
        assertEquals(List.of("first second"), deltas);
    }

    @Test
    void streamsFinalTextImmediatelyWhenNoToolsExist() {
        NativeChatModelClient client = mock(NativeChatModelClient.class);
        ChatResponse firstChunk = response(new AssistantMessage("first "), "unknown");
        ChatResponse secondChunk = response(new AssistantMessage("second"), "stop");
        when(client.streamNative(any(Prompt.class))).thenReturn(Flux.just(firstChunk, secondChunk));
        List<String> deltas = new ArrayList<>();

        AgentModelTurn turn = gateway(client).nextTurn(requestWithoutTools(), deltas::add);

        assertEquals("first second", turn.assistantText());
        assertEquals(List.of("first ", "second"), deltas);
    }

    @Test
    void rejectsMalformedNativeToolArguments() {
        NativeChatModelClient client = mock(NativeChatModelClient.class);
        AssistantMessage.ToolCall nativeCall = new AssistantMessage.ToolCall(
                "provider-call-1", "function", "ticket_status", "{broken"
        );
        ChatResponse malformedResponse = response(
                AssistantMessage.builder().content("").toolCalls(List.of(nativeCall)).build(), "tool_calls");
        when(client.completeNative(any(Prompt.class))).thenReturn(malformedResponse);

        LlmCallException failure = assertThrows(LlmCallException.class,
                () -> gateway(client).nextTurn(requestWithTool(List.of())));

        assertEquals("MODEL_PROTOCOL_ERROR", failure.errorType());
        assertFalse(failure.safeMessage().contains("{broken"));
    }

    @Test
    void mapsProviderSafeAliasBackToRuntimeToolName() {
        String runtimeToolName = "mcp.filesystem.read_file";
        NativeChatModelClient client = new NativeChatModelClient() {
            @Override
            public ChatResponse completeNative(Prompt prompt) {
                ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
                String providerName = options.getToolCallbacks().get(0).getToolDefinition().name();
                assertFalse(providerName.contains("."));
                AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                        "call-1", "function", providerName, "{\"path\":\"demo.txt\"}");
                return response(AssistantMessage.builder().content("").toolCalls(List.of(call)).build(), "tool_calls");
            }

            @Override
            public Flux<ChatResponse> streamNative(Prompt prompt) {
                return Flux.error(new UnsupportedOperationException("not used"));
            }
        };
        ToolDefinition tool = new ToolDefinition(
                runtimeToolName,
                "read file",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}",
                ToolRiskLevel.LOW,
                Map.of("readOnly", true));
        AgentModelRequest request = new AgentModelRequest(
                "run-1", "session-1", "system", List.of(), List.of(tool), Map.of());

        AgentModelTurn turn = gateway(client).nextTurn(request);

        assertEquals(runtimeToolName, turn.toolCalls().get(0).toolName());
    }

    private NativeToolCallingAgentModelGateway gateway(NativeChatModelClient client) {
        return new NativeToolCallingAgentModelGateway(client, new ObjectMapper());
    }

    private AgentModelRequest requestWithoutTools() {
        return new AgentModelRequest("run-1", "session-1", "system", List.of(), List.of(), Map.of());
    }

    private AgentModelRequest requestWithTool(List<AgentMessage> messages) {
        ToolDefinition tool = new ToolDefinition(
                "ticket_status",
                "read ticket status",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}",
                ToolRiskLevel.LOW,
                Map.of("readOnly", true)
        );
        return new AgentModelRequest("run-1", "session-1", "system", messages, List.of(tool), Map.of());
    }

    private AgentMessage message(long sequence,
                                 AgentMessageType type,
                                 String content,
                                 String toolCallId,
                                 String toolName,
                                 Map<String, Object> arguments,
                                 Map<String, Object> metadata) {
        return new AgentMessage(
                "message-" + sequence,
                "session-1",
                "run-1",
                sequence,
                type,
                content,
                toolCallId,
                toolName,
                arguments,
                metadata,
                10,
                Instant.now()
        );
    }

    private ChatResponse response(AssistantMessage output, String finishReason) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        ChatGenerationMetadata generationMetadata = mock(ChatGenerationMetadata.class);
        ChatResponseMetadata responseMetadata = mock(ChatResponseMetadata.class);
        when(response.getResult()).thenReturn(generation);
        when(response.getMetadata()).thenReturn(responseMetadata);
        when(generation.getOutput()).thenReturn(output);
        when(generation.getMetadata()).thenReturn(generationMetadata);
        when(generationMetadata.getFinishReason()).thenReturn(finishReason);
        when(responseMetadata.getModel()).thenReturn("test-model");
        return response;
    }
}
