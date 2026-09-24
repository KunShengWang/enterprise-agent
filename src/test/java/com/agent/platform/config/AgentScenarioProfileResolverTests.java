package com.agent.platform.config;

import com.agent.platform.common.RetiredBusinessException;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentScenarioProfileResolverTests {
    private final InternalTestProfileFactory internal =
            new InternalTestProfileFactory();
    private final ProcurementSourcingExecutionProfileFactory procurement =
            new ProcurementSourcingExecutionProfileFactory();

    @Test
    void internalAndProcurementResolveWithoutAnyOrderCareFactory() {
        var resolver = new AgentScenarioProfileResolver(List.of(internal, procurement));
        assertEquals(internal.createProfile(), resolver.resolve("internal-test-v1").orElseThrow());
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
        var resolver = new AgentScenarioProfileResolver(List.of(internal, procurement, orderCare));
        assertThrows(RetiredBusinessException.class, () -> resolver.resolve(AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1));
    }

    @Test
    void blankStillUsesDefaultRuntimeAndUnknownScenarioStillFailsClosed() {
        var resolver = new AgentScenarioProfileResolver(List.of(internal, procurement));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("general-agent-v1"));
        assertTrue(resolver.resolve(null).isEmpty());
        assertTrue(resolver.resolve(" \t").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("user-defined-profile"));
    }

    @Test
    void duplicateScenarioRegistrationFailsInsteadOfOverridingPermissions() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentScenarioProfileResolver(List.of(internal, internal)));
    }

    @Test
    void springWiresOnlyInstalledFactoriesWithoutOrderCareBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentProperties.class);
            context.register(AgentScenarioProfileResolver.class, InternalTestProfileFactory.class,
                    ProcurementSourcingExecutionProfileFactory.class);
            context.refresh();
            var resolver = context.getBean(AgentScenarioProfileResolver.class);
            assertEquals(internal.createProfile(), resolver.resolve(internal.scenarioId()).orElseThrow());
            assertEquals(procurement.createProfile(), resolver.resolve(procurement.scenarioId()).orElseThrow());
        }
    }
}
