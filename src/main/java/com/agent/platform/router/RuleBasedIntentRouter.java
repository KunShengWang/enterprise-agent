package com.agent.platform.router;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

@Service
public class RuleBasedIntentRouter implements IntentRouter {

    private final Pattern ticketIdPattern = Pattern.compile("T\\d{3,}", Pattern.CASE_INSENSITIVE);

    @Override
    public IntentRoute route(AgentRequest request, ConversationMemory memory) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isBlank()) {
            return new IntentRoute(IntentType.CLARIFY, "empty question needs clarification", Map.of());
        }
        if (isSmallTalk(question)) {
            return new IntentRoute(IntentType.CHAT, "small talk or general chat detected", Map.of());
        }
        if (isTicketQuestion(question)) {
            return new IntentRoute(IntentType.TOOL, "ticket intent detected", Map.of("toolName", resolveTicketTool(question)));
        }
        if (isKnowledgeQuestion(question)) {
            return new IntentRoute(IntentType.RAG, "knowledge-base question detected", Map.of());
        }
        if (isAmbiguousBusinessRequest(question)) {
            return new IntentRoute(IntentType.CLARIFY, "ambiguous business request needs clarification", Map.of());
        }
        return new IntentRoute(IntentType.CHAT, "general chat fallback", Map.of());
    }

    private boolean isSmallTalk(String question) {
        String normalized = question.replace("？", "")
                .replace("?", "")
                .replace("！", "")
                .replace("!", "")
                .trim()
                .toLowerCase();
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

    private boolean isTicketQuestion(String question) {
        return question.contains("工单")
                || question.contains("报修")
                || question.contains("故障")
                || question.contains("客服")
                || question.toLowerCase().contains("ticket")
                || ticketIdPattern.matcher(question).find();
    }

    private String resolveTicketTool(String question) {
        if (question.contains("创建") || question.contains("新建") || question.contains("报修") || question.contains("故障")) {
            return "ticket_create";
        }
        if (question.contains("升级") || question.contains("优先级") || question.contains("P0") || question.contains("P1")) {
            return "ticket_priority_update";
        }
        return "ticket_status";
    }

    private boolean isKnowledgeQuestion(String question) {
        return question.contains("知识库")
                || question.contains("流程")
                || question.contains("制度")
                || question.contains("RAG")
                || question.contains("报销")
                || question.contains("发布")
                || question.contains("应急")
                || question.contains("培训")
                || question.contains("退款")
                || question.contains("审批");
    }

    private boolean isAmbiguousBusinessRequest(String question) {
        return question.contains("帮我查一下")
                || question.equals("查一下")
                || question.equals("处理一下")
                || question.equals("帮我处理")
                || question.equals("看一下")
                || question.contains("不清楚")
                || question.equals("什么意思")
                || question.equals("这个什么意思");
    }
}
