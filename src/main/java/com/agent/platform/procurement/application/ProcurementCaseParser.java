package com.agent.platform.procurement.application;

import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementCasePatch;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只抽取可验证的采购字段；开放式解释仍由 Procurement Agent 完成。 */
@Component
public class ProcurementCaseParser {
    private static final Pattern QUANTITY = Pattern.compile("(\\d[\\d,]*)\\s*(台|件|个|套|箱|只|台套)");
    private static final Pattern BUDGET = Pattern.compile("预算\\s*(?:为|是)?\\s*([\\d,.]+)\\s*(万|万元|千|k|K)?");
    private static final Pattern DAYS = Pattern.compile("(?:不超过|最多|以内|交付|到货|交期)[^。；,，]{0,8}?(\\d+)\\s*(?:天|日)");
    private static final Pattern WEEKS = Pattern.compile("([0-9一二两三四五六七八九十]+)\\s*周(?:内|以内)?");
    private static final Pattern GPU = Pattern.compile("(?:显存|GPU\\s*(?:显存|memory))\\s*(?:至少|不低于|>=|大于等于)?\\s*(\\d+)\\s*GB", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXCLUDED = Pattern.compile("(?:不要|排除|不选)(?:供应商)?\\s*((?:Supplier\\s+)?[A-Za-z][A-Za-z0-9_-]{0,30})(?=[，。,;；]|$)", Pattern.CASE_INSENSITIVE);

    public ProcurementCaseState merge(ProcurementCaseState current, String message) {
        ProcurementCasePatch patch = toPatch(message);
        return patch.hasChanges() ? new ProcurementCasePatchMerger().merge(current, patch)
                : (current == null ? ProcurementCaseState.empty() : current);
    }

    /**
     * 仅用于 deterministic fast-path 或离线 fixture，把简单可识别文本转换成 Agent 同形态 Patch。
     * Workbench 生产路由不会调用它；复杂语义必须由模型提出 Patch。
     */
    public ProcurementCasePatch toPatch(String message) {
        String text = message == null ? "" : message.trim();
        Integer quantity = number(text, QUANTITY, 1);
        BigDecimal budget = budget(text);
        Integer deliveryDays = number(text, DAYS, 1);
        if (deliveryDays == null) {
            Integer weeks = number(text, WEEKS, 1);
            if (weeks != null) deliveryDays = weeks * 7;
        }
        Map<String, String> hard = new LinkedHashMap<>();
        Matcher gpu = GPU.matcher(text);
        if (gpu.find()) hard.put("gpuMemoryMinGb", gpu.group(1));
        Set<String> excluded = new LinkedHashSet<>();
        Matcher excludedMatcher = EXCLUDED.matcher(text);
        while (excludedMatcher.find()) excluded.add(excludedMatcher.group(1).trim());
        Map<String, String> preferences = new LinkedHashMap<>();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("交期") || lower.contains("到货") || lower.contains("延期")) preferences.put("deliveryPriority", "HIGH");
        if (lower.contains("稍微贵") || lower.contains("价格不是第一") || lower.contains("可以贵")) preferences.put("pricePriority", "MEDIUM");
        String description = (lower.contains("采购") || lower.contains("需要")) && !text.isBlank() ? text : null;
        String category = null;
        if (lower.contains("工作站") || lower.contains("workstation")) category = "计算工作站";
        else if (lower.contains("办公")) category = "办公用品";
        return new ProcurementCasePatch(category, description, quantity, budget, currency(text), deliveryDays,
                hard, Set.of(), preferences, Set.of(), excluded, Set.of());
    }

    private BigDecimal budget(String text) {
        Matcher matcher = BUDGET.matcher(text);
        if (!matcher.find()) return null;
        BigDecimal value = new BigDecimal(matcher.group(1).replace(",", ""));
        String unit = matcher.group(2);
        if (unit != null && (unit.contains("万"))) value = value.multiply(BigDecimal.valueOf(10_000));
        else if (unit != null && (unit.equalsIgnoreCase("k") || unit.equals("千"))) value = value.multiply(BigDecimal.valueOf(1_000));
        return value;
    }

    private Integer number(String text, Pattern pattern, int group) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        try { return parseInteger(matcher.group(group)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Integer parseInteger(String raw) {
        String value = raw.replace(",", "");
        if (value.matches("\\d+")) return Integer.valueOf(value);
        return switch (value) {
            case "一" -> 1; case "二", "两" -> 2; case "三" -> 3; case "四" -> 4; case "五" -> 5;
            case "六" -> 6; case "七" -> 7; case "八" -> 8; case "九" -> 9; case "十" -> 10;
            default -> throw new NumberFormatException(value);
        };
    }

    private String currency(String text) {
        return text.contains("$") || text.toLowerCase(Locale.ROOT).contains("usd") ? "USD" : null;
    }
}
