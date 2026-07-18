package com.agent.platform.guardrail;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DefaultGuardrailService implements GuardrailService {

    private final PromptInjectionDetector promptInjectionDetector;

    private final SensitiveDataFilter sensitiveDataFilter;

    private final ToolPermissionPolicy toolPermissionPolicy;

    private final GuardrailAuditRecorder auditRecorder;

    public DefaultGuardrailService(PromptInjectionDetector promptInjectionDetector,
                                   SensitiveDataFilter sensitiveDataFilter,
                                   ToolPermissionPolicy toolPermissionPolicy,
                                   GuardrailAuditRecorder auditRecorder) {
        this.promptInjectionDetector = promptInjectionDetector;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.toolPermissionPolicy = toolPermissionPolicy;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 输入 Guardrail
     */
    @Override
    public GuardrailDecision checkInput(String userQuestion) {
        // 检测用户输入是否是不安全的操作
        GuardrailDecision injectionDecision = promptInjectionDetector.detect(userQuestion);
        if (injectionDecision.action() == GuardrailAction.BLOCK) {
            audit("input", injectionDecision, "", Map.of("detector", "prompt_injection"));
            return injectionDecision;
        }
        // 敏感数据过滤
        SensitiveDataFilterResult filterResult = sensitiveDataFilter.filter(userQuestion);
        if (!filterResult.categories().isEmpty()) {
            // 用户问题是敏感信息披露请求
            if (isSensitiveDisclosureRequest(userQuestion)) {
                GuardrailDecision decision = GuardrailDecision.block(
                        GuardrailStage.INPUT,
                        "敏感信息原样输出请求已被拦截，请先脱敏后再处理。categories=" + filterResult.categories()
                );
                audit("input", decision, filterResult.safeContent(), Map.of("categories", filterResult.categories(), "detector", "sensitive_disclosure"));
                return decision;
            }
            GuardrailDecision decision = GuardrailDecision.redact(
                    GuardrailStage.INPUT,
                    "sensitive input redacted: " + filterResult.categories(),
                    filterResult.safeContent()
            );
            audit("input", decision, filterResult.safeContent(), Map.of("categories", filterResult.categories()));
            return decision;
        }
        GuardrailDecision decision = GuardrailDecision.allow(GuardrailStage.INPUT, "input is allowed");
        audit("input", decision, "", Map.of());
        return decision;
    }

    /**
     * 敏感信息披露请求
     */
    private boolean isSensitiveDisclosureRequest(String userQuestion) {
        String normalized = userQuestion == null ? "" : userQuestion.toLowerCase(Locale.ROOT);
        boolean asksToExpose = containsAny(normalized, List.of("原样", "完整", "明文", "不要脱敏", "不脱敏"))
                || (containsAny(normalized, List.of("输出", "写进", "展示", "返回", "打印"))
                && containsAny(normalized, List.of("手机号", "身份证", "api key", "apikey", "密码", "密钥")));
        return asksToExpose;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 工具执行前单独检查权限和风险级别；高风险副作用不能直接执行
     */
    @Override
    public GuardrailDecision checkToolCall(ToolDefinition toolDefinition, ToolCallRequest toolCallRequest) {
        return checkToolCall(toolDefinition, toolCallRequest, null);
    }

    @Override
    public GuardrailDecision checkToolCall(ToolDefinition toolDefinition,
                                           ToolCallRequest toolCallRequest,
                                           ToolPolicyContext context) {
        // 工具风险检查
        GuardrailDecision decision = toolPermissionPolicy.check(toolDefinition, toolCallRequest, context);
        audit(toolDefinition == null ? "unknown" : toolDefinition.name(), decision, "", Map.of(
                "toolName", toolDefinition == null ? "unknown" : toolDefinition.name(),
                "riskLevel", toolDefinition == null || toolDefinition.riskLevel() == null ? "unknown" : toolDefinition.riskLevel().name(),
                "arguments", toolCallRequest == null ? Map.of() : toolCallRequest.arguments(),
                "userId", context == null ? "anonymous" : context.userId(),
                "tenantId", context == null ? "default" : context.tenantId()
        ));
        return decision;
    }

    @Override
    public GuardrailDecision checkOutput(String answer) {
        return outputDecision(answer, true);
    }

    @Override
    public GuardrailDecision previewOutput(String answer) {
        return outputDecision(answer, false);
    }

    private GuardrailDecision outputDecision(String answer, boolean auditDecision) {
        SensitiveDataFilterResult filterResult = sensitiveDataFilter.filter(answer);
        if (!filterResult.categories().isEmpty()) {
            GuardrailDecision decision = GuardrailDecision.redact(
                    GuardrailStage.OUTPUT,
                    "sensitive output redacted: " + filterResult.categories(),
                    filterResult.safeContent()
            );
            if (auditDecision) {
                audit("output", decision, filterResult.safeContent(), Map.of("categories", filterResult.categories()));
            }
            return decision;
        }
        GuardrailDecision decision = GuardrailDecision.allow(GuardrailStage.OUTPUT, "output is allowed");
        if (auditDecision) {
            audit("output", decision, "", Map.of());
        }
        return decision;
    }

    private void audit(String subject, GuardrailDecision decision, String safeContent, Map<String, Object> metadata) {
        auditRecorder.record(new GuardrailAuditRecord(
                UUID.randomUUID().toString(),
                decision.stage(),
                decision.action(),
                subject,
                decision.reason(),
                safeContent == null ? "" : safeContent,
                Instant.now(),
                metadata
        ));
    }
}
