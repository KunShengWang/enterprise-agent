package com.agent.platform.guardrail;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class DefaultGuardrailService implements GuardrailService {

    private final List<String> blockedInputPhrases = List.of(
            "忽略之前",
            "绕过审批",
            "导出系统密钥",
            "删除所有客户",
            "清空数据库",
            "disable audit",
            "ignore previous"
    );

    private final Pattern apiKeyPattern = Pattern.compile("sk-[a-zA-Z0-9]{8,}");

    private final Pattern phonePattern = Pattern.compile("1[3-9]\\d{9}");

    @Override
    public GuardrailDecision checkInput(String userQuestion) {
        String normalized = userQuestion == null ? "" : userQuestion.toLowerCase(Locale.ROOT);
        for (String phrase : blockedInputPhrases) {
            if (normalized.contains(phrase.toLowerCase(Locale.ROOT))) {
                return GuardrailDecision.block(GuardrailStage.INPUT, "blocked suspicious or dangerous input: " + phrase);
            }
        }
        return GuardrailDecision.allow(GuardrailStage.INPUT, "input is allowed");
    }

    @Override
    public GuardrailDecision checkToolCall(ToolDefinition toolDefinition, ToolCallRequest toolCallRequest) {
        if (toolDefinition == null) {
            return GuardrailDecision.block(GuardrailStage.TOOL, "unknown tool cannot be executed");
        }
        if (toolDefinition.riskLevel() == ToolRiskLevel.CRITICAL) {
            return GuardrailDecision.block(GuardrailStage.TOOL, "critical risk tool is blocked: " + toolDefinition.name());
        }
        if (toolDefinition.riskLevel() == ToolRiskLevel.HIGH) {
            return GuardrailDecision.requireApproval(GuardrailStage.TOOL, "high risk tool requires approval: " + toolDefinition.name());
        }
        return GuardrailDecision.allow(GuardrailStage.TOOL, "tool call is allowed: " + toolDefinition.name());
    }

    @Override
    public GuardrailDecision checkOutput(String answer) {
        String safeContent = answer == null ? "" : answer;
        safeContent = apiKeyPattern.matcher(safeContent).replaceAll("[API_KEY_REDACTED]");
        safeContent = phonePattern.matcher(safeContent).replaceAll("[PHONE_REDACTED]");
        if (!safeContent.equals(answer)) {
            return GuardrailDecision.redact(GuardrailStage.OUTPUT, "sensitive output redacted", safeContent);
        }
        return GuardrailDecision.allow(GuardrailStage.OUTPUT, "output is allowed");
    }
}
