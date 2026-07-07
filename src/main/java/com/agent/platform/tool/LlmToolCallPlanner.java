package com.agent.platform.tool;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.llm.LlmService;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.router.IntentRoute;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LlmToolCallPlanner implements ToolCallPlanner {

    private final Pattern ticketIdPattern = Pattern.compile("T\\d{3,}", Pattern.CASE_INSENSITIVE);

    private final Pattern priorityPattern = Pattern.compile("\\bP[0-3]\\b", Pattern.CASE_INSENSITIVE);

    private final LlmService llmService;

    private final ObjectMapper objectMapper;

    public LlmToolCallPlanner(LlmService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolCallPlan plan(AgentRequest request,
                             ConversationMemory memory,
                             IntentRoute route,
                             List<ToolDefinition> availableTools,
                             List<ToolCallResult> previousResults) {
        if (availableTools == null || availableTools.isEmpty()) {
            return ToolCallPlan.noTool("no available tools", "fallback");
        }
        try {
            ToolCallPlan llmPlan = parsePlan(callPlannerModel(request, route, availableTools, previousResults), availableTools);
            if (llmPlan.shouldCallTool()) {
                return llmPlan;
            }
        }
        catch (RuntimeException ignored) {
            // Fall back to deterministic extraction. Tool planning should degrade, not break the agent run.
        }
        return fallbackPlan(request, route, availableTools, previousResults);
    }

    private String callPlannerModel(AgentRequest request,
                                    IntentRoute route,
                                    List<ToolDefinition> availableTools,
                                    List<ToolCallResult> previousResults) {
        String systemPrompt = """
                你是 Agent 的工具调用规划器。
                你只能输出一个 JSON 对象，不能输出 Markdown，不能解释。
                JSON 格式：
                {
                  "needsTool": true,
                  "toolName": "工具名",
                  "arguments": {"参数名": "参数值"},
                  "reason": "为什么选择该工具",
                  "confidence": 0.0
                }
                如果不需要继续调用工具，输出：
                {"needsTool": false, "reason": "原因", "confidence": 0.0}
                只能选择可用工具列表中的工具名，参数必须符合对应 inputSchema。
                """.strip();

        List<String> contextBlocks = new ArrayList<>();
        contextBlocks.add("routeType=" + route.type() + ", routeReason=" + route.reason() + ", routeSlots=" + route.slots());
        contextBlocks.add("availableTools=\n" + formatTools(availableTools));
        if (previousResults != null && !previousResults.isEmpty()) {
            contextBlocks.add("previousToolResults=\n" + formatPreviousResults(previousResults));
        }
        return llmService.complete(new PromptRequest(
                systemPrompt,
                "用户问题：" + request.question(),
                contextBlocks,
                Map.of("purpose", "tool_planning")
        ));
    }

    private ToolCallPlan parsePlan(String modelOutput, List<ToolDefinition> availableTools) {
        String json = extractJsonObject(modelOutput);
        Map<?, ?> raw = objectMapper.readValue(json, Map.class);
        boolean needsTool = booleanValue(raw.get("needsTool"), true);
        if (!needsTool) {
            return ToolCallPlan.noTool(stringValue(raw.get("reason"), "model decided no more tool"), "llm");
        }
        String toolName = stringValue(raw.get("toolName"), "");
        if (!isKnownTool(toolName, availableTools)) {
            return ToolCallPlan.noTool("model selected unknown tool: " + toolName, "llm");
        }
        Map<String, Object> arguments = objectMap(raw.get("arguments"));
        return new ToolCallPlan(
                true,
                toolName,
                arguments,
                stringValue(raw.get("reason"), "model selected tool"),
                doubleValue(raw.get("confidence"), 0.5),
                "llm"
        );
    }

    private ToolCallPlan fallbackPlan(AgentRequest request,
                                      IntentRoute route,
                                      List<ToolDefinition> availableTools,
                                      List<ToolCallResult> previousResults) {
        String question = request.question() == null ? "" : request.question();
        String lower = question.toLowerCase(Locale.ROOT);

        Optional<ToolCallPlan> followUp = fallbackFollowUpPlan(question, availableTools, previousResults);
        if (followUp.isPresent()) {
            return followUp.get();
        }
        if (previousResults != null && !previousResults.isEmpty()) {
            return ToolCallPlan.noTool("previous tool result is enough for final answer", "fallback");
        }

        String hintedTool = String.valueOf(route.slots().getOrDefault("toolName", ""));
        String toolName = isKnownTool(hintedTool, availableTools) ? hintedTool : inferToolName(lower, availableTools);
        if (toolName == null || toolName.isBlank()) {
            return ToolCallPlan.noTool("fallback could not infer tool", "fallback");
        }
        return new ToolCallPlan(
                true,
                toolName,
                fallbackArguments(toolName, question, request.metadata()),
                "fallback extracted tool call from user question",
                0.55,
                "fallback"
        );
    }

    private Optional<ToolCallPlan> fallbackFollowUpPlan(String question,
                                                       List<ToolDefinition> availableTools,
                                                       List<ToolCallResult> previousResults) {
        if (previousResults == null || previousResults.isEmpty()) {
            return Optional.empty();
        }
        boolean alreadyUpdated = previousResults.stream().anyMatch(result -> "ticket_priority_update".equals(result.toolName()));
        boolean hasStatus = previousResults.stream().anyMatch(result -> "ticket_status".equals(result.toolName()) && result.success());
        if (!alreadyUpdated
                && hasStatus
                && containsAny(question, List.of("升级", "优先级", "调整为", "改成"))
                && isKnownTool("ticket_priority_update", availableTools)) {
            return Optional.of(new ToolCallPlan(
                    true,
                    "ticket_priority_update",
                    Map.of("ticketId", extractTicketId(question), "priority", extractPriority(question, "P1")),
                    "fallback detected follow-up priority update after status query",
                    0.6,
                    "fallback"
            ));
        }
        return Optional.empty();
    }

    private String inferToolName(String lowerQuestion, List<ToolDefinition> availableTools) {
        if (lowerQuestion.contains("mcp") && lowerQuestion.contains("工单")) {
            String ticketAction = inferTicketAction(lowerQuestion);
            Optional<String> mcpTicketTool = firstToolNamed(availableTools, "mcp.ticket." + ticketAction);
            if (mcpTicketTool.isPresent()) {
                return mcpTicketTool.get();
            }
        }
        if (containsAny(lowerQuestion, List.of("关闭", "结束", "close")) && isKnownTool("ticket_close", availableTools)) {
            return "ticket_close";
        }
        if (containsAny(lowerQuestion, List.of("创建", "新建", "报修", "create")) && isKnownTool("ticket_create", availableTools)) {
            return "ticket_create";
        }
        if (containsAny(lowerQuestion, List.of("升级", "优先级", "priority")) && isKnownTool("ticket_priority_update", availableTools)) {
            return "ticket_priority_update";
        }
        if (containsAny(lowerQuestion, List.of("读取文件", "查看文件", "read file"))) {
            return firstToolContaining(availableTools, "read_file").orElse(null);
        }
        if (containsAny(lowerQuestion, List.of("列出目录", "查看目录", "list directory"))) {
            return firstToolContaining(availableTools, "list_directory").orElse(null);
        }
        if (isKnownTool("ticket_status", availableTools)) {
            return "ticket_status";
        }
        return null;
    }

    private String inferTicketAction(String lowerQuestion) {
        if (containsAny(lowerQuestion, List.of("关闭", "结束", "close"))) {
            return "ticket_close";
        }
        if (containsAny(lowerQuestion, List.of("创建", "新建", "报修", "create"))) {
            return "ticket_create";
        }
        if (containsAny(lowerQuestion, List.of("升级", "优先级", "priority"))) {
            return "ticket_priority_update";
        }
        return "ticket_status";
    }

    private Map<String, Object> fallbackArguments(String toolName, String question, Map<String, Object> metadata) {
        if ("ticket_create".equals(toolName)) {
            return Map.of("title", blankToDefault(question, "用户问题待处理"), "priority", extractPriority(question, "P2"));
        }
        if ("ticket_priority_update".equals(toolName)) {
            return Map.of("ticketId", extractTicketId(question), "priority", extractPriority(question, "P1"));
        }
        if ("ticket_close".equals(toolName)) {
            return Map.of("ticketId", extractTicketId(question), "closeReason", blankToDefault(question, "用户请求关闭"));
        }
        if (toolName.contains("read_file") || toolName.contains("list_directory")) {
            return Map.of("path", extractPath(question, metadata));
        }
        return Map.of("ticketId", extractTicketId(question));
    }

    private String formatTools(List<ToolDefinition> availableTools) {
        StringBuilder builder = new StringBuilder();
        for (ToolDefinition tool : availableTools) {
            builder.append("- name: ").append(tool.name()).append('\n')
                    .append("  description: ").append(tool.description()).append('\n')
                    .append("  riskLevel: ").append(tool.riskLevel()).append('\n')
                    .append("  inputSchema: ").append(tool.inputSchema()).append('\n');
        }
        return builder.toString();
    }

    private String formatPreviousResults(List<ToolCallResult> previousResults) {
        StringBuilder builder = new StringBuilder();
        for (ToolCallResult result : previousResults) {
            builder.append("- toolName: ").append(result.toolName()).append('\n')
                    .append("  success: ").append(result.success()).append('\n')
                    .append("  content: ").append(result.content()).append('\n')
                    .append("  error: ").append(result.errorMessage()).append('\n');
        }
        return builder.toString();
    }

    private String extractJsonObject(String text) {
        if (text == null) {
            throw new IllegalArgumentException("model output is empty");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("model output does not contain JSON object");
        }
        return text.substring(start, end + 1);
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            }
            catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private boolean isKnownTool(String toolName, List<ToolDefinition> availableTools) {
        return toolName != null && availableTools.stream().anyMatch(tool -> tool.name().equals(toolName));
    }

    private Optional<String> firstToolContaining(List<ToolDefinition> availableTools, String keyword) {
        return availableTools.stream()
                .map(ToolDefinition::name)
                .filter(name -> name.contains(keyword))
                .findFirst();
    }

    private Optional<String> firstToolNamed(List<ToolDefinition> availableTools, String toolName) {
        return availableTools.stream()
                .map(ToolDefinition::name)
                .filter(name -> name.equals(toolName))
                .findFirst();
    }

    private String extractTicketId(String question) {
        Matcher matcher = ticketIdPattern.matcher(question == null ? "" : question);
        if (matcher.find()) {
            return matcher.group().toUpperCase(Locale.ROOT);
        }
        return "T1001";
    }

    private String extractPriority(String question, String defaultPriority) {
        Matcher matcher = priorityPattern.matcher(question == null ? "" : question);
        if (matcher.find()) {
            return matcher.group().toUpperCase(Locale.ROOT);
        }
        return defaultPriority;
    }

    private String extractPath(String question, Map<String, Object> metadata) {
        Object path = metadata == null ? null : metadata.get("path");
        if (path instanceof String text && !text.isBlank()) {
            return text;
        }
        Matcher quoted = Pattern.compile("[\"'“”](.*?)[\"'“”]").matcher(question == null ? "" : question);
        if (quoted.find()) {
            return quoted.group(1);
        }
        return "data/mcp-sandbox";
    }

    private boolean containsAny(String text, List<String> keywords) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
