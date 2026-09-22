package com.agent.platform.mcp;

import com.agent.platform.config.McpProperties;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@Service
@ConditionalOnProperty(prefix = "enterprise-agent.mcp", name = "enabled", havingValue = "true")
public class StdioMcpToolGateway implements McpToolGateway {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpToolGateway.class);

    private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 5_000L;

    private final ObjectMapper objectMapper;

    private final Duration requestTimeout;

    private final List<ServerRuntime> serverRuntimes;

    private final Map<String, ServerRuntime> serverRuntimesById;

    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public StdioMcpToolGateway(McpProperties mcpProperties, ObjectMapper objectMapper) {
        this(mcpProperties, objectMapper, Duration.ofMillis(DEFAULT_REQUEST_TIMEOUT_MILLIS));
    }

    /**
     * 测试可以缩短 request timeout；生产配置仍保持现有 McpProperties 契约不变。
     */
    StdioMcpToolGateway(McpProperties mcpProperties, ObjectMapper objectMapper, Duration requestTimeout) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }

        List<ServerRuntime> runtimes = new ArrayList<>();
        Map<String, ServerRuntime> byId = new LinkedHashMap<>();
        List<McpProperties.Server> configuredServers = mcpProperties == null
                ? List.of()
                : mcpProperties.effectiveServers();
        for (int index = 0; index < configuredServers.size(); index++) {
            ServerSpec specification = ServerSpec.from(configuredServers.get(index), index, byId.keySet());
            ServerRuntime runtime = new ServerRuntime(specification);
            runtimes.add(runtime);
            byId.put(specification.serverId(), runtime);
        }
        this.serverRuntimes = List.copyOf(runtimes);
        this.serverRuntimesById = Map.copyOf(byId);
    }

    /**
     * 寻找服务商的工具。每个 Server 第一次被需要时才启动自己的 stdio session。
     */
    @Override
    public List<ToolDefinition> discoverTools() {
        if (shuttingDown.get()) {
            return List.of();
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        for (ServerRuntime runtime : serverRuntimes) {
            try {
                definitions.addAll(runtime.discover());
            }
            catch (RuntimeException exception) {
                log.warn("MCP discovery failed serverId={} reason={}",
                        runtime.specification.serverId(), exception.getClass().getSimpleName());
            }
        }
        return List.copyOf(definitions);
    }

    /**
     * 只刷新已经 READY 的 session；刷新失败由各 Server 自己隔离，不在这里隐式重连。
     */
    @Override
    public List<ToolDefinition> refreshTools() {
        if (shuttingDown.get()) {
            return List.of();
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        for (ServerRuntime runtime : serverRuntimes) {
            try {
                definitions.addAll(runtime.refresh());
            }
            catch (RuntimeException exception) {
                log.warn("MCP refresh failed serverId={} reason={}",
                        runtime.specification.serverId(), exception.getClass().getSimpleName());
            }
        }
        return List.copyOf(definitions);
    }

    /**
     * 兼容旧调用方：先建立一次新的 definition resolution，再使用其稳定 binding。
     */
    @Override
    public ToolCallResult callTool(ToolCallRequest request) {
        if (request == null || request.toolName() == null || request.toolName().isBlank()) {
            return failure("", "MCP tool name must not be blank", Map.of("provider", "mcp"));
        }
        List<ToolDefinition> matches;
        try {
            matches = discoverTools().stream()
                    .filter(definition -> definition.name().equals(request.toolName()))
                    .toList();
        }
        catch (RuntimeException exception) {
            return failure(request.toolName(), "MCP tool resolution failed", Map.of("provider", "mcp"));
        }
        if (matches.isEmpty()) {
            return failure(request.toolName(), "No MCP tool matched: " + request.toolName(), Map.of("provider", "mcp"));
        }
        if (matches.size() > 1) {
            return failure(request.toolName(), "Ambiguous MCP tool: " + request.toolName(),
                    Map.of("provider", "mcp", "bindingFailure", "duplicate-tool-name"));
        }
        return callTool(matches.get(0), request);
    }

    /**
     * 正常执行路径必须使用 registry 已经解析出的 definition，不得重新按 prefix 猜 Server。
     */
    @Override
    public ToolCallResult callTool(ToolDefinition definition, ToolCallRequest request) {
        String toolName = request == null ? definition == null ? "" : definition.name() : request.toolName();
        if (definition == null || request == null) {
            return failure(toolName, "MCP tool definition and request are required", Map.of("provider", "mcp"));
        }
        if (!definition.name().equals(request.toolName())) {
            return failure(toolName, "MCP tool definition does not match request", Map.of(
                    "provider", "mcp", "bindingFailure", "definition-request-mismatch"));
        }

        Optional<ToolBinding> binding = ToolBinding.from(definition);
        if (binding.isEmpty()) {
            return failure(toolName, "MCP tool definition has no stable binding", Map.of(
                    "provider", "mcp", "bindingFailure", "missing-binding"));
        }
        ToolBinding resolved = binding.get();
        ServerRuntime runtime = serverRuntimesById.get(resolved.serverId());
        if (runtime == null || !resolved.matches(runtime.specification, definition)) {
            return failure(toolName, "MCP tool definition binding is invalid", bindingMetadata(resolved, null, "invalid-binding"));
        }
        McpSession session = runtime.sessionFor(resolved.generation());
        if (session == null) {
            return failure(toolName, "MCP tool definition binding is stale or session unavailable",
                    bindingMetadata(resolved, runtime.specification, "stale-session"));
        }
        if (!session.containsDefinition(definition)) {
            return failure(toolName, "MCP tool definition is not from the current session snapshot",
                    bindingMetadata(resolved, runtime.specification, "definition-not-in-snapshot"));
        }
        return executeBound(runtime, session, resolved, request);
    }

    private ToolCallResult executeBound(ServerRuntime runtime, McpSession session,
                                        ToolBinding binding, ToolCallRequest request) {
        Map<String, Object> metadata = bindingMetadata(binding, runtime.specification, null);
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", binding.originalToolName());
            params.put("arguments", request.arguments());
            Map<?, ?> result = session.request("tools/call", params);
            String content = contentText(result);
            boolean isError = Boolean.TRUE.equals(result.get("isError"));
            return new ToolCallResult(request.toolName(), !isError, content, isError ? content : "", metadata);
        }
        catch (McpRequestTimeoutException exception) {
            return failure(request.toolName(), "MCP tool call timed out", metadata);
        }
        catch (McpProtocolException exception) {
            return failure(request.toolName(), "MCP tool call returned a protocol error", metadata);
        }
        catch (McpTransportException exception) {
            runtime.invalidate(session, exception.getClass().getSimpleName());
            return failure(request.toolName(), "MCP transport failed", metadata);
        }
        catch (McpInterruptedException exception) {
            return failure(request.toolName(), "MCP tool call interrupted", metadata);
        }
        catch (RuntimeException exception) {
            runtime.invalidate(session, exception.getClass().getSimpleName());
            return failure(request.toolName(), "MCP tool call failed", metadata);
        }
    }

    private void initialize(ServerSpec server, McpSession session) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", server.protocolVersion());
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "enterprise-agent", "version", "0.0.1"));
        session.request("initialize", params);
        session.notification("notifications/initialized", Map.of());
    }

    private List<ToolDefinition> loadToolSnapshot(ServerSpec server, McpSession session) {
        Map<?, ?> result = session.request("tools/list", Map.of());
        Object toolsValue = result.get("tools");
        if (!(toolsValue instanceof List<?> tools)) {
            throw new McpProtocolException("tools/list result does not contain tools");
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Object toolValue : tools) {
            if (!(toolValue instanceof Map<?, ?> tool)) {
                throw new McpProtocolException("tools/list contains a non-object tool");
            }
            definitions.add(toToolDefinition(server, session.generation(), tool));
        }
        return List.copyOf(definitions);
    }

    private ToolDefinition toToolDefinition(ServerSpec server, long generation, Map<?, ?> tool) {
        Object nameValue = tool.get("name");
        String originalName = nameValue == null ? "" : String.valueOf(nameValue);
        if (originalName.isBlank()) {
            throw new McpProtocolException("tools/list contains a blank tool name");
        }
        String unifiedName = server.toolNamePrefix() + originalName;
        Object descriptionValue = tool.get("description");
        String description = descriptionValue == null ? "MCP 工具：" + originalName : String.valueOf(descriptionValue);
        Object inputSchemaValue = tool.get("inputSchema");
        String inputSchema = toJson(inputSchemaValue == null ? Map.of("type", "object") : inputSchemaValue);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "mcp");
        metadata.put("mcpServerName", server.serverName());
        metadata.put("mcpServerId", server.serverId());
        metadata.put("mcpToolName", originalName);
        metadata.put("mcpSessionGeneration", generation);
        metadata.put("mcpBindingId", server.serverId() + "/" + generation + "/" + originalName);
        return new ToolDefinition(unifiedName, description, inputSchema, ToolRiskLevel.MEDIUM, metadata);
    }

    private McpSession openSession(ServerSpec server, long generation,
                                   BiConsumer<McpSession, String> transportFailure) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(server.command());
        command.addAll(server.args());
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (!server.workingDirectory().isBlank()) {
            processBuilder.directory(new File(server.workingDirectory()));
        }
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = processBuilder.start();
        log.info("MCP server connected serverId={} generation={}", server.serverId(), generation);
        return new McpSession(server.serverId(), generation, process, objectMapper, requestTimeout, transportFailure);
    }

    @PreDestroy
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        for (ServerRuntime runtime : serverRuntimes) {
            try {
                runtime.close();
            }
            catch (RuntimeException exception) {
                log.warn("MCP shutdown failed serverId={} reason={}",
                        runtime.specification.serverId(), exception.getClass().getSimpleName());
            }
        }
    }

    private boolean isShuttingDown() {
        return shuttingDown.get();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            return "{\"type\":\"object\"}";
        }
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

    private ToolCallResult failure(String toolName, String message, Map<String, Object> metadata) {
        return new ToolCallResult(toolName, false, "", message, metadata);
    }

    private Map<String, Object> bindingMetadata(ToolBinding binding, ServerSpec server, String failure) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "mcp");
        if (server != null) {
            metadata.put("mcpServerName", server.serverName());
        }
        if (binding != null) {
            metadata.put("mcpServerId", binding.serverId());
            metadata.put("mcpToolName", binding.originalToolName());
            metadata.put("mcpSessionGeneration", binding.generation());
        }
        if (failure != null) {
            metadata.put("bindingFailure", failure);
        }
        return Map.copyOf(metadata);
    }

    private final class ServerRuntime {

        private final ServerSpec specification;

        private final Object lifecycleLock = new Object();

        private final Object refreshLock = new Object();

        private long nextGeneration;

        private McpSession activeSession;

        private ServerRuntime(ServerSpec specification) {
            this.specification = specification;
        }

        private List<ToolDefinition> discover() {
            synchronized (lifecycleLock) {
                if (isReady(activeSession)) {
                    return activeSession.snapshot();
                }
                if (isShuttingDown()) {
                    return List.of();
                }
                return createSessionLocked();
            }
        }

        private List<ToolDefinition> refresh() {
            synchronized (refreshLock) {
                McpSession session;
                synchronized (lifecycleLock) {
                    if (isShuttingDown() || !isReady(activeSession)) {
                        return List.of();
                    }
                    session = activeSession;
                }

                try {
                    List<ToolDefinition> refreshed = loadToolSnapshot(specification, session);
                    synchronized (lifecycleLock) {
                        if (isShuttingDown()
                                || activeSession != session
                                || !isReady(session)) {
                            return List.of();
                        }
                        session.publishRefresh(refreshed);
                        return session.snapshot();
                    }
                }
                catch (McpProtocolException | McpRequestTimeoutException | McpInterruptedException exception) {
                    return currentSnapshot(session);
                }
                catch (McpTransportException exception) {
                    invalidate(session, exception.getClass().getSimpleName());
                    return List.of();
                }
                catch (RuntimeException exception) {
                    return currentSnapshot(session);
                }
            }
        }

        private List<ToolDefinition> currentSnapshot(McpSession session) {
            synchronized (lifecycleLock) {
                return activeSession == session && isReady(session) ? session.snapshot() : List.of();
            }
        }

        private List<ToolDefinition> createSessionLocked() {
            if (isShuttingDown()) {
                return List.of();
            }
            McpSession session = null;
            try {
                long generation = ++nextGeneration;
                session = openSession(specification, generation, this::invalidate);
                activeSession = session;
                initialize(specification, session);
                List<ToolDefinition> snapshot = loadToolSnapshot(specification, session);
                session.initializeSnapshot(snapshot);
                session.markReady();
                log.info("MCP server initialized serverId={} generation={} toolCount={}",
                        specification.serverId(), generation, snapshot.size());
                return session.snapshot();
            }
            catch (IOException exception) {
                if (activeSession == session) {
                    activeSession = null;
                }
                closeQuietly(session);
                throw new McpTransportException("MCP process could not start", exception);
            }
            catch (RuntimeException exception) {
                if (activeSession == session) {
                    activeSession = null;
                }
                closeQuietly(session);
                throw exception;
            }
        }

        private McpSession sessionFor(long generation) {
            synchronized (lifecycleLock) {
                return isReady(activeSession) && activeSession.generation() == generation ? activeSession : null;
            }
        }

        private void invalidate(McpSession session, String reason) {
            synchronized (lifecycleLock) {
                invalidateLocked(session, reason);
            }
        }

        private void invalidateLocked(McpSession session, String reason) {
            if (activeSession != session) {
                return;
            }
            activeSession = null;
            log.warn("MCP session invalidated serverId={} generation={} reason={}",
                    specification.serverId(), session.generation(), reason);
            session.close();
        }

        private void close() {
            McpSession session;
            synchronized (lifecycleLock) {
                session = activeSession;
                activeSession = null;
            }
            if (session != null) {
                session.close();
            }
        }

        private boolean isReady(McpSession session) {
            return session != null && session.isReady();
        }

        private void closeQuietly(McpSession session) {
            if (session != null) {
                session.close();
            }
        }
    }

    private enum SessionState {
        STARTING,
        READY,
        INVALID,
        CLOSED
    }

    private static final class McpSession implements Closeable {

        private final String serverId;

        private final long generation;

        private final Process process;

        private final ObjectMapper objectMapper;

        private final Duration requestTimeout;

        private final BiConsumer<McpSession, String> transportFailure;

        private final BufferedReader reader;

        private final BufferedWriter writer;

        private final AtomicInteger ids = new AtomicInteger(1);

        private final Map<Integer, CompletableFuture<Map<?, ?>>> pending = new ConcurrentHashMap<>();

        private final Object writerLock = new Object();

        private final AtomicBoolean transportFailed = new AtomicBoolean();

        private final AtomicBoolean closed = new AtomicBoolean();

        private final Object snapshotLock = new Object();

        private final Thread readerThread;

        private volatile SessionState state = SessionState.STARTING;

        private volatile List<ToolDefinition> snapshot = List.of();

        private volatile List<ToolDefinition> publishedDefinitions = List.of();

        private volatile boolean snapshotInitialized;

        private McpSession(String serverId, long generation, Process process, ObjectMapper objectMapper,
                           Duration requestTimeout, BiConsumer<McpSession, String> transportFailure) {
            this.serverId = serverId;
            this.generation = generation;
            this.process = process;
            this.objectMapper = objectMapper;
            this.requestTimeout = requestTimeout;
            this.transportFailure = transportFailure;
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.readerThread = new Thread(this::readLoop, "mcp-stdio-reader-" + serverId + "-" + generation);
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        private long generation() {
            return generation;
        }

        private List<ToolDefinition> snapshot() {
            return snapshot;
        }

        private boolean containsDefinition(ToolDefinition definition) {
            return publishedDefinitions.contains(definition);
        }

        private void initializeSnapshot(List<ToolDefinition> definitions) {
            List<ToolDefinition> immutableSnapshot = List.copyOf(definitions);
            synchronized (snapshotLock) {
                if (state != SessionState.STARTING) {
                    throw new IllegalStateException("MCP tool snapshot can only be initialized while starting");
                }
                if (snapshotInitialized) {
                    throw new IllegalStateException("MCP tool snapshot is already initialized");
                }
                snapshot = immutableSnapshot;
                publishedDefinitions = immutableSnapshot;
                snapshotInitialized = true;
            }
        }

        private void publishRefresh(List<ToolDefinition> definitions) {
            List<ToolDefinition> immutableSnapshot = List.copyOf(definitions);
            synchronized (snapshotLock) {
                if (state != SessionState.READY || !snapshotInitialized) {
                    throw new IllegalStateException("MCP tool snapshot can only be refreshed while ready");
                }
                List<ToolDefinition> published = new ArrayList<>(publishedDefinitions);
                for (ToolDefinition definition : immutableSnapshot) {
                    if (!published.contains(definition)) {
                        published.add(definition);
                    }
                }
                publishedDefinitions = List.copyOf(published);
                snapshot = immutableSnapshot;
            }
        }

        private void markReady() {
            if (!snapshotInitialized) {
                throw new IllegalStateException("MCP tool snapshot must be initialized before ready");
            }
            if (!closed.get() && !transportFailed.get()) {
                state = SessionState.READY;
            }
        }

        private boolean isReady() {
            return state == SessionState.READY && !closed.get() && !transportFailed.get();
        }

        private Map<?, ?> request(String method, Map<String, Object> params) {
            if (closed.get() || transportFailed.get()) {
                throw new McpTransportException("MCP session is unavailable");
            }
            int id = ids.getAndIncrement();
            CompletableFuture<Map<?, ?>> response = new CompletableFuture<>();
            pending.put(id, response);
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null && !params.isEmpty()) {
                request.put("params", params);
            }
            try {
                write(request);
            }
            catch (RuntimeException exception) {
                pending.remove(id, response);
                throw exception;
            }
            try {
                long timeoutMillis = Math.max(1L, requestTimeout.toMillis());
                return response.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException exception) {
                pending.remove(id, response);
                throw new McpRequestTimeoutException("MCP request timed out");
            }
            catch (InterruptedException exception) {
                pending.remove(id, response);
                Thread.currentThread().interrupt();
                throw new McpInterruptedException("MCP request interrupted", exception);
            }
            catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new McpTransportException("MCP request failed", cause);
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
            synchronized (writerLock) {
                if (closed.get() || transportFailed.get()) {
                    throw new McpTransportException("MCP session is unavailable");
                }
                try {
                    writer.write(objectMapper.writeValueAsString(message));
                    writer.newLine();
                    writer.flush();
                }
                catch (Exception exception) {
                    McpTransportException failure = new McpTransportException("Failed to write MCP message", exception);
                    failTransport(failure);
                    throw failure;
                }
            }
        }

        private void readLoop() {
            try {
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    handleLine(line);
                }
                if (!closed.get()) {
                    failTransport(new McpTransportException("MCP server closed stdout"));
                }
            }
            catch (IOException exception) {
                if (!closed.get()) {
                    failTransport(new McpTransportException("Failed to read MCP message", exception));
                }
            }
        }

        private void handleLine(String line) {
            if (line == null || line.isBlank()) {
                return;
            }
            Map<?, ?> message;
            try {
                Object parsed = objectMapper.readValue(line, Map.class);
                if (!(parsed instanceof Map<?, ?> map)) {
                    return;
                }
                message = map;
            }
            catch (Exception ignored) {
                // 兼容少数把日志写到 stdout 的 MCP Server；非 JSON 行不是 response。
                return;
            }
            Integer id = requestId(message.get("id"));
            if (id == null) {
                return;
            }
            CompletableFuture<Map<?, ?>> response = pending.remove(id);
            if (response == null) {
                // timeout 后到达的 late response 必须被丢弃，不能污染后续 request。
                return;
            }
            if (message.containsKey("error")) {
                response.completeExceptionally(new McpProtocolException("MCP error response"));
                return;
            }
            Object result = message.get("result");
            if (result instanceof Map<?, ?> resultMap) {
                response.complete(resultMap);
            }
            else {
                response.completeExceptionally(new McpProtocolException("MCP response result is not an object"));
            }
        }

        private Integer requestId(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text) {
                try {
                    return Integer.valueOf(text);
                }
                catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private void failTransport(McpTransportException failure) {
            if (closed.get() || !transportFailed.compareAndSet(false, true)) {
                return;
            }
            state = SessionState.INVALID;
            pending.forEach((id, response) -> {
                if (pending.remove(id, response)) {
                    response.completeExceptionally(failure);
                }
            });
            try {
                transportFailure.accept(this, failure.getClass().getSimpleName());
            }
            catch (RuntimeException ignored) {
                // failure cleanup must not terminate the reader loop before the process is closed.
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            state = SessionState.CLOSED;
            McpTransportException failure = new McpTransportException("MCP session closed");
            pending.forEach((id, response) -> {
                if (pending.remove(id, response)) {
                    response.completeExceptionally(failure);
                }
            });
            synchronized (writerLock) {
                try {
                    writer.close();
                }
                catch (IOException ignored) {
                    // process cleanup continues below
                }
            }
            try {
                reader.close();
            }
            catch (IOException ignored) {
                // process cleanup continues below
            }
            process.destroy();
            waitForProcess(250L);
            if (process.isAlive()) {
                process.destroyForcibly();
                waitForProcess(250L);
            }
            readerThread.interrupt();
            log.info("MCP session closed serverId={} generation={}", serverId, generation);
        }

        private void waitForProcess(long millis) {
            try {
                process.waitFor(millis, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record ServerSpec(
            String serverId,
            String serverName,
            String toolNamePrefix,
            String protocolVersion,
            String command,
            List<String> args,
            String workingDirectory
    ) {

        private static ServerSpec from(McpProperties.Server server, int index, java.util.Set<String> usedIds) {
            String configuredName = valueOrEmpty(server == null ? null : server.getServerName());
            String baseId = configuredName.isBlank() ? "server-" + (index + 1) : configuredName;
            String serverId = baseId;
            int suffix = 2;
            while (usedIds.contains(serverId)) {
                serverId = baseId + "#" + suffix++;
            }
            String serverName = configuredName.isBlank() ? serverId : configuredName;
            List<String> args = server == null || server.getArgs() == null
                    ? List.of()
                    : server.getArgs().stream().filter(Objects::nonNull).toList();
            return new ServerSpec(
                    serverId,
                    serverName,
                    valueOrEmpty(server == null ? null : server.getToolNamePrefix()),
                    valueOrDefault(server == null ? null : server.getProtocolVersion(), "2025-11-25"),
                    valueOrEmpty(server == null ? null : server.getCommand()),
                    List.copyOf(args),
                    valueOrEmpty(server == null ? null : server.getWorkingDirectory())
            );
        }

        private static String valueOrEmpty(String value) {
            return value == null ? "" : value;
        }

        private static String valueOrDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private record ToolBinding(String serverId, long generation, String originalToolName) {

        private static Optional<ToolBinding> from(ToolDefinition definition) {
            Map<String, Object> metadata = definition.metadata();
            Object serverId = metadata.get("mcpServerId");
            Object generation = metadata.get("mcpSessionGeneration");
            Object originalName = metadata.get("mcpToolName");
            if (serverId == null || originalName == null || generation == null) {
                return Optional.empty();
            }
            long generationValue;
            try {
                generationValue = generation instanceof Number number
                        ? number.longValue()
                        : Long.parseLong(String.valueOf(generation));
            }
            catch (NumberFormatException exception) {
                return Optional.empty();
            }
            String serverIdValue = String.valueOf(serverId);
            String originalNameValue = String.valueOf(originalName);
            if (serverIdValue.isBlank() || originalNameValue.isBlank() || generationValue <= 0) {
                return Optional.empty();
            }
            return Optional.of(new ToolBinding(serverIdValue, generationValue, originalNameValue));
        }

        private boolean matches(ServerSpec server, ToolDefinition definition) {
            return serverId.equals(server.serverId())
                    && definition.name().equals(server.toolNamePrefix() + originalToolName)
                    && "mcp".equals(String.valueOf(definition.metadata().get("provider")))
                    && originalToolName.equals(String.valueOf(definition.metadata().get("mcpToolName")));
        }
    }

    private static final class McpTransportException extends RuntimeException {

        private McpTransportException(String message) {
            super(message);
        }

        private McpTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class McpProtocolException extends RuntimeException {

        private McpProtocolException(String message) {
            super(message);
        }
    }

    private static final class McpRequestTimeoutException extends RuntimeException {

        private McpRequestTimeoutException(String message) {
            super(message);
        }
    }

    private static final class McpInterruptedException extends RuntimeException {

        private McpInterruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
