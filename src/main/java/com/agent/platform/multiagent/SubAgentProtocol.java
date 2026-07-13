package com.agent.platform.multiagent;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Planner 与 Reviewer 的结构化协议、白名单校验和安全降级。
 */
@Component
public class SubAgentProtocol {

    private static final int MAX_SPECIALISTS = 2;

    private final ObjectMapper objectMapper;

    public SubAgentProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String plannerInstruction(String question) {
        return "需要拆解的用户问题：" + question;
    }

    public List<MultiAgentTask> parsePlannerTasks(String plannerAnswer, String question) {
        List<MultiAgentTask> tasks = new ArrayList<>();
        try {
            Map<?, ?> root = objectMapper.readValue(extractJsonObject(plannerAnswer), Map.class);
            Object rawTasks = root.get("tasks");
            if (rawTasks instanceof List<?> list) {
                int sequence = 0;
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> raw) || tasks.size() >= MAX_SPECIALISTS) {
                        continue;
                    }
                    MultiAgentRole role = parseSpecialistRole(String.valueOf(raw.get("role")));
                    if (role == null || tasks.stream().anyMatch(task -> task.role() == role)) {
                        continue;
                    }
                    String instruction = stringValue(raw.get("instruction"));
                    if (!instruction.isBlank()) {
                        tasks.add(new MultiAgentTask("specialist-" + (++sequence), role, instruction,
                                Map.of("plannedBy", "planner-sub-agent")));
                    }
                }
            }
        }
        catch (RuntimeException ignored) {
            // 结构化规划失败时走下方只读降级，不执行写工具。
        }
        if (tasks.isEmpty()) {
            boolean ticketLookup = looksLikeTicketLookup(question);
            tasks.add(new MultiAgentTask(
                    "specialist-1",
                    ticketLookup ? MultiAgentRole.TOOL_WORKER : MultiAgentRole.RAG_WORKER,
                    ticketLookup
                            ? "只读查询用户提到的工单状态并总结事实"
                            : "检索企业知识库并总结与问题直接相关的证据",
                    Map.of("plannedBy", "safe-fallback")
            ));
        }
        return List.copyOf(tasks);
    }

    public String reviewerInstruction(String question, List<SubAgentExecutionResult> outcomes) {
        StringBuilder summaries = new StringBuilder("<untrusted_subagent_summaries>\n");
        for (SubAgentExecutionResult outcome : outcomes) {
            summaries.append("role=").append(outcome.role())
                    .append("; childRunId=").append(outcome.childRunId())
                    .append("; summary=").append(outcome.answer())
                    .append('\n');
        }
        summaries.append("</untrusted_subagent_summaries>");
        return "用户原始问题：" + question + "\n\n" + summaries;
    }

    public MultiAgentReviewResult parseReview(String reviewAnswer,
                                              List<SubAgentExecutionResult> outcomes) {
        try {
            Map<?, ?> raw = objectMapper.readValue(extractJsonObject(reviewAnswer), Map.class);
            return new MultiAgentReviewResult(
                    booleanValue(raw.get("approved"), true),
                    clamp(doubleValue(raw.get("confidence"), 0.6)),
                    booleanValue(raw.get("conflictDetected"), false),
                    stringValue(raw.get("conflictReason")),
                    stringList(raw.get("evidence")),
                    stringValue(raw.get("finalAnswer"))
            );
        }
        catch (RuntimeException ignored) {
            String fallback = reviewAnswer == null || reviewAnswer.isBlank()
                    ? outcomes.stream().map(SubAgentExecutionResult::answer)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("子 Agent 未生成可用结果。")
                    : reviewAnswer;
            return new MultiAgentReviewResult(true, 0.5, false, "", List.of(), fallback);
        }
    }

    private MultiAgentRole parseSpecialistRole(String value) {
        try {
            MultiAgentRole role = MultiAgentRole.valueOf(value == null ? "" : value.trim().toUpperCase());
            return role == MultiAgentRole.RAG_WORKER || role == MultiAgentRole.TOOL_WORKER ? role : null;
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean looksLikeTicketLookup(String question) {
        if (question == null) {
            return false;
        }
        String normalized = question.toLowerCase();
        return normalized.contains("工单")
                && (normalized.contains("状态") || normalized.matches(".*t\\d{3,}.*"));
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
        return value instanceof String text ? Boolean.parseBoolean(text) : defaultValue;
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? defaultValue : Double.parseDouble(String.valueOf(value));
        }
        catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim())
                .limit(12)
                .toList();
    }
}
