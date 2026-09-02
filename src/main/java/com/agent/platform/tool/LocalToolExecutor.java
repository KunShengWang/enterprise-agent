package com.agent.platform.tool;

import com.agent.platform.mcp.McpToolGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class LocalToolExecutor implements ToolExecutor {

    private final ToolRegistry toolRegistry;

    private final ToolParameterValidator parameterValidator;

    private final ToolRunRecorder toolRunRecorder;

    private final TicketStore ticketStore;

    private final ObjectProvider<McpToolGateway> mcpToolGatewayProvider;

    private final ObjectProvider<ToolHandler> toolHandlers;

    @Autowired
    public LocalToolExecutor(ToolRegistry toolRegistry,
                             ToolParameterValidator parameterValidator,
                             ToolRunRecorder toolRunRecorder,
                             TicketStore ticketStore,
                             ObjectProvider<McpToolGateway> mcpToolGatewayProvider,
                             ObjectProvider<ToolHandler> toolHandlers) {
        this.toolRegistry = toolRegistry;
        this.parameterValidator = parameterValidator;
        this.toolRunRecorder = toolRunRecorder;
        this.ticketStore = ticketStore;
        this.mcpToolGatewayProvider = mcpToolGatewayProvider;
        this.toolHandlers = toolHandlers;
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request) {
        return execute(request, ToolExecutionContext.empty());
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context) {
        long startNanos = System.nanoTime();
        ToolCallResult result;
        try {
            result = executeInternal(request, context == null ? ToolExecutionContext.empty() : context);
        }
        catch (RuntimeException exception) {
            result = new ToolCallResult(
                    safeToolName(request),
                    false,
                    "",
                    "tool execution failed: " + exception.getClass().getSimpleName(),
                    Map.of("provider", "unknown")
            );
        }
        toolRunRecorder.record(new ToolCallRecord(
                request == null ? "" : request.requestId(),
                safeToolName(request),
                result.success(),
                elapsedMs(startNanos),
                result.errorMessage(),
                request == null ? Map.of() : request.arguments(),
                result.metadata(),
                Instant.now()
        ));
        return result;
    }

    private ToolCallResult executeInternal(ToolCallRequest request, ToolExecutionContext context) {
        // 校验 toolName
        if (request == null || request.toolName() == null || request.toolName().isBlank()) {
            return new ToolCallResult("", false, "", "toolName must not be blank", Map.of("provider", "unknown"));
        }
        // 找 ToolDefinition
        Optional<ToolDefinition> definition = toolRegistry.findTool(request.toolName());
        if (definition.isEmpty()) {
            return new ToolCallResult(request.toolName(), false, "", "unknown tool: " + request.toolName(), Map.of("provider", "unknown"));
        }

        // 参数校验（parameterValidator）
        ToolValidationResult validationResult = parameterValidator.validate(definition.get(), request);
        if (!validationResult.valid()) {
            return new ToolCallResult(request.toolName(), false, "", validationResult.message(), Map.of(
                    "provider", definition.get().metadata().getOrDefault("provider", "unknown"),
                    "validation", "failed"
            ));
        }

        // isMcpTool → MCP gateway
        if (isMcpTool(definition.get())) {
            McpToolGateway gateway = mcpToolGatewayProvider == null ? null : mcpToolGatewayProvider.getIfAvailable();
            if (gateway == null) {
                return new ToolCallResult(request.toolName(), false, "", "MCP gateway is not configured", Map.of("provider", "mcp"));
            }
            return gateway.callTool(definition.get(), request);
        }

        Optional<ToolHandler> businessHandler = toolHandlers.orderedStream()
                .filter(handler -> handler.supports(request.toolName()))
                .findFirst();
        if (businessHandler.isPresent()) {
            ToolHandler handler = businessHandler.get();
            return handler instanceof ContextualToolHandler contextual
                    ? contextual.execute(request, context)
                    : handler.execute(request);
        }

        return switch (request.toolName()) {
            case "ticket_status" -> ticketStatus(request);
            case "ticket_create" -> ticketCreate(request);
            case "ticket_priority_update" -> ticketPriorityUpdate(request);
            case "ticket_close" -> ticketClose(request);
            default -> new ToolCallResult(request.toolName(), false, "", "unknown tool: " + request.toolName(), Map.of());
        };
    }

    private ToolCallResult ticketStatus(ToolCallRequest request) {
        String ticketId = stringArg(request, "ticketId", "T1001");
        Optional<SupportTicket> ticket = ticketStore.findById(ticketId);
        if (ticket.isEmpty()) {
            return new ToolCallResult(request.toolName(), false, "", "工单不存在：" + ticketId, Map.of("provider", "local", "ticketId", ticketId));
        }
        SupportTicket value = ticket.get();
        String content = "工单 " + value.ticketId()
                + " 当前状态为" + value.status()
                + "，优先级 " + value.priority()
                + "，处理人" + value.assignee()
                + "，标题：" + value.title() + "。";
        return new ToolCallResult(request.toolName(), true, content, "", ticketMetadata(value));
    }

    private ToolCallResult ticketCreate(ToolCallRequest request) {
        String title = stringArg(request, "title", "用户问题待处理");
        String priority = stringArg(request, "priority", "P2");
        SupportTicket ticket = ticketStore.create(title, priority);
        String content = "已创建工单 " + ticket.ticketId()
                + "，标题：" + ticket.title()
                + "，优先级 " + ticket.priority()
                + "，当前状态为" + ticket.status() + "。";
        return new ToolCallResult(request.toolName(), true, content, "", ticketMetadata(ticket));
    }

    private ToolCallResult ticketPriorityUpdate(ToolCallRequest request) {
        String ticketId = stringArg(request, "ticketId", "T1001");
        String priority = stringArg(request, "priority", "P1");
        Optional<SupportTicket> ticket = ticketStore.updatePriority(ticketId, priority);
        if (ticket.isEmpty()) {
            return new ToolCallResult(request.toolName(), false, "", "工单不存在：" + ticketId, Map.of("provider", "local", "ticketId", ticketId));
        }
        String content = "工单 " + ticketId + " 已更新优先级为 " + ticket.get().priority() + "。";
        return new ToolCallResult(request.toolName(), true, content, "", ticketMetadata(ticket.get()));
    }

    private ToolCallResult ticketClose(ToolCallRequest request) {
        String ticketId = stringArg(request, "ticketId", "T1001");
        String closeReason = stringArg(request, "closeReason", "用户请求关闭");
        Optional<SupportTicket> ticket = ticketStore.close(ticketId, closeReason);
        if (ticket.isEmpty()) {
            return new ToolCallResult(request.toolName(), false, "", "工单不存在：" + ticketId, Map.of("provider", "local", "ticketId", ticketId));
        }
        String content = "工单 " + ticketId + " 已关闭，关闭原因：" + closeReason + "。";
        Map<String, Object> metadata = new LinkedHashMap<>(ticketMetadata(ticket.get()));
        metadata.put("closeReason", closeReason);
        return new ToolCallResult(request.toolName(), true, content, "", metadata);
    }

    private String stringArg(ToolCallRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return defaultValue;
    }

    private boolean isMcpTool(ToolDefinition definition) {
        return "mcp".equals(String.valueOf(definition.metadata().get("provider")))
                || definition.name().startsWith("mcp.");
    }

    private Map<String, Object> ticketMetadata(SupportTicket ticket) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "local");
        metadata.put("ticketId", ticket.ticketId());
        metadata.put("priority", ticket.priority());
        metadata.put("status", ticket.status());
        metadata.put("assignee", ticket.assignee());
        return metadata;
    }

    private String safeToolName(ToolCallRequest request) {
        return request == null || request.toolName() == null ? "" : request.toolName();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
