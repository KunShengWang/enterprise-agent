package com.agent.platform.skill;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;

import java.util.Optional;

public interface SkillSelector {

    /**
     * 根据用户问题选出评分高 skill
     */
    Optional<SkillDefinition> select(AgentRequest request, ConversationMemory memory);
}
