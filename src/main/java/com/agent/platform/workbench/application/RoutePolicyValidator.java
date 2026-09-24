package com.agent.platform.workbench.application;

import com.agent.platform.common.BusinessRetirementPolicy;

import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.model.IdentifierSource;
import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.model.RouteValidationResult;
import com.agent.platform.workbench.model.ValidatedExecutionInput;
import com.agent.platform.workbench.model.ValidatedIdentifier;
import com.agent.platform.workbench.target.ExecutionTargetDefinition;
import com.agent.platform.workbench.target.ExecutionTargetId;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class RoutePolicyValidator {

    private static final Set<String> FORBIDDEN_MODEL_FIELDS = Set.of(
            "scenarioId", "executionProfile", "toolName", "approvedBy", "roles", "tenantId", "url", "sql");
    private static final Set<String> DANGEROUS_IDENTIFIERS = Set.of(
            "requestId", "orderNo", "deductNo", "queueName", "queueNames", "incidentId", "batchId", "requestIds");

    private final ExecutionTargetRegistry targetRegistry;
    private final WorkbenchRoutingProperties properties;
    private final ObjectMapper objectMapper;
    private final ProcurementCaseService procurementCaseService;

    @Autowired
    public RoutePolicyValidator(ExecutionTargetRegistry targetRegistry,
                                WorkbenchRoutingProperties properties,
                                ObjectMapper objectMapper,
                                ProcurementCaseService procurementCaseService) {
        this.targetRegistry = targetRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.procurementCaseService = procurementCaseService;
    }

    public RoutePolicyValidator(ExecutionTargetRegistry targetRegistry,
                                WorkbenchRoutingProperties properties,
                                ObjectMapper objectMapper) {
        this(targetRegistry, properties, objectMapper, null);
    }

    public RouteValidationResult validate(ExecutionDecision decision, RouteValidationContext context) {
        if (decision == null) {
            return rejected("STRUCTURED_OUTPUT_INVALID", "routing decision is absent");
        }
        if (BusinessRetirementPolicy.retiredTarget(decision.targetId())
                || BusinessRetirementPolicy.retiredInput(context.originalGoal())) {
            return rejected(BusinessRetirementPolicy.CODE,
                    BusinessRetirementPolicy.MESSAGE);
        }
        ExecutionTargetDefinition target = targetRegistry.findEnabled(context.principal(), decision.targetId())
                .orElse(null);
        if (target == null) {
            return rejected("TARGET_DISABLED", "target is not registered, enabled or permitted");
        }
        if (decision.extractedInputs().keySet().stream().anyMatch(FORBIDDEN_MODEL_FIELDS::contains)) {
            return rejected("POLICY_REJECTED", "model attempted to set a protected execution field");
        }
        List<String> effectiveMissing = decision.missingInputs();
        if (!effectiveMissing.isEmpty()) {
            return clarified("missing required inputs: " + String.join(",", effectiveMissing));
        }

        Map<String, ValidatedIdentifier> identifiers = new LinkedHashMap<>();
        Map<String, Object> typed = new TreeMap<>();
        List<String> untrusted = new ArrayList<>();
        decision.extractedInputs().forEach((type, raw) -> {
            List<String> values = values(raw);
            if (!values.isEmpty()) typed.put(type, values.size() == 1 ? values.get(0) : values);
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                IdentifierSource source = context.sourceOf(type, value);
                identifiers.put(values.size() == 1 ? type : type + "[" + index + "]",
                        new ValidatedIdentifier(type, value, source));
                if (DANGEROUS_IDENTIFIERS.contains(type) && source == IdentifierSource.MODEL_INFERRED) {
                    untrusted.add(type + "=" + value);
                }
            }
        });
        if (!untrusted.isEmpty()) {
            return clarified("identifier source is MODEL_INFERRED: " + String.join(",", untrusted));
        }
        Map<String, Set<String>> typesByValue = new LinkedHashMap<>();
        identifiers.values().stream()
                .filter(identifier -> DANGEROUS_IDENTIFIERS.contains(identifier.type()))
                .forEach(identifier -> typesByValue.computeIfAbsent(identifier.value(), ignored -> new java.util.HashSet<>())
                        .add(identifier.type()));
        List<String> crossTypeCollisions = typesByValue.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
        if (!crossTypeCollisions.isEmpty()) {
            return clarified("one identifier value cannot satisfy multiple identifier types: "
                    + String.join(",", crossTypeCollisions));
        }

        ExecutionTargetId targetId = target.targetId();
        List<String> reasons = new ArrayList<>();
        RouteDisposition disposition;
        switch (targetId) {
            case PROCUREMENT_SOURCING -> {
                disposition = RouteDisposition.AUTO_DISPATCH;
                reasons.add("采购寻源和推荐为只读分析；RFQ 创建必须经人工审批，不支持创建采购订单");
            }
            default -> { return rejected("TARGET_DISABLED", "target is unsupported"); }
        }
        String digest = digest(targetId.name(), typed);
        return new RouteValidationResult(
                disposition,
                new ValidatedExecutionInput(targetId.name(), identifiers, typed, digest),
                List.copyOf(reasons), "");
    }

    /** 路由只确保同一会话有一个空 Case，不替 Agent 解析或写入采购需求。 */
    public void ensureProcurementCase(ExecutionDecision decision, RouteValidationContext context) {
        if (procurementCaseService == null || decision == null || context == null
                || !ExecutionTargetId.PROCUREMENT_SOURCING.name().equals(decision.targetId())) return;
        procurementCaseService.ensureCase(context.principal().tenantId(), context.workItem().conversationId(),
                context.principal().principalId());
    }

    private RouteValidationResult clarified(String reason) {
        return new RouteValidationResult(RouteDisposition.REQUIRE_CLARIFICATION, null, List.of(reason), "");
    }

    private RouteValidationResult rejected(String code, String reason) {
        return new RouteValidationResult(RouteDisposition.REJECT, null, List.of(reason), code);
    }

    private List<String> values(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        }
        String value = raw == null ? "" : String.valueOf(raw).trim();
        return value.isBlank() ? List.of() : List.of(value);
    }

    private String digest(String targetId, Map<String, Object> typed) {
        try {
            String canonical = targetId + "|" + objectMapper.writeValueAsString(new TreeMap<>(typed));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception exception) {
            throw new IllegalStateException("failed to digest validated execution input", exception);
        }
    }
}
