package com.agent.platform.config;

import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.DefaultAgentCapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralAgentExecutionProfileFactoryTests {

    @Test
    void generalProfileExposesOnlyReadOnlyGeneralCapabilities() {
        AgentExecutionProfile profile = new GeneralAgentExecutionProfileFactory(new AgentProperties())
                .createProfile();

        assertEquals(GeneralAgentExecutionProfileFactory.PROFILE_NAME, profile.name());
        assertEquals(Set.of(
                DefaultAgentCapabilityRegistry.KNOWLEDGE_SEARCH,
                DefaultAgentCapabilityRegistry.SKILL_CATALOG,
                "ticket_status"
        ), profile.allowedCapabilities());
        assertTrue(profile.longTermMemoryEnabled());
        assertTrue(profile.systemPrompt().contains("当前消息省略的主题能够从最近一轮唯一确定时"));
        assertTrue(profile.systemPrompt().contains("给出 Java 代码解释"));
        assertTrue(profile.systemPrompt().contains("不得输出“用户要求”“根据上下文”“决定”"));
        assertFalse(profile.allowedCapabilities().stream().anyMatch(name ->
                name.startsWith("floworder_") || name.contains("execute") || name.contains("close")));
    }
}
