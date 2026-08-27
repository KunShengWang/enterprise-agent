package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;

import java.util.Map;
import java.util.List;

/**
 * 控制在特定代理阶段可见的已授权功能。
 * 可见性并不赋予权力：执行配置文件和工具处理程序仍然具有权威性。
 */
final class AgentCapabilityVisibilityPolicy {

    static final String INITIAL_ONLY = "initialOnly";
    static final String REQUIRED_FOLLOW_UP_TYPE = "requiredFollowUpType";
    static final String FOLLOW_UP_TYPE = "followUpType";

    private AgentCapabilityVisibilityPolicy() {
    }

    static boolean visible(ToolDefinition definition, Map<String, Object> requestMetadata) {
        if (definition == null) {
            return false;
        }
        Map<String, Object> toolMetadata = definition.metadata();
        String followUpType = value(requestMetadata, FOLLOW_UP_TYPE);// 当前请求的"续跑类型"
        // 规则一：initialOnly 工具在续跑时不可见
        if (Boolean.TRUE.equals(toolMetadata.get(INITIAL_ONLY)) && !followUpType.isBlank()) {
            return false;// 工具只在初始回合可见，现在是续跑 → 隐藏
        }
        // 规则二：工具要求特定续跑类型，当前不匹配 → 不可见
        String requiredFollowUpType = value(toolMetadata, REQUIRED_FOLLOW_UP_TYPE);
        return requiredFollowUpType.isBlank() || requiredFollowUpType.equals(followUpType);
    }

    static boolean visibleToModel(ToolDefinition definition,
                                  Map<String, Object> requestMetadata,
                                  List<String> usedTools) {
        if (!visible(definition, requestMetadata)) {
            return false;
        }
        List<String> safeUsedTools = usedTools == null ? List.of() : usedTools;
        return !Boolean.TRUE.equals(definition.metadata().get("singleUse"))
                || !safeUsedTools.contains(definition.name());
    }

    private static String value(Map<String, Object> values, String key) {
        if (values == null || values.get(key) == null) {
            return "";
        }
        return String.valueOf(values.get(key)).trim();
    }
}
