package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;

import java.util.Map;
import java.util.List;

/**
 * Controls which already-authorized capabilities are visible during a specific Agent phase.
 * Visibility never grants authority: the execution profile and Tool handler remain authoritative.
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
        String followUpType = value(requestMetadata, FOLLOW_UP_TYPE);
        if (Boolean.TRUE.equals(toolMetadata.get(INITIAL_ONLY)) && !followUpType.isBlank()) {
            return false;
        }
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
