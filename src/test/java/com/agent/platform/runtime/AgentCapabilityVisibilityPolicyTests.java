package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCapabilityVisibilityPolicyTests {

    @Test
    void exposesInitialSpecialistsButNotReviewer() {
        ToolDefinition specialist = tool("fixture_specialist", Map.of(
                "initialOnly", true,
                "singleUse", true));
        ToolDefinition reviewer = tool("fixture_reviewer", Map.of(
                "requiredFollowUpType", "REVIEW_READY",
                "singleUse", true));

        assertTrue(AgentCapabilityVisibilityPolicy.visibleToModel(
                specialist, Map.of(), List.of()));
        assertFalse(AgentCapabilityVisibilityPolicy.visibleToModel(
                reviewer, Map.of(), List.of()));
    }

    @Test
    void exposesOnlyUnusedReviewerAfterReviewReady() {
        Map<String, Object> reviewReady = Map.of("followUpType", "REVIEW_READY");
        ToolDefinition specialist = tool("fixture_specialist", Map.of(
                "initialOnly", true,
                "singleUse", true));
        ToolDefinition reviewer = tool("fixture_reviewer", Map.of(
                "requiredFollowUpType", "REVIEW_READY",
                "singleUse", true));

        assertFalse(AgentCapabilityVisibilityPolicy.visibleToModel(
                specialist, reviewReady, List.of()));
        assertTrue(AgentCapabilityVisibilityPolicy.visibleToModel(
                reviewer, reviewReady, List.of()));
        assertFalse(AgentCapabilityVisibilityPolicy.visibleToModel(
                reviewer, reviewReady, List.of("fixture_reviewer")));
    }

    @Test
    void removesSingleUseSpecialistAfterItsFirstExecution() {
        ToolDefinition specialist = tool("fixture_specialist", Map.of(
                "initialOnly", true,
                "singleUse", true));

        assertFalse(AgentCapabilityVisibilityPolicy.visibleToModel(
                specialist, Map.of(), List.of("fixture_specialist")));
    }

    @Test
    void removesEachSingleUseCapabilityAfterItsFirstExecution() {
        for (String capability : List.of("fixture_lookup", "fixture_analyze", "fixture_review")) {
            ToolDefinition definition = tool(capability, Map.of("singleUse", true));
            assertTrue(AgentCapabilityVisibilityPolicy.visibleToModel(
                    definition, Map.of(), List.of()), capability);
            assertFalse(AgentCapabilityVisibilityPolicy.visibleToModel(
                    definition, Map.of(), List.of(capability)), capability);
        }
    }

    private ToolDefinition tool(String name, Map<String, Object> metadata) {
        return new ToolDefinition(
                name, name, "{\"type\":\"object\"}", ToolRiskLevel.LOW, metadata);
    }
}
