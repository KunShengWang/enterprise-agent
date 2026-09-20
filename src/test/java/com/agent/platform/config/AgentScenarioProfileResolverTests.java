package com.agent.platform.config;

import com.agent.platform.common.RetiredBusinessException;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentScenarioProfileResolverTests {
    private final GeneralAgentExecutionProfileFactory general =
            new GeneralAgentExecutionProfileFactory(new AgentProperties());
    private final ProcurementSourcingExecutionProfileFactory procurement =
            new ProcurementSourcingExecutionProfileFactory();

    @Test
    void generalAndProcurementResolveWithoutAnyOrderCareFactory() {
        var resolver = new AgentScenarioProfileResolver(List.of(general, procurement));
        assertEquals(general.createProfile(), resolver.resolve(AgentScenarioProfileResolver.GENERAL_AGENT_V1).orElseThrow());
        assertEquals(procurement.createProfile(), resolver.resolve(
                "  " + AgentScenarioProfileResolver.PROCUREMENT_SOURCING_READONLY_V1 + "  ").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1));
    }

    @Test
    void retiredFactoryCannotRestoreAnExecutableProfile() {
        AgentScenarioProfileFactory orderCare = new AgentScenarioProfileFactory() {
            public String scenarioId() { return AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1; }
            public com.agent.platform.runtime.AgentExecutionProfile createProfile() {
                throw new AssertionError("retired factory must never execute");
            }
        };
        var resolver = new AgentScenarioProfileResolver(List.of(general, procurement, orderCare));
        assertThrows(RetiredBusinessException.class, () -> resolver.resolve(AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1));
    }

    @Test
    void blankStillUsesDefaultRuntimeAndUnknownScenarioStillFailsClosed() {
        var resolver = new AgentScenarioProfileResolver(List.of(general, procurement));
        assertTrue(resolver.resolve(null).isEmpty());
        assertTrue(resolver.resolve(" \t").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("user-defined-profile"));
    }

    @Test
    void duplicateScenarioRegistrationFailsInsteadOfOverridingPermissions() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentScenarioProfileResolver(List.of(general, general)));
    }

    @Test
    void springWiresOnlyInstalledFactoriesWithoutOrderCareBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentProperties.class);
            context.register(AgentScenarioProfileResolver.class, GeneralAgentExecutionProfileFactory.class,
                    ProcurementSourcingExecutionProfileFactory.class);
            context.refresh();
            var resolver = context.getBean(AgentScenarioProfileResolver.class);
            assertEquals(general.createProfile(), resolver.resolve(general.scenarioId()).orElseThrow());
            assertEquals(procurement.createProfile(), resolver.resolve(procurement.scenarioId()).orElseThrow());
        }
    }
}
