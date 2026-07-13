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

    /**
     * 根据用户问题选出评分高 skill
     */
    @Override
    public Optional<SkillDefinition> select(AgentRequest request, ConversationMemory memory) {
        StringBuilder query = new StringBuilder();
        // 添加用户消息
        if (request != null && request.question() != null) {
            query.append(request.question()).append(' ');
        }
        // 添加 ConversationMemory 中的压缩消息
        if (memory != null && memory.summary() != null) {
            query.append(memory.summary()).append(' ');
        }
        // 根据用户问题选出评分高 skill
        return skillRegistry.search(query.toString(), 1)
                .stream()
                .filter(match -> match.score() >= 0.15)
                .findFirst()
                .map(SkillMatch::skill);
    }
}
