package com.agent.platform.tool;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.llm.MockLlmService;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.router.IntentRoute;
import com.agent.platform.router.IntentType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmToolCallPlannerTests {

    @Test
    void shouldFallbackToCreateTicketPlanWhenModelDoesNotReturnJson() {
        LlmToolCallPlanner planner = new LlmToolCallPlanner(new MockLlmService(), new ObjectMapper());
        LocalToolRegistry registry = new LocalToolRegistry();

        ToolCallPlan plan = planner.plan(
                new AgentRequest("c1", "u1", "创建一个登录失败的 P1 故障工单", Map.of()),
                ConversationMemory.empty("c1"),
                new IntentRoute(IntentType.TOOL, "tool intent", Map.of()),
                registry.listTools(),
                List.of()
        );

        assertThat(plan.shouldCallTool()).isTrue();
        assertThat(plan.toolName()).isEqualTo("ticket_create");
        assertThat(plan.arguments()).containsEntry("priority", "P1");
    }

    @Test
    void shouldStopAfterPreviousToolResultWhenNoFollowUpNeeded() {
        LlmToolCallPlanner planner = new LlmToolCallPlanner(new MockLlmService(), new ObjectMapper());
        LocalToolRegistry registry = new LocalToolRegistry();

        ToolCallPlan plan = planner.plan(
                new AgentRequest("c1", "u1", "查询工单 T1001 的状态", Map.of()),
                ConversationMemory.empty("c1"),
                new IntentRoute(IntentType.TOOL, "tool intent", Map.of("toolName", "ticket_status")),
                registry.listTools(),
                List.of(new ToolCallResult("ticket_status", true, "工单 T1001 当前状态为处理中。", "", Map.of()))
        );

        assertThat(plan.shouldCallTool()).isFalse();
    }
}
