package com.agent.platform.tool;

import com.agent.platform.mcp.McpToolGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalToolExecutorTests {

    private static final String REQUIRED_X_SCHEMA = "{\"type\":\"object\","
            + "\"properties\":{\"x\":{\"type\":\"string\"}},"
            + "\"required\":[\"x\"]}";

    @Test
    void schemaValidationRejectsRemoteCallBeforeMcpGateway() {
        ToolDefinition definition = remoteDefinition("mcp.fake.echo");
        RecordingGateway gateway = new RecordingGateway(
                new ToolCallResult(definition.name(), true, "should not run", "", Map.of("provider", "mcp")));
        TrackingProvider<McpToolGateway> mcpProvider = new TrackingProvider<>(gateway);
        RecordingToolRunRecorder recorder = new RecordingToolRunRecorder();
        LocalToolExecutor executor = executor(
                definition, new JsonSchemaToolParameterValidator(new ObjectMapper()), recorder,
                mcpProvider, emptyHandlers());

        ToolCallResult result = executor.execute(new ToolCallRequest(definition.name(), "invalid", Map.of()));

        assertFalse(result.success());
        assertEquals("missing required argument: x", result.errorMessage());
        assertEquals(0, gateway.boundCalls);
        assertEquals(0, mcpProvider.getIfAvailableCalls);
        assertEquals(1, recorder.records.size());
    }

    @Test
    void validRemoteCallUsesTheResolvedDefinitionBinding() {
        ToolDefinition definition = remoteDefinition("mcp.fake.echo");
        ToolCallResult expected = new ToolCallResult(definition.name(), true, "remote ok", "",
                Map.of("provider", "mcp", "mcpServerId", "fake"));
        RecordingGateway gateway = new RecordingGateway(expected);
        LocalToolExecutor executor = executor(
                definition, new JsonSchemaToolParameterValidator(new ObjectMapper()),
                new RecordingToolRunRecorder(), new TrackingProvider<>(gateway), emptyHandlers());
        ToolCallRequest request = new ToolCallRequest(definition.name(), "valid", Map.of("x", "value"));

        ToolCallResult result = executor.execute(request);

        assertSame(expected, result);
        assertSame(definition, gateway.boundDefinition);
        assertSame(request, gateway.boundRequest);
        assertEquals(1, gateway.boundCalls);
        assertEquals(0, gateway.legacyCalls);
    }

    @Test
    void localToolHandlerPathStillExecutesWithoutTouchingMcpGateway() {
        ToolDefinition definition = new ToolDefinition(
                "custom.local", "local", REQUIRED_X_SCHEMA, ToolRiskLevel.LOW,
                Map.of("provider", "local"));
        RecordingHandler handler = new RecordingHandler(
                new ToolCallResult(definition.name(), true, "local ok", "", Map.of("provider", "local")));
        TrackingProvider<McpToolGateway> mcpProvider = new TrackingProvider<>(null);
        LocalToolExecutor executor = executor(
                definition, new JsonSchemaToolParameterValidator(new ObjectMapper()),
                new RecordingToolRunRecorder(), mcpProvider, streamProvider(List.of(handler)));
        ToolCallRequest request = new ToolCallRequest(definition.name(), "local", Map.of("x", "value"));

        ToolCallResult result = executor.execute(request);

        assertTrue(result.success());
        assertEquals("local ok", result.content());
        assertSame(request, handler.request);
        assertEquals(0, mcpProvider.getIfAvailableCalls);
    }

    @Test
    void builtInTicketToolPathRemainsAvailable() {
        ToolDefinition definition = new ToolDefinition(
                "ticket_status", "status", "{\"type\":\"object\"}", ToolRiskLevel.LOW,
                Map.of("provider", "local"));
        TicketStore tickets = new TicketStore() {
            @Override
            public Optional<SupportTicket> findById(String ticketId) {
                return Optional.of(new SupportTicket("T1001", "Example", "P2", "处理中",
                        "agent", Instant.now(), Instant.now()));
            }

            @Override
            public SupportTicket create(String title, String priority) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<SupportTicket> updatePriority(String ticketId, String priority) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<SupportTicket> close(String ticketId, String reason) {
                throw new UnsupportedOperationException();
            }
        };
        LocalToolExecutor executor = new LocalToolExecutor(
                new StaticRegistry(definition), new JsonSchemaToolParameterValidator(new ObjectMapper()),
                new RecordingToolRunRecorder(), tickets, new TrackingProvider<>(null), emptyHandlers());

        ToolCallResult result = executor.execute(new ToolCallRequest("ticket_status", "ticket", Map.of()));

        assertTrue(result.success());
        assertTrue(result.content().contains("T1001"));
        assertEquals("local", result.metadata().get("provider"));
    }

    private static LocalToolExecutor executor(ToolDefinition definition,
                                               ToolParameterValidator validator,
                                               ToolRunRecorder recorder,
                                               ObjectProvider<McpToolGateway> mcpProvider,
                                               ObjectProvider<ToolHandler> handlers) {
        return new LocalToolExecutor(new StaticRegistry(definition), validator, recorder,
                new EmptyTicketStore(), mcpProvider, handlers);
    }

    private static ToolDefinition remoteDefinition(String name) {
        return new ToolDefinition(name, "remote", REQUIRED_X_SCHEMA, ToolRiskLevel.MEDIUM,
                Map.of("provider", "mcp", "mcpServerId", "fake", "mcpToolName", "echo",
                        "mcpSessionGeneration", 1L));
    }

    private static ObjectProvider<ToolHandler> emptyHandlers() {
        return streamProvider(List.of());
    }

    private static <T> ObjectProvider<T> streamProvider(List<T> values) {
        return new ObjectProvider<>() {
            @Override
            public Stream<T> orderedStream() {
                return values.stream();
            }
        };
    }

    private static final class StaticRegistry implements ToolRegistry {

        private final ToolDefinition definition;

        private StaticRegistry(ToolDefinition definition) {
            this.definition = definition;
        }

        @Override
        public List<ToolDefinition> listTools() {
            return List.of(definition);
        }

        @Override
        public Optional<ToolDefinition> findTool(String toolName) {
            return definition.name().equals(toolName) ? Optional.of(definition) : Optional.empty();
        }
    }

    private static final class RecordingGateway implements McpToolGateway {

        private final ToolCallResult result;

        private ToolDefinition boundDefinition;

        private ToolCallRequest boundRequest;

        private int boundCalls;

        private int legacyCalls;

        private RecordingGateway(ToolCallResult result) {
            this.result = result;
        }

        @Override
        public List<ToolDefinition> discoverTools() {
            return List.of();
        }

        @Override
        public ToolCallResult callTool(ToolCallRequest request) {
            legacyCalls++;
            return result;
        }

        @Override
        public ToolCallResult callTool(ToolDefinition definition, ToolCallRequest request) {
            boundCalls++;
            boundDefinition = definition;
            boundRequest = request;
            return result;
        }
    }

    private static final class RecordingHandler implements ToolHandler {

        private final ToolCallResult result;

        private ToolCallRequest request;

        private RecordingHandler(ToolCallResult result) {
            this.result = result;
        }

        @Override
        public boolean supports(String toolName) {
            return result.toolName().equals(toolName);
        }

        @Override
        public ToolCallResult execute(ToolCallRequest request) {
            this.request = request;
            return result;
        }
    }

    private static final class TrackingProvider<T> implements ObjectProvider<T> {

        private final T value;

        private int getIfAvailableCalls;

        private TrackingProvider(T value) {
            this.value = value;
        }

        @Override
        public T getIfAvailable() {
            getIfAvailableCalls++;
            return value;
        }
    }

    private static final class RecordingToolRunRecorder implements ToolRunRecorder {

        private final List<ToolCallRecord> records = new ArrayList<>();

        @Override
        public void record(ToolCallRecord record) {
            records.add(record);
        }

        @Override
        public List<ToolCallRecord> recent(int limit) {
            return records.stream().limit(limit).toList();
        }

        @Override
        public ToolRunStats stats() {
            long total = records.size();
            long success = records.stream().filter(ToolCallRecord::success).count();
            return new ToolRunStats(total, success, total - success,
                    total == 0 ? 0 : (double) success / total, Map.of());
        }
    }

    private static final class EmptyTicketStore implements TicketStore {

        @Override
        public Optional<SupportTicket> findById(String ticketId) {
            return Optional.empty();
        }

        @Override
        public SupportTicket create(String title, String priority) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SupportTicket> updatePriority(String ticketId, String priority) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SupportTicket> close(String ticketId, String reason) {
            throw new UnsupportedOperationException();
        }
    }
}
