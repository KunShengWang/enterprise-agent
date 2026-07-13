package com.agent.platform.mcp;

import com.agent.platform.config.McpProperties;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@ConditionalOnProperty(prefix = "enterprise-agent.mcp", name = "enabled", havingValue = "true")
public class StdioMcpToolGateway implements McpToolGateway {

    private final McpProperties mcpProperties;

    private final ObjectMapper objectMapper;

    public StdioMcpToolGateway(McpProperties mcpProperties, ObjectMapper objectMapper) {
        this.mcpProperties = mcpProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 寻找服务商的工具
     */
    @Override
    public List<ToolDefinition> discoverTools() {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (McpProperties.Server server : mcpProperties.effectiveServers()) {
            // 寻找服务商的工具
            definitions.addAll(discoverTools(server));
        }
        return List.copyOf(definitions);
    }

    @Override
    public ToolCallResult callTool(ToolCallRequest request) {
        Optional<McpProperties.Server> server = resolveServer(request.toolName());
        if (server.isEmpty()) {
            return new ToolCallResult(request.toolName(), false, "", "No MCP server matched tool: " + request.toolName(), Map.of("provider", "mcp"));
        }
        return callTool(server.get(), request);
    }

    /**
     * 寻找服务商的工具
     */
    private List<ToolDefinition> discoverTools(McpProperties.Server server) {
        try (McpSession session = openSession(server)) {
            initialize(server, session);
            Map<?, ?> result = session.request("tools/list", Map.of());
            Object toolsValue = result.get("tools");
            if (!(toolsValue instanceof List<?> tools)) {
                return List.of();
            }
            List<ToolDefinition> definitions = new ArrayList<>();
            for (Object toolValue : tools) {
                if (toolValue instanceof Map<?, ?> tool) {
                    definitions.add(toToolDefinition(server, tool));
                }
            }
            return definitions;
        }
        catch (RuntimeException | IOException exception) {
            return List.of();
        }
    }

    private ToolCallResult callTool(McpProperties.Server server, ToolCallRequest request) {
        String originalName = originalToolName(server, request.toolName());
        try (McpSession session = openSession(server)) {
            initialize(server, session);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", originalName);
            params.put("arguments", request.arguments());
            Map<?, ?> result = session.request("tools/call", params);
            boolean isError = Boolean.TRUE.equals(result.get("isError"));
            return new ToolCallResult(
                    request.toolName(),
                    !isError,
                    contentText(result),
                    isError ? contentText(result) : "",
                    Map.of(
                            "provider", "mcp",
                            "mcpServerName", server.getServerName(),
                            "mcpToolName", originalName
                    )
            );
        }
        catch (RuntimeException | IOException exception) {
            return new ToolCallResult(
                    request.toolName(),
                    false,
                    "",
                    "MCP tool call failed: " + exception.getClass().getSimpleName(),
                    Map.of("provider", "mcp", "mcpServerName", server.getServerName(), "mcpToolName", originalName)
            );
        }
    }

    private void initialize(McpProperties.Server server, McpSession session) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", server.getProtocolVersion());
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "enterprise-agent", "version", "0.0.1"));
        session.request("initialize", params);
        session.notification("notifications/initialized", Map.of());
    }

    private ToolDefinition toToolDefinition(McpProperties.Server server, Map<?, ?> tool) {
        Object nameValue = tool.get("name");
        String originalName = nameValue == null ? "" : String.valueOf(nameValue);
        String unifiedName = server.getToolNamePrefix() + originalName;
        Object descriptionValue = tool.get("description");
        String description = descriptionValue == null ? "MCP tool: " + originalName : String.valueOf(descriptionValue);
        Object inputSchemaValue = tool.get("inputSchema");
        String inputSchema = toJson(inputSchemaValue == null ? Map.of("type", "object") : inputSchemaValue);
        return new ToolDefinition(
                unifiedName,
                description,
                inputSchema,
                ToolRiskLevel.MEDIUM,
                Map.of(
                        "provider", "mcp",
                        "mcpServerName", server.getServerName(),
                        "mcpToolName", originalName
                )
        );
    }

    private Optional<McpProperties.Server> resolveServer(String toolName) {
        return mcpProperties.effectiveServers().stream()
                .filter(server -> toolName != null && toolName.startsWith(server.getToolNamePrefix()))
                .findFirst();
    }

    private String contentText(Map<?, ?> result) {
        Object contentValue = result.get("content");
        if (!(contentValue instanceof List<?> contentList)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Object itemValue : contentList) {
            if (itemValue instanceof Map<?, ?> item) {
                Object text = item.get("text");
                if (text != null) {
                    parts.add(String.valueOf(text));
                }
            }
        }
        return String.join("\n", parts);
    }

    private String originalToolName(McpProperties.Server server, String toolName) {
        String prefix = server.getToolNamePrefix();
        if (toolName != null && toolName.startsWith(prefix)) {
            return toolName.substring(prefix.length());
        }
        return toolName;
    }

    private McpSession openSession(McpProperties.Server server) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(server.getCommand());
        command.addAll(server.getArgs());
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (server.getWorkingDirectory() != null && !server.getWorkingDirectory().isBlank()) {
            processBuilder.directory(new File(server.getWorkingDirectory()));
        }
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return new McpSession(processBuilder.start(), objectMapper);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            return "{\"type\":\"object\"}";
        }
    }

    private static final class McpSession implements Closeable {

        private final Process process;

        private final ObjectMapper objectMapper;

        private final BufferedReader reader;

        private final BufferedWriter writer;

        private final AtomicInteger ids = new AtomicInteger(1);

        private McpSession(Process process, ObjectMapper objectMapper) {
            this.process = process;
            this.objectMapper = objectMapper;
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        private Map<?, ?> request(String method, Map<String, Object> params) {
            int id = ids.getAndIncrement();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null && !params.isEmpty()) {
                request.put("params", params);
            }
            write(request);
            while (true) {
                Map<?, ?> response = readMessage();
                Object responseId = response.get("id");
                if (responseId != null && String.valueOf(responseId).equals(String.valueOf(id))) {
                    if (response.containsKey("error")) {
                        throw new IllegalStateException("MCP error response: " + response.get("error"));
                    }
                    Object result = response.get("result");
                    if (result instanceof Map<?, ?> resultMap) {
                        return resultMap;
                    }
                    return Map.of();
                }
            }
        }

        private void notification(String method, Map<String, Object> params) {
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            if (params != null && !params.isEmpty()) {
                notification.put("params", params);
            }
            write(notification);
        }

        private void write(Map<String, Object> message) {
            try {
                writer.write(objectMapper.writeValueAsString(message));
                writer.newLine();
                writer.flush();
            }
            catch (IOException exception) {
                throw new IllegalStateException("Failed to write MCP message", exception);
            }
        }

        private Map<?, ?> readMessage() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        Object message = objectMapper.readValue(line, Map.class);
                        if (message instanceof Map<?, ?> map) {
                            return map;
                        }
                    }
                    catch (RuntimeException ignored) {
                        // Some servers may print logs. Skip non-JSON lines.
                    }
                }
                throw new IllegalStateException("MCP server closed stdout");
            }
            catch (IOException exception) {
                throw new IllegalStateException("Failed to read MCP message", exception);
            }
        }

        @Override
        public void close() {
            process.destroy();
        }
    }
}
