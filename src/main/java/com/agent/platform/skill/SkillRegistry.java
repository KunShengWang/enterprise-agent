package com.agent.platform.skill;

import java.util.List;
import java.util.Optional;

public interface SkillRegistry {

    List<SkillDefinition> list();

    Optional<SkillDefinition> find(String name);

    SkillDefinition save(SkillDefinition skill);

    boolean delete(String name);
}
