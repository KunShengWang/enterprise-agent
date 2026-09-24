package com.agent.platform.procurement;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Finite whole-clause grammar. Never changes the scalar extractor or old coverage. */
public final class ProcurementAnswerRelationExtractor {
    public static final String VERSION = "procurement-relations-v1";
    public record Claim(String text, int start, int end, String left, String right, String scope,
                        String property, String operator, BigDecimal difference, String unit, String currency,
                        String modality, String unresolvedReason) {
        public Claim {
            if (text == null || start < 0 || end < start || end - start != text.length()
                    || left == null || right == null || scope == null || property == null || operator == null
                    || unit == null || currency == null || modality == null || unresolvedReason == null
                    || difference != null && difference.signum() < 0
                    || unresolvedReason.isEmpty() && !modality.equals("ASSERTED"))
                throw new IllegalArgumentException("INVALID_RELATION_CLAIM");
        }
    }
    private static final String S = "(?:Supplier\\s+)?([A-Z])";
    private static final String N = "([0-9]+(?:\\.[0-9]+)?)";
    private static final Pattern PRICE = Pattern.compile("^" + S + "\\s*(?:的)?\\s*(?:总价)?\\s*比\\s*" + S
            + "\\s*(?:的总价)?\\s*(更贵|贵|更高|高|更便宜|便宜|更低|低)\\s*(?:" + N + "\\s*(万)?\\s*(元|人民币|CNY|美元|USD|欧元|EUR))?$");
    private static final Pattern DELIVERY = Pattern.compile("^" + S + "\\s*(?:的)?\\s*(?:报价交期|交期|交付)?\\s*比\\s*" + S
            + "\\s*(?:的报价交期|的交期)?\\s*(更快|快|更慢|慢)\\s*(?:" + N + "\\s*天)?$");
    private static final Pattern BUDGET = Pattern.compile("^" + S + "\\s*的总价\\s*(满足本次预算|在本次预算内|超出本次预算)$");
    private static final Pattern LIMIT = Pattern.compile("^" + S + "\\s*的(?:报价交期|交期)\\s*(满足本次交付期限|超出本次交付期限)$");
    private static final Pattern UNIQUE = Pattern.compile("^当前(?:原始硬)?约束下(?:仅|只有)\\s*" + S + "\\s*(?:合格|是合格供应商)$");
    private static final Pattern EMPTY = Pattern.compile("^当前原始硬约束下(?:无|没有)合格供应商$");
    // Scope may cross clauses/sentences: conservative global abstention, not prefix extraction.
    private static final Pattern QUALIFIED = Pattern.compile("如果|假如|假设|若|只有.*才|只要|除非|否则|前提|条件|仅在|只在|可能|据说|听说|引用|原话|声称|计划|将|预计|曾|之前|并非|不是|不比|未必|否认|取消|是否|能否|可否|吗|么|[？?\"'“”‘’「」『』《》]");
    private static final Pattern PART = Pattern.compile("[^，,；;。！!？?\\r\\n]+");

    public List<Claim> extract(String answer) {
        String raw = answer == null ? "" : answer;
        List<Claim> result = new ArrayList<>();
        boolean qualified = QUALIFIED.matcher(raw).find();
        var parts = PART.matcher(raw);
        while (parts.find()) {
            int start = parts.start(), end = parts.end();
            while (start < end && Character.isWhitespace(raw.charAt(start))) start++;
            while (end > start && Character.isWhitespace(raw.charAt(end - 1))) end--;
            if (start == end) continue;
            String text = raw.substring(start, end);
            if (qualified) { result.add(unresolved(text, start, end, "QUALIFIED", "CONDITION_QUESTION_QUOTATION_OR_MODAL_SCOPE")); continue; }
            var price = PRICE.matcher(text); var delivery = DELIVERY.matcher(text);
            var budget = BUDGET.matcher(text); var limit = LIMIT.matcher(text); var unique = UNIQUE.matcher(text);
            if (price.matches()) {
                BigDecimal difference = price.group(4) == null ? null : new BigDecimal(price.group(4))
                        .multiply(price.group(5) == null ? BigDecimal.ONE : new BigDecimal("10000")).stripTrailingZeros();
                result.add(claim(text, start, end, id(price.group(1)), id(price.group(2)), "PAIRWISE", "TOTAL_PRICE",
                        Set.of("更贵", "贵", "更高", "高").contains(price.group(3)) ? "GT" : "LT", difference, "MONEY", currency(price.group(6))));
            } else if (delivery.matches()) {
                result.add(claim(text, start, end, id(delivery.group(1)), id(delivery.group(2)), "PAIRWISE", "LEAD_TIME",
                        delivery.group(3).contains("快") ? "LT" : "GT", delivery.group(4) == null ? null : new BigDecimal(delivery.group(4)).stripTrailingZeros(), "DAY", ""));
            } else if (budget.matches()) {
                result.add(claim(text, start, end, id(budget.group(1)), "case", "CASE_BUDGET", "TOTAL_PRICE",
                        budget.group(2).startsWith("超出") ? "GT" : "LE", null, "MONEY", "CASE_CURRENCY"));
            } else if (limit.matches()) {
                result.add(claim(text, start, end, id(limit.group(1)), "case", "CASE_DELIVERY", "LEAD_TIME",
                        limit.group(2).startsWith("超出") ? "GT" : "LE", null, "DAY", ""));
            } else if (unique.matches()) {
                result.add(claim(text, start, end, id(unique.group(1)), "", "ORIGINAL_CASE_ELIGIBLE", "ELIGIBLE_SET", "ONLY", null, "", ""));
            } else if (EMPTY.matcher(text).matches()) {
                result.add(claim(text, start, end, "", "", "ORIGINAL_CASE_ELIGIBLE", "ELIGIBLE_SET", "EMPTY", null, "", ""));
            } else result.add(unresolved(text, start, end, "UNKNOWN", "UNSUPPORTED_WHOLE_CLAUSE"));
        }
        if (result.isEmpty()) result.add(unresolved("", 0, 0, "UNKNOWN", "NO_RELATION_CLAIM"));
        return List.copyOf(result);
    }
    private static String id(String letter) { return "supplier-" + letter.toLowerCase(Locale.ROOT); }
    private static String currency(String token) {
        if (token == null) return "CASE_CURRENCY";
        return switch (token) { case "元", "人民币", "CNY" -> "CNY"; case "美元", "USD" -> "USD"; default -> "EUR"; };
    }
    private static Claim claim(String text, int start, int end, String left, String right, String scope, String property,
                               String operator, BigDecimal difference, String unit, String currency) {
        return new Claim(text, start, end, left, right, scope, property, operator, difference, unit, currency, "ASSERTED", "");
    }
    private static Claim unresolved(String text, int start, int end, String modality, String reason) {
        return new Claim(text, start, end, "", "", "UNKNOWN", "UNRESOLVED", "", null, "", "", modality, reason);
    }
}
