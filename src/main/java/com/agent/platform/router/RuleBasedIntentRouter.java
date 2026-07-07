package com.agent.platform.router;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class RuleBasedIntentRouter implements IntentRouter {

    private final Pattern ticketIdPattern = Pattern.compile("T\\d{3,}", Pattern.CASE_INSENSITIVE);

    private final Pattern incidentLevelPattern = Pattern.compile("\\bP[0-3]\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public IntentRoute route(AgentRequest request, ConversationMemory memory) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isBlank()) {
            return new IntentRoute(IntentType.CLARIFY, "empty question needs clarification", Map.of());
        }
        if (isSmallTalk(question)) {
            return new IntentRoute(IntentType.CHAT, "small talk or general chat detected", routeSlots(0, 0, 0, null));
        }

        int ragScore = ragScore(question);
        int toolScore = toolScore(question);
        int clarifyScore = clarifyScore(question);
        String toolName = toolScore > 0 ? resolveTicketTool(question) : null;
        Map<String, Object> slots = routeSlots(ragScore, toolScore, clarifyScore, toolName);

        if (clarifyScore >= 3 && Math.max(ragScore, toolScore) < 3) {
            return new IntentRoute(IntentType.CLARIFY, "ambiguous request score is higher than actionable intent", slots);
        }
        if (shouldPreferRag(question, ragScore, toolScore)) {
            return new IntentRoute(IntentType.RAG, "knowledge intent wins by score", slots);
        }
        if (toolScore >= 3) {
            return new IntentRoute(IntentType.TOOL, "tool intent wins by score", slots);
        }
        if (ragScore >= 3) {
            return new IntentRoute(IntentType.RAG, "knowledge intent detected by score", slots);
        }
        if (clarifyScore >= 2) {
            return new IntentRoute(IntentType.CLARIFY, "request is too vague for safe execution", slots);
        }
        return new IntentRoute(IntentType.CHAT, "general chat fallback", slots);
    }

    private int ragScore(String question) {
        int score = 0;
        score += containsAny(question, List.of("知识库", "流程", "制度", "规范", "规则", "手册", "说明", "文档")) * 3;
        score += containsAny(question, List.of("RAG", "检索", "向量", "Embedding", "TopK", "chunk")) * 3;
        score += containsAny(question, List.of(
                "退款", "退款审批", "审批", "发布", "生产发布", "应急", "故障", "故障响应",
                "故障应急", "复盘", "回滚", "灰度", "财务复核")) * 3;
        score += containsAny(question, List.of("怎么", "如何", "是什么", "有哪些", "需要哪些", "为什么", "规则是什么", "流程是什么")) * 2;
        if (incidentLevelPattern.matcher(question).find()
                && containsAny(question, List.of("响应", "处理", "应急", "故障")) > 0) {
            score += 3;
        }
        return score;
    }

    private int toolScore(String question) {
        int score = 0;
        score += containsAny(question, List.of("工单", "ticket", "报修")) * 3;
        score += containsAny(question, List.of("查询", "查看", "状态", "创建", "新建", "升级", "关闭", "更新", "优先级")) * 2;
        if (ticketIdPattern.matcher(question).find()) {
            score += 4;
        }
        if (containsAny(question, List.of("创建工单", "新建工单", "报修工单", "查询工单", "升级工单")) > 0) {
            score += 3;
        }
        return score;
    }

    private int clarifyScore(String question) {
        int score = 0;
        score += containsAny(question, List.of("帮我查一下", "查一下", "处理一下", "帮我处理", "看一下")) * 3;
        score += containsAny(question, List.of("不清楚", "什么意思", "这个", "那个")) * 2;
        if (question.length() <= 4 && !isSmallTalk(question)) {
            score += 2;
        }
        return score;
    }

    private boolean shouldPreferRag(String question, int ragScore, int toolScore) {
        if (ragScore < 3) {
            return false;
        }
        if (isKnowledgeQuestionShape(question) && ragScore >= toolScore) {
            return true;
        }
        return ragScore >= toolScore + 2;
    }

    private boolean isKnowledgeQuestionShape(String question) {
        return containsAny(question, List.of("怎么", "如何", "是什么", "有哪些", "需要哪些", "规则", "流程", "规范", "制度", "手册")) > 0;
    }

    private boolean isSmallTalk(String question) {
        String normalized = question.replace("？", "")
                .replace("?", "")
                .replace("！", "")
                .replace("!", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        return normalized.equals("你好")
                || normalized.equals("您好")
                || normalized.equals("hello")
                || normalized.equals("hi")
                || normalized.equals("在吗")
                || normalized.equals("谢谢")
                || normalized.equals("感谢")
                || normalized.contains("你是谁")
                || normalized.contains("你能做什么")
                || normalized.contains("介绍一下你自己");
    }

    private String resolveTicketTool(String question) {
        if (containsAny(question, List.of("创建", "新建", "报修")) > 0) {
            return "ticket_create";
        }
        if (containsAny(question, List.of("升级", "优先级", "P0", "P1")) > 0
                && containsAny(question, List.of("工单", "ticket")) > 0) {
            return "ticket_priority_update";
        }
        return "ticket_status";
    }

    private int containsAny(String question, List<String> keywords) {
        String normalized = question.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    private Map<String, Object> routeSlots(int ragScore, int toolScore, int clarifyScore, String toolName) {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("ragScore", ragScore);
        slots.put("toolScore", toolScore);
        slots.put("clarifyScore", clarifyScore);
        if (toolName != null) {
            slots.put("toolName", toolName);
        }
        return slots;
    }
}
