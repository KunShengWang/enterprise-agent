package com.agent.platform.skill;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class StaticSkillSelector implements SkillSelector {

    private final SkillDefinition ticketSkill = new SkillDefinition(
            "ticket-handling",
            "Handle support ticket status query, creation, and priority update.",
            "Use ticket tools and summarize result in concise Chinese.",
            List.of("ticket_status", "ticket_create", "ticket_priority_update"),
            "{}",
            "{}",
            "MEDIUM"
    );

    private final SkillDefinition knowledgeSkill = new SkillDefinition(
            "knowledge-base-qa",
            "Answer enterprise policy or knowledge-base questions using RAG evidence.",
            "Use retrieved documents and cite source when possible.",
            List.of(),
            "{}",
            "{}",
            "LOW"
    );

    @Override
    public Optional<SkillDefinition> select(AgentRequest request, ConversationMemory memory) {
        String question = request.question() == null ? "" : request.question();
        if (question.contains("工单") || question.contains("报修") || question.contains("故障")) {
            return Optional.of(ticketSkill);
        }
        if (question.contains("流程") || question.contains("制度") || question.contains("知识库") || question.contains("RAG")) {
            return Optional.of(knowledgeSkill);
        }
        return Optional.empty();
    }
}
