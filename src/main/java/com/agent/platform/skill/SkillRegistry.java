package com.agent.platform.skill;

import java.util.List;
import java.util.Optional;

public interface SkillRegistry {

    List<SkillDefinition> list();

    Optional<SkillDefinition> find(String name);

    SkillDefinition save(SkillDefinition skill);

    boolean delete(String name);

    /**
     * 根据用户问题选出评分较高的几个 skill
     */
    List<SkillMatch> search(String query, int limit);
}
