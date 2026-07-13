package com.agent.platform.skill;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class JdbcSkillRegistry implements SkillRegistry {

    private static final String CATEGORY = "skill";

    private final JdbcAgentStoreSupport store;

    public JdbcSkillRegistry(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    @Override
    public List<SkillDefinition> list() {
        seedDefaultsIfNeeded();
        return store.recent(CATEGORY, SkillDefinition.class, Integer.MAX_VALUE).stream()
                .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                .toList();
    }

    @Override
    public Optional<SkillDefinition> find(String name) {
        seedDefaultsIfNeeded();
        return store.find(CATEGORY, name, SkillDefinition.class);
    }

    @Override
    public SkillDefinition save(SkillDefinition skill) {
        validate(skill);
        store.save(CATEGORY, skill.name(), skill, Instant.now(), Instant.now());
        return skill;
    }

    @Override
    public boolean delete(String name) {
        return store.delete(CATEGORY, name);
    }

    private void seedDefaultsIfNeeded() {
        if (store.count(CATEGORY) > 0) {
            return;
        }
        defaults().forEach(this::save);
    }

    private void validate(SkillDefinition skill) {
        if (skill == null || skill.name() == null || skill.name().isBlank()) {
            throw new IllegalArgumentException("skill name must not be blank");
        }
        if (!skill.name().matches("[a-z0-9][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("skill name must use lowercase letters, numbers and hyphens");
        }
        if (skill.promptTemplate() == null || skill.promptTemplate().isBlank()) {
            throw new IllegalArgumentException("skill promptTemplate must not be blank");
        }
    }

    private List<SkillDefinition> defaults() {
        return List.of(
                new SkillDefinition(
                        "ticket-handling",
                        "处理工单查询、创建、优先级调整和关闭。",
                        "先确认工单目标和必要参数，再选择对应工单工具；写操作必须说明影响并遵守运行时审批，最后基于工具结果总结。",
                        List.of("ticket_status", "ticket_create", "ticket_priority_update", "ticket_close"),
                        "{}",
                        "{}",
                        "MEDIUM"
                ),
                new SkillDefinition(
                        "knowledge-base-qa",
                        "处理企业制度、流程、规范和知识库问答。",
                        "先使用 knowledge_search 获取证据，再基于来源回答；资料不足或冲突时必须明确说明，不得补写不存在的制度。",
                        List.of("knowledge_search"),
                        "{}",
                        "{}",
                        "LOW"
                ),
                new SkillDefinition(
                        "incident-troubleshooting",
                        "处理故障排查、P0/P1 应急响应、恢复建议和复盘。",
                        "先确认影响范围、故障等级和时间线，再检索知识库并按需读取工单；输出假设、证据、风险和下一步，不执行未经审批的写操作。",
                        List.of("knowledge_search", "ticket_status", "ticket_priority_update"),
                        "{}",
                        "{}",
                        "HIGH"
                )
        );
    }
}
