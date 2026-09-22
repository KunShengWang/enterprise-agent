package com.agent.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "enterprise-agent.ordercare.incident-command.enabled=true",
        "enterprise-agent.ordercare.incident-command.phase3-enabled=true",
        "enterprise-agent.ordercare.floworder-base-url=http://127.0.0.1:1",
        "enterprise-agent.ordercare.incident.rabbitmq-management.base-url=http://127.0.0.1:1"
})
class EnterpriseAgentApplicationTests {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void contextLoads() {
        var adapters = context.getBeansOfType(com.agent.platform.workbench.dispatch.ExecutionAdapter.class).values();
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(
                com.agent.platform.workbench.target.ExecutionTargetId.GENERAL_AGENT,
                com.agent.platform.workbench.target.ExecutionTargetId.PROCUREMENT_SOURCING),
                adapters.stream().map(com.agent.platform.workbench.dispatch.ExecutionAdapter::targetId)
                        .collect(java.util.stream.Collectors.toSet()));
        org.junit.jupiter.api.Assertions.assertEquals(2, adapters.size());
        org.junit.jupiter.api.Assertions.assertNotNull(context.getBean(com.agent.platform.web.EvalController.class));
        org.junit.jupiter.api.Assertions.assertTrue(context.getBeansOfType(com.agent.platform.workbench.application.WorkEventProjectionContributor.class).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(context.getBeansOfType(com.agent.platform.workbench.application.WorkExecutionTreeContributor.class).isEmpty());
    }

    @Test
    void applicationAssemblyContainsOnlyCurrentProfilesAndNoRetiredDomainBeans() {
        var factories = context.getBeansOfType(com.agent.platform.config.AgentScenarioProfileFactory.class).values();
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("general-agent-v1", "procurement-sourcing-rfq-v1"),
                factories.stream().map(com.agent.platform.config.AgentScenarioProfileFactory::scenarioId)
                        .collect(java.util.stream.Collectors.toSet()));
        var resolver = context.getBean(com.agent.platform.config.AgentScenarioProfileResolver.class);
        factories.forEach(factory -> org.junit.jupiter.api.Assertions.assertEquals(factory.createProfile(),
                resolver.resolve(factory.scenarioId()).orElseThrow()));
        org.junit.jupiter.api.Assertions.assertThrows(com.agent.platform.common.RetiredBusinessException.class,
                () -> resolver.resolve(com.agent.platform.config.AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1));
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            org.junit.jupiter.api.Assertions.assertFalse(type != null && type.getName().startsWith("com.agent.platform.ordercare."), name);
        }
        org.junit.jupiter.api.Assertions.assertTrue(!context.containsBean("defaultIncidentBudgetService"));
        var tools = context.getBean(com.agent.platform.tool.ToolRegistry.class).listTools();
        org.junit.jupiter.api.Assertions.assertFalse(tools.isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(tools.stream().noneMatch(tool ->
                com.agent.platform.common.BusinessRetirementPolicy.retiredTool(tool.name())));
        org.junit.jupiter.api.Assertions.assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("procurement_create_rfq")));
        System.out.println("CURRENT_PROFILE_IDS=" + factories.stream().map(com.agent.platform.config.AgentScenarioProfileFactory::scenarioId).sorted().toList());
        System.out.println("CURRENT_TOOL_NAMES=" + tools.stream().map(com.agent.platform.tool.ToolDefinition::name).sorted().toList());
    }
}
