package com.agent.platform.query;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import org.springframework.stereotype.Service;

@Service
public class RuleBasedQueryRewriteService implements QueryRewriteService {

    @Override
    public String rewrite(AgentRequest request, ConversationMemory memory) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isBlank()) {
            return "";
        }
        if (!question.endsWith("？") && !question.endsWith("?")) {
            return question + "？";
        }
        return question;
    }
}
