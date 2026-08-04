package com.agent.platform.mcp.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TicketMcpServerApplication {

    private static final Pattern METHOD_PATTERN = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|-?\\d+)");

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private static final Map<String, Ticket> TICKETS = new LinkedHashMap<>();

    private static final AtomicInteger SEQUENCE = new AtomicInteger(3000);

    static {
        TICKETS.put("T3001", new Ticket("T3001", "MCP 工单：登录接口超时", "P1", "处理中", "王五"));
        TICKETS.put("T3002", new Ticket("T3002", "MCP 工单：知识库同步失败", "P2", "待处理", "赵六"));
    }

    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String method = extract(METHOD_PATTERN, line, "");
            String id = extract(ID_PATTERN, line, "");
            if (id.isBlank()) {
                continue;
            }
            String response = switch (method) {
                case "initialize" -> initializeResponse(id);
                case "tools/list" -> toolsListResponse(id);
                case "tools/call" -> toolsCallResponse(id, line);
                default -> errorResponse(id, -32601, "Method not found: " + method);
            };
            writer.write(response);
            writer.newLine();
            writer.flush();
        }
    }

    private static String initializeResponse(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{"
                + "\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{\"tools\":{}},"
                + "\"serverInfo\":{\"name\":\"enterprise-ticket-mcp\",\"version\":\"0.1.0\"}"
                + "}}";
    }

    private static String toolsListResponse(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                + tool("ticket_status", "从工单 MCP 服务查询工单状态。",
                "{\"type\":\"object\",\"properties\":{\"ticketId\":{\"type\":\"string\"}},\"required\":[\"ticketId\"]}")
                + ","
                + tool("ticket_create", "通过工单 MCP 服务创建工单。",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"priority\":{\"type\":\"string\",\"enum\":[\"P0\",\"P1\",\"P2\",\"P3\"]}},\"required\":[\"title\"]}")
                + ","
                + tool("ticket_priority_update", "通过工单 MCP 服务更新工单优先级。",
                "{\"type\":\"object\",\"properties\":{\"ticketId\":{\"type\":\"string\"},\"priority\":{\"type\":\"string\",\"enum\":[\"P0\",\"P1\",\"P2\",\"P3\"]}},\"required\":[\"ticketId\",\"priority\"]}")
                + ","
                + tool("ticket_close", "通过工单 MCP 服务关闭工单。",
                "{\"type\":\"object\",\"properties\":{\"ticketId\":{\"type\":\"string\"},\"closeReason\":{\"type\":\"string\"}},\"required\":[\"ticketId\",\"closeReason\"]}")
                + "]}}";
    }

    private static String toolsCallResponse(String id, String request) {
        String toolName = extract(TOOL_NAME_PATTERN, request, "");
        return switch (toolName) {
            case "ticket_status" -> successResponse(id, ticketStatus(request));
            case "ticket_create" -> successResponse(id, ticketCreate(request));
            case "ticket_priority_update" -> successResponse(id, ticketPriorityUpdate(request));
            case "ticket_close" -> successResponse(id, ticketClose(request));
            default -> toolErrorResponse(id, "unknown ticket MCP tool: " + toolName);
        };
    }

    private static String ticketStatus(String request) {
        String ticketId = normalizeTicketId(stringField(request, "ticketId", "T3001"));
        Ticket ticket = TICKETS.get(ticketId);
        if (ticket == null) {
            return "工单不存在：" + ticketId;
        }
        return "MCP 工单 " + ticket.ticketId + " 当前状态为" + ticket.status
                + "，优先级 " + ticket.priority
                + "，处理人" + ticket.assignee
                + "，标题：" + ticket.title + "。";
    }

    private static String ticketCreate(String request) {
        String ticketId = "T" + SEQUENCE.incrementAndGet();
        String title = stringField(request, "title", "MCP 用户问题待处理");
        String priority = normalizePriority(stringField(request, "priority", "P2"));
        Ticket ticket = new Ticket(ticketId, title, priority, "待处理", "MCP-未分配");
        TICKETS.put(ticketId, ticket);
        return "MCP 已创建工单 " + ticketId + "，标题：" + title + "，优先级 " + priority + "，当前状态为待处理。";
    }

    private static String ticketPriorityUpdate(String request) {
        String ticketId = normalizeTicketId(stringField(request, "ticketId", "T3001"));
        String priority = normalizePriority(stringField(request, "priority", "P1"));
        Ticket ticket = TICKETS.get(ticketId);
        if (ticket == null) {
            return "工单不存在：" + ticketId;
        }
        TICKETS.put(ticketId, new Ticket(ticket.ticketId, ticket.title, priority, ticket.status, ticket.assignee));
        return "MCP 工单 " + ticketId + " 已更新优先级为 " + priority + "。";
    }

    private static String ticketClose(String request) {
        String ticketId = normalizeTicketId(stringField(request, "ticketId", "T3001"));
        String closeReason = stringField(request, "closeReason", "MCP 用户请求关闭");
        Ticket ticket = TICKETS.get(ticketId);
        if (ticket == null) {
            return "工单不存在：" + ticketId;
        }
        TICKETS.put(ticketId, new Ticket(ticket.ticketId, ticket.title, ticket.priority, "已关闭", ticket.assignee));
        return "MCP 工单 " + ticketId + " 已关闭，关闭原因：" + closeReason + "。";
    }

    private static String tool(String name, String description, String schema) {
        return "{\"name\":\"" + escape(name)
                + "\",\"description\":\"" + escape(description)
                + "\",\"inputSchema\":" + schema
                + "}";
    }

    private static String successResponse(String id, String text) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
                + escape(text)
                + "\"}],\"isError\":false}}";
    }

    private static String toolErrorResponse(String id, String text) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
                + escape(text)
                + "\"}],\"isError\":true}}";
    }

    private static String errorResponse(String id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code + ",\"message\":\"" + escape(message) + "\"}}";
    }

    private static String stringField(String json, String fieldName, String defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        if (matcher.find() && !matcher.group(1).isBlank()) {
            return unescape(matcher.group(1));
        }
        return defaultValue;
    }

    private static String extract(Pattern pattern, String text, String defaultValue) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : defaultValue;
    }

    private static String normalizeTicketId(String ticketId) {
        return ticketId == null || ticketId.isBlank() ? "T3001" : ticketId.trim().toUpperCase();
    }

    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return "P2";
        }
        String normalized = priority.trim().toUpperCase();
        return switch (normalized) {
            case "P0", "P1", "P2", "P3" -> normalized;
            default -> "P2";
        };
    }

    private static String escape(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String unescape(String value) {
        return value == null ? "" : value
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\\", "\\");
    }

    private record Ticket(
            String ticketId,
            String title,
            String priority,
            String status,
            String assignee,
            Instant updatedAt
    ) {

        private Ticket(String ticketId, String title, String priority, String status, String assignee) {
            this(ticketId, title, priority, status, assignee, Instant.now());
        }
    }
}
