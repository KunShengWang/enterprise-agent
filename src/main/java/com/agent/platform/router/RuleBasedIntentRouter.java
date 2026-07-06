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
        if (question.length() < 4 || question.contains("不清楚") || question.contains("什么意思")) {
            return new IntentRoute(IntentType.CLARIFY, "question is too vague", Map.of());
        }
        if (isTicketQuestion(question)) {
            return new IntentRoute(IntentType.TOOL, "ticket intent detected", Map.of("toolName", resolveTicketTool(question)));
        }
        if (isKnowledgeQuestion(question)) {
            return new IntentRoute(IntentType.RAG, "knowledge-base question detected", Map.of());
        }
        return new IntentRoute(IntentType.CHAT, "general chat fallback", Map.of());
    }

    private boolean isTicketQuestion(String question) {
        return question.contains("工单")
                || question.contains("报修")
                || question.contains("故障")
                || ticketIdPattern.matcher(question).find();
    }

    private String resolveTicketTool(String question) {
        if (question.contains("创建") || question.contains("新建") || question.contains("报修") || question.contains("故障")) {
            return "ticket_create";
        }
        if (question.contains("升级") || question.contains("优先级")) {
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
                || question.contains("退款");
    }
}
