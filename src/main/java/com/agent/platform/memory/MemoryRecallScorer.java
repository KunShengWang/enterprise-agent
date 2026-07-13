package com.agent.platform.memory;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class MemoryRecallScorer {

    private static final Map<String, List<String>> SEMANTIC_ALIASES = Map.ofEntries(
            Map.entry("ticket", List.of("ticket", "工单", "报修", "故障单", "服务单")),
            Map.entry("rag", List.of("rag", "知识库", "文档", "资料", "检索", "向量", "embedding")),
            Map.entry("refund", List.of("refund", "退款", "退费", "返款")),
            Map.entry("approval", List.of("approval", "审批", "审核", "人工确认", "复核")),
            Map.entry("incident", List.of("incident", "故障", "事故", "告警", "应急")),
            Map.entry("priority", List.of("priority", "优先级", "p0", "p1", "p2", "p3", "升级")),
            Map.entry("close", List.of("close", "关闭", "完成", "解决", "结单")),
            Map.entry("tool", List.of("tool", "工具", "function", "mcp", "调用")),
            Map.entry("memory", List.of("memory", "记忆", "偏好", "用户画像", "历史")),
            Map.entry("security", List.of("security", "安全", "护栏", "越权", "注入", "脱敏"))
    );

    public double score(String query, String content) {
        return scoreDetail(query, content).score();
    }

    public MemoryRecallScore scoreDetail(String query, String content) {
        if (isBlank(query) || isBlank(content)) {
            return new MemoryRecallScore(0, 0, 0, Set.of(), Set.of());
        }
        // 把字符串统一转为小写
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        // TODO 这块的分词是不是有点简陋
        // 分词 ["退款", "款审", "审批", "批流", ...]
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            double exactScore = normalizedContent.contains(query.toLowerCase(Locale.ROOT)) ? 1.0 : 0;
            return new MemoryRecallScore(exactScore, exactScore, 0, Set.of(), Set.of());
        }
        int hits = 0;
        Set<String> matchedTokens = new LinkedHashSet<>();
        for (String token : tokens) {
            // 每个 token 去记忆内容里找
            if (normalizedContent.contains(token.toLowerCase(Locale.ROOT))) {
                hits++;
                matchedTokens.add(token);
            }
        }
        // 命中率 = 命中数 / 总token数
        double lexicalScore = (double) hits / tokens.size();

        Set<String> expandedQueryTerms = semanticTerms(query);
        Set<String> contentTerms = semanticTerms(content);
        Set<String> semanticMatches = new LinkedHashSet<>();
        for (String term : expandedQueryTerms) {
            if (contentTerms.contains(term)) {
                semanticMatches.add(term);
            }
        }
        double semanticScore = expandedQueryTerms.isEmpty()
                ? 0
                : (double) semanticMatches.size() / expandedQueryTerms.size();
        Set<String> allMatches = new LinkedHashSet<>(matchedTokens);
        allMatches.addAll(semanticMatches);
        double finalScore = Math.min(1.0, lexicalScore * 0.65 + semanticScore * 0.35);
        return new MemoryRecallScore(finalScore, lexicalScore, semanticScore, allMatches, expandedQueryTerms);
    }

    private Set<String> tokenize(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        // ① 转小写 + 把标点/特殊字符替换成空格
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]+", " ");
        // ② 按空白字符切分
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            // ③ 如果是中文长串（>6个字），用 bigram 分词
            if (containsHan(part) && part.length() > 6) {
                for (int index = 0; index < part.length() - 1; index++) {
                    tokens.add(part.substring(index, index + 2));
                }
            }
            // 英文/短中文直接作为一个 token
            else {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private Set<String> semanticTerms(String value) {
        Set<String> terms = new LinkedHashSet<>(tokenize(value));
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        Map<String, List<String>> aliases = new LinkedHashMap<>(SEMANTIC_ALIASES);
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            boolean matched = entry.getValue().stream()
                    .map(alias -> alias.toLowerCase(Locale.ROOT))
                    .anyMatch(normalized::contains);
            if (matched) {
                terms.add(entry.getKey());
                terms.addAll(entry.getValue().stream().map(alias -> alias.toLowerCase(Locale.ROOT)).toList());
            }
        }
        return terms;
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
