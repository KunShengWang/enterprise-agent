package com.agent.platform.tool;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class LocalToolExecutor implements ToolExecutor {

    @Override
    public ToolCallResult execute(ToolCallRequest request) {
        return switch (request.toolName()) {
            case "ticket_status" -> ticketStatus(request);
            case "ticket_create" -> ticketCreate(request);
            case "ticket_priority_update" -> ticketPriorityUpdate(request);
            default -> new ToolCallResult(request.toolName(), false, "", "unknown tool: " + request.toolName(), Map.of());
        };
    }

    private ToolCallResult ticketStatus(ToolCallRequest request) {
        String ticketId = stringArg(request, "ticketId", "T1001");
        String content = "工单 " + ticketId + " 当前状态为处理中，优先级 P1，处理人张三，预计 2 小时内更新。";
        return new ToolCallResult(request.toolName(), true, content, "", Map.of("ticketId", ticketId));
    }

    private ToolCallResult ticketCreate(ToolCallRequest request) {
        String title = stringArg(request, "title", "用户问题待处理");
        String content = "已创建工单 T2001，标题：" + title + "，优先级 P2，当前状态为待处理。";
        return new ToolCallResult(request.toolName(), true, content, "", Map.of("ticketId", "T2001"));
    }

    private ToolCallResult ticketPriorityUpdate(ToolCallRequest request) {
        String ticketId = stringArg(request, "ticketId", "T1001");
        String priority = stringArg(request, "priority", "P1");
        String content = "工单 " + ticketId + " 已更新优先级为 " + priority + "。";
        return new ToolCallResult(request.toolName(), true, content, "", Map.of("ticketId", ticketId, "priority", priority));
    }

    private String stringArg(ToolCallRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return defaultValue;
    }
}
