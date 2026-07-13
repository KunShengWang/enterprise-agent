package com.agent.platform.skill;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Primary
@Component
public class JdbcSkillRegistry implements SkillRegistry {

    private static final String CATEGORY = "skill";

    private final JdbcAgentStoreSupport store;

    public JdbcSkillRegistry(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    /**
     * 列出 skills
     */
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
        if (skill == null || skill.name() == null || skill.name().isBlank()) {
            throw new IllegalArgumentException("skill name must not be blank");
        }
        store.save(CATEGORY, skill.name(), skill, Instant.now(), Instant.now());
        return skill;
    }

    @Override
    public boolean delete(String name) {
        return store.delete(CATEGORY, name);
    }

    /**
     * 根据用户问题选出评分较高的几个 skill
     */
    @Override
    public List<SkillMatch> search(String query, int limit) {
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<SkillMatch> matches = new ArrayList<>();
        for (SkillDefinition skill : list()) {
            // 根据用户问题对 skills 进行打分
            SkillMatch match = match(skill, normalizedQuery);
            if (match.score() > 0) {
                matches.add(match);
            }
        }
        // 选出评分较高的几个 skill
        return matches.stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(Math.max(1, limit))
                .toList();
    }

    private void seedDefaultsIfNeeded() {
        if (store.count(CATEGORY) > 0) {
            return;
        }
        for (SkillDefinition skill : defaults()) {
            save(skill);
        }
    }

    private List<SkillDefinition> defaults() {
        return List.of(
                new SkillDefinition(
                        "ticket-handling",
                        "处理工单查询、创建、优先级调整和关闭，必须优先绑定工单工具。",
                        "先识别工单意图，再调用 ticket_status、ticket_create、ticket_priority_update 或 ticket_close，最后用中文总结工具结果。",
                        List.of("ticket_status", "ticket_create", "ticket_priority_update", "ticket_close", "mcp.ticket.ticket_status", "mcp.ticket.ticket_create"),
                        "{}",
                        "{}",
                        "MEDIUM"
                ),
                new SkillDefinition(
                        "knowledge-base-qa",
                        "处理企业制度、流程、规范、知识库、RAG 问答，必须优先使用检索证据。",
                        "先进行 Query Rewrite 和 RAG 检索，再基于 source、chunkIndex 和上下文回答，资料不足时明确说明。",
                        List.of(),
                        "{}",
                        "{}",
                        "LOW"
                ),
                new SkillDefinition(
                        "incident-troubleshooting",
                        "处理故障排查、P0/P1 应急、日志分析、恢复建议和复盘总结。",
                        "先澄清故障级别和现象，再结合知识库与工单工具输出排查步骤、风险和下一步动作。",
                        List.of("ticket_status", "ticket_priority_update"),
                        "{}",
                        "{}",
                        "HIGH"
                )
        );
    }

    private SkillMatch match(SkillDefinition skill, String query) {
        Set<String> matched = new LinkedHashSet<>();
        String text = (skill.name() + " " + skill.description() + " " + skill.promptTemplate() + " " + skill.toolNames())
                .toLowerCase(Locale.ROOT);
        List<String> tokens = tokens(query);
        for (String token : tokens) {
            if (text.contains(token.toLowerCase(Locale.ROOT))) {
                matched.add(token);
            }
        }
        double score = matched.isEmpty() ? 0 : (double) matched.size() / Math.max(1, tokens.size());
        if (query.contains(skill.name().toLowerCase(Locale.ROOT))) {
            score += 0.5;
        }
        if (skill.name().equals("ticket-handling") && (query.contains("ticket") || query.contains("工单") || query.contains("报修"))) {
            score += 0.35;
        }
        if (skill.name().equals("incident-troubleshooting") && (query.contains("incident") || query.contains("故障") || query.contains("p0") || query.contains("p1"))) {
            score += 0.35;
        }
        if (skill.name().equals("knowledge-base-qa") && (query.contains("rag") || query.contains("知识库") || query.contains("流程") || query.contains("制度"))) {
            score += 0.35;
        }
        return new SkillMatch(skill, score, List.copyOf(matched), "matched terms=" + matched);
    }

    private List<String> tokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        String normalized = query.replaceAll("[^\\p{IsHan}a-zA-Z0-9]+", " ").trim();
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            tokens.add(part);
            if (containsHan(part) && part.length() > 2) {
                for (int index = 0; index < part.length() - 1; index++) {
                    tokens.add(part.substring(index, index + 2));
                }
            }
        }
        return tokens;
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
