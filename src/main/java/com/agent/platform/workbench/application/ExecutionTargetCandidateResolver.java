package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.target.ExecutionTargetDefinition;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies deterministic business boundaries before model routing.
 * The resolver does not grant permissions; it only narrows the already enabled target catalog.
 */
@Component
public class ExecutionTargetCandidateResolver {

    static final String POLICY_VERSION = "execution-target-candidates-v1";

    private static final Pattern EXPLICIT_IDENTIFIERS = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(requestIds?|orderNos?|deductNos?)\\s*[:=：]\\s*"
                    + "([A-Za-z0-9][A-Za-z0-9._:/-]*(?:\\s*[,，]\\s*[A-Za-z0-9][A-Za-z0-9._:/-]*)*)");

    public Resolution resolve(String originalGoal, List<ExecutionTargetDefinition> enabledTargets) {
        if (enabledTargets == null || enabledTargets.isEmpty()) {
            throw new IllegalArgumentException("enabled targets are required");
        }
        String goal = originalGoal == null ? "" : originalGoal;
        ScopeEvidence evidence = analyze(goal);

        if (evidence.conflictingScope()) {
            String reason = "任务同时声明了唯一单案例和批量或事故调查范围，请明确选择单案例处理或事故调查";
            return Resolution.clarification(enabledTargets, deterministicClarification(goal, reason), reason,
                    "CONFLICTING_ORDERCARE_INCIDENT_SCOPE");
        }
        if (evidence.incidentRecoveryPlanIntent()) {
            Optional<ExecutionTargetDefinition> recoveryPlan = target(
                    enabledTargets, ExecutionTargetId.INCIDENT_RECOVERY_PLAN);
            if (recoveryPlan.isEmpty()) {
                String reason = "当前账号没有可用的事故恢复计划执行目标";
                return Resolution.clarification(enabledTargets, deterministicClarification(goal, reason), reason,
                        "INCIDENT_RECOVERY_TARGET_UNAVAILABLE");
            }
            return Resolution.model(List.of(recoveryPlan.get()), "EXPLICIT_INCIDENT_RECOVERY_PLAN_INTENT");
        }
        if (evidence.boundedSingleCase()) {
            Optional<ExecutionTargetDefinition> orderCare = target(
                    enabledTargets, ExecutionTargetId.ORDERCARE_CASE);
            if (orderCare.isEmpty()) {
                String reason = "当前账号没有可用的 OrderCare 单案例执行目标";
                return Resolution.clarification(enabledTargets, deterministicClarification(goal, reason), reason,
                        "ORDERCARE_TARGET_UNAVAILABLE");
            }
            ExplicitIdentifier identifier = evidence.identifiers().get(0);
            return Resolution.deterministic(
                    List.of(orderCare.get()),
                    deterministicOrderCare(goal, identifier),
                    "EXPLICIT_BOUNDED_SINGLE_CASE");
        }
        if (evidence.singleCaseIntent()) {
            Optional<ExecutionTargetDefinition> orderCare = target(
                    enabledTargets, ExecutionTargetId.ORDERCARE_CASE);
            if (orderCare.isPresent()) {
                return Resolution.model(List.of(orderCare.get()), "EXPLICIT_SINGLE_CASE_INTENT");
            }
        }
        if (evidence.incidentScope()) {
            Optional<ExecutionTargetDefinition> incident = target(
                    enabledTargets, ExecutionTargetId.INCIDENT_INVESTIGATION);
            if (incident.isEmpty()) {
                String reason = "当前账号没有可用的事故调查执行目标";
                return Resolution.clarification(enabledTargets, deterministicClarification(goal, reason), reason,
                        "INCIDENT_TARGET_UNAVAILABLE");
            }
            return Resolution.model(List.of(incident.get()), "EXPLICIT_INCIDENT_SCOPE");
        }
        return Resolution.model(enabledTargets, "AMBIGUOUS_MODEL_ROUTING");
    }

    static ScopeEvidence analyze(String goal) {
        String source = goal == null ? "" : goal;
        Map<String, Set<String>> valuesByType = new LinkedHashMap<>();
        Matcher matcher = EXPLICIT_IDENTIFIERS.matcher(source);
        while (matcher.find()) {
            String type = canonicalType(matcher.group(1));
            Set<String> values = valuesByType.computeIfAbsent(type, ignored -> new LinkedHashSet<>());
            for (String raw : matcher.group(2).split("[,，]")) {
                String value = raw.trim();
                if (!value.isBlank()) values.add(value);
            }
        }

        List<ExplicitIdentifier> identifiers = new ArrayList<>();
        valuesByType.forEach((type, values) -> values.forEach(value ->
                identifiers.add(new ExplicitIdentifier(type, value))));

        String normalized = source.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        boolean singleCaseIntent = containsAny(normalized,
                "唯一ordercare单案例", "唯一的ordercare单案例", "唯一单案例", "单案例", "单个案例", "单订单", "单案");
        boolean incidentRecoveryPlanIntent = containsAny(normalized,
                "生成受控恢复计划", "生成恢复计划", "创建恢复proposal", "制定恢复计划", "规划恢复");
        boolean incidentScope = valuesByType.getOrDefault("requestId", Set.of()).size() > 1
                || containsAny(normalized,
                "批量", "批次", "一批订单", "多个订单", "多个requestid", "全量订单",
                "事故", "多agent调查", "multiagentinvestigation", "incident");
        boolean conflictingScope = singleCaseIntent && (incidentScope || incidentRecoveryPlanIntent);
        boolean boundedSingleCase = identifiers.size() == 1 && !incidentScope && !incidentRecoveryPlanIntent;
        return new ScopeEvidence(List.copyOf(identifiers), singleCaseIntent,
                incidentScope, incidentRecoveryPlanIntent, conflictingScope, boundedSingleCase);
    }

    private RouterModelResult deterministicOrderCare(String goal, ExplicitIdentifier identifier) {
        ExecutionDecision decision = new ExecutionDecision(
                ExecutionTargetId.ORDERCARE_CASE.name(), 1.0,
                "deterministic policy selected one explicit bounded OrderCare identifier",
                Map.of(identifier.type(), identifier.value()), List.of(),
                "已识别为唯一有界的 OrderCare 单案例。");
        return deterministicResult(goal, decision);
    }

    private RouterModelResult deterministicClarification(String goal, String reason) {
        ExecutionDecision decision = new ExecutionDecision(
                "", 1.0, "deterministic policy requires clarification",
                Map.of(), List.of("executionScope"), reason);
        return deterministicResult(goal, decision);
    }

    private RouterModelResult deterministicResult(String goal, ExecutionDecision decision) {
        String promptDigest = sha256(POLICY_VERSION + "|" + goal);
        String resultDigest = sha256(decision.targetId() + "|" + decision.reason()
                + "|" + decision.extractedInputs() + "|" + decision.missingInputs());
        return new RouterModelResult(decision, POLICY_VERSION, promptDigest, resultDigest,
                "", 0, 0, 0);
    }

    private Optional<ExecutionTargetDefinition> target(List<ExecutionTargetDefinition> targets,
                                                       ExecutionTargetId targetId) {
        return targets.stream().filter(target -> target.targetId() == targetId).findFirst();
    }

    private static String canonicalType(String rawType) {
        String value = rawType.toLowerCase(Locale.ROOT);
        if (value.startsWith("request")) return "requestId";
        if (value.startsWith("order")) return "orderNo";
        return "deductNo";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception exception) {
            throw new IllegalStateException("failed to digest deterministic route decision", exception);
        }
    }

    public record Resolution(
            List<ExecutionTargetDefinition> candidates,
            Optional<RouterModelResult> deterministicResult,
            String clarificationReason,
            String policyReason
    ) {
        public Resolution {
            if (candidates == null || candidates.isEmpty()) {
                throw new IllegalArgumentException("candidate targets are required");
            }
            candidates = List.copyOf(candidates);
            deterministicResult = deterministicResult == null ? Optional.empty() : deterministicResult;
            clarificationReason = clarificationReason == null ? "" : clarificationReason.trim();
            policyReason = policyReason == null ? "" : policyReason.trim();
        }

        static Resolution deterministic(List<ExecutionTargetDefinition> candidates,
                                        RouterModelResult result,
                                        String policyReason) {
            return new Resolution(candidates, Optional.of(result), "", policyReason);
        }

        static Resolution model(List<ExecutionTargetDefinition> candidates, String policyReason) {
            return new Resolution(candidates, Optional.empty(), "", policyReason);
        }

        static Resolution clarification(List<ExecutionTargetDefinition> candidates,
                                        RouterModelResult result,
                                        String reason,
                                        String policyReason) {
            return new Resolution(candidates, Optional.of(result), reason, policyReason);
        }

        public boolean requiresClarification() {
            return !clarificationReason.isBlank();
        }

        public boolean allows(String targetId) {
            return candidates.stream().anyMatch(candidate -> candidate.targetId().name().equals(targetId));
        }
    }

    record ExplicitIdentifier(String type, String value) { }

    record ScopeEvidence(
            List<ExplicitIdentifier> identifiers,
            boolean singleCaseIntent,
            boolean incidentScope,
            boolean incidentRecoveryPlanIntent,
            boolean conflictingScope,
            boolean boundedSingleCase
    ) { }
}
