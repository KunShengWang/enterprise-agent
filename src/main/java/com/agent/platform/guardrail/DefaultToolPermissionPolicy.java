package com.agent.platform.guardrail;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DefaultToolPermissionPolicy implements ToolPermissionPolicy {

    /**
     * 工具风险检查
     */
    @Override
    public GuardrailDecision check(ToolDefinition toolDefinition, ToolCallRequest toolCallRequest) {
        if (toolDefinition == null) {
            return GuardrailDecision.block(GuardrailStage.TOOL, "unknown tool cannot be executed");
        }
        if (toolDefinition.riskLevel() == ToolRiskLevel.CRITICAL) {
            return GuardrailDecision.block(GuardrailStage.TOOL, "critical risk tool is blocked: " + toolDefinition.name());
        }
        if (toolDefinition.riskLevel() == ToolRiskLevel.HIGH) {
            return GuardrailDecision.requireApproval(GuardrailStage.TOOL, "high risk tool requires approval: " + toolDefinition.name());
        }
        if (isFilesystemWrite(toolDefinition, toolCallRequest)) {
            return GuardrailDecision.requireApproval(GuardrailStage.TOOL, "filesystem write tool requires approval: " + toolDefinition.name());
        }
        return GuardrailDecision.allow(GuardrailStage.TOOL, "tool call is allowed: " + toolDefinition.name());
    }

    private boolean isFilesystemWrite(ToolDefinition toolDefinition, ToolCallRequest request) {
        String name = toolDefinition.name() == null ? "" : toolDefinition.name().toLowerCase();
        if (!(name.contains("filesystem") || name.contains("file"))) {
            return false;
        }
        if (name.contains("write") || name.contains("delete") || name.contains("move")) {
            return true;
        }
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        return arguments.keySet().stream().anyMatch(key -> key.toLowerCase().contains("write") || key.toLowerCase().contains("delete"));
    }
}
