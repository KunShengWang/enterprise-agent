package com.agent.platform.runtime;

import com.agent.platform.ordercare.incident.tool.IncidentToolCatalog;
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
        ToolDefinition specialist = tool("delegate_order_analyst", Map.of(
                "initialOnly", true,
                "singleUse", true));
        ToolDefinition reviewer = tool("review_incident_evidence", Map.of(
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
        ToolDefinition specialist = tool("delegate_order_analyst", Map.of(
                "initialOnly", true,
                "singleUse", true));
        ToolDefinition reviewer = tool("review_incident_evidence", Map.of(
                "requiredFollowUpType", "REVIEW_READY",
                "singleUse", true));

        assertFalse(AgentCapabilityVisibilityPolicy.visibleToModel(
                specialist, reviewReady, List.of()));
        assertTrue(AgentCapabilityVisibilityPolicy.visibleToModel(
                reviewer, reviewReady, List.of()));
        assertFalse(AgentCapabilityVisibilityPolicy.visibleToModel(
                reviewer, reviewReady, List.of("review_incident_evidence")));
    }

    @Test
    void removesSingleUseSpecialistAfterItsFirstExecution() {
        ToolDefinition specialist = tool("delegate_order_analyst", Map.of(
                "initialOnly", true,
                "singleUse", true));

        assertFalse(AgentCapabilityVisibilityPolicy.visibleToModel(
                specialist, Map.of(), List.of("delegate_order_analyst")));
    }

    @Test
    void removesEachIncidentFactCapabilityAfterItsFirstExecution() {
        List<ToolDefinition> definitions = new IncidentToolCatalog().definitions();
        for (String capability : List.of(
                IncidentToolCatalog.ORDER_FACTS,
                IncidentToolCatalog.INVENTORY_FACTS,
                IncidentToolCatalog.MQ_FACTS)) {
            ToolDefinition definition = definitions.stream()
                    .filter(item -> capability.equals(item.name()))
                    .findFirst()
                    .orElseThrow();

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
