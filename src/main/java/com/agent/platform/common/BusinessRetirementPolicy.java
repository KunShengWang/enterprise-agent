package com.agent.platform.common;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Permanent execution boundary; historical identifiers remain readable. No feature flag re-enables it. */
public final class BusinessRetirementPolicy {
    public static final String CODE = "TARGET_RETIRED";
    public static final String MESSAGE = "Generic Agent、FlowOrder 订单诊断、事故调查和恢复业务已退役，不再支持新执行或恢复；历史记录仍可查看。";
    private static final Set<String> TARGETS = Set.of("GENERAL_AGENT", "ORDERCARE_CASE", "INCIDENT_INVESTIGATION", "INCIDENT_RECOVERY_PLAN");
    private static final Set<String> TOOLS = Set.of("delegate_order_analyst", "delegate_inventory_analyst",
            "delegate_mq_analyst", "review_incident_evidence");
    private static final Pattern LEGACY_INPUT = Pattern.compile(
            "(?i)floworder|ordercare|incident[ _-]*(?:command|investigation|scope[ _-]*discovery|recovery)|recovery[ _-]+plan|"
            + "(?:requestIds?|orderNos?|deductNos?|deadLetterId|incidentId)\\b|"
            + "事故调查|调查[^。\\n]*事故|异常订单|订单异常|诊断[^。\\n]*订单|订单[^。\\n]*诊断|"
            + "调查[^。\\n]*规划恢复|生成受控恢复计划|生成恢复计划|制定恢复计划|创建恢复\\s*proposal|"
            + "死信[^。\\n]*重放|重放[^。\\n]*死信");

    private BusinessRetirementPolicy() { }
    public static boolean retiredTarget(String id) { return id != null && TARGETS.contains(id.trim()); }
    public static boolean retiredScenario(String id) {
        String value = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("ordercare-") || value.startsWith("incident-");
    }
    public static boolean retiredTool(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return value.startsWith("floworder_") || value.startsWith("mcp.floworder.") || TOOLS.contains(value);
    }
    public static boolean retiredInput(String text) { return text != null && LEGACY_INPUT.matcher(text).find(); }
    public static void requireTarget(String id) { if (retiredTarget(id)) throw new RetiredBusinessException(); }
    public static void requireScenario(String id) { if (retiredScenario(id)) throw new RetiredBusinessException(); }
    public static void requireTool(String name) { if (retiredTool(name)) throw new RetiredBusinessException(); }
}
