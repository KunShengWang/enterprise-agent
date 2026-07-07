package com.agent.platform.skill;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class StaticSkillSelector implements SkillSelector {

    private final SkillRegistry skillRegistry;

    public StaticSkillSelector(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public Optional<SkillDefinition> select(AgentRequest request, ConversationMemory memory) {
        StringBuilder query = new StringBuilder();
        if (request != null && request.question() != null) {
            query.append(request.question()).append(' ');
        }
        if (memory != null && memory.summary() != null) {
            query.append(memory.summary()).append(' ');
        }
        return skillRegistry.search(query.toString(), 1)
                .stream()
                .filter(match -> match.score() >= 0.15)
                .findFirst()
                .map(SkillMatch::skill);
    }
}
