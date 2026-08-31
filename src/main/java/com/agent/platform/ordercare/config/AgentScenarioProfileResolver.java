package com.agent.platform.ordercare.config;

import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.config.GeneralAgentExecutionProfileFactory;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AgentScenarioProfileResolver {

    public static final String ORDERCARE_FLOWORDER_V1 = "ordercare-floworder-v1";
    public static final String GENERAL_AGENT_V1 = GeneralAgentExecutionProfileFactory.PROFILE_NAME;
    public static final String PROCUREMENT_SOURCING_READONLY_V1 = ProcurementSourcingExecutionProfileFactory.PROFILE_NAME;

    private final OrderCareExecutionProfileFactory orderCareProfileFactory;
    private final GeneralAgentExecutionProfileFactory generalProfileFactory;
    private final ProcurementSourcingExecutionProfileFactory procurementProfileFactory;

    @Autowired
    public AgentScenarioProfileResolver(OrderCareExecutionProfileFactory orderCareProfileFactory,
                                        GeneralAgentExecutionProfileFactory generalProfileFactory,
                                        ProcurementSourcingExecutionProfileFactory procurementProfileFactory) {
        this.orderCareProfileFactory = orderCareProfileFactory;
        this.generalProfileFactory = generalProfileFactory;
        this.procurementProfileFactory = procurementProfileFactory;
    }

    /** 保留旧测试和嵌入式调用方的构造入口。 */
    public AgentScenarioProfileResolver(OrderCareExecutionProfileFactory orderCareProfileFactory,
                                        GeneralAgentExecutionProfileFactory generalProfileFactory) {
        this(orderCareProfileFactory, generalProfileFactory, new ProcurementSourcingExecutionProfileFactory());
    }

    public Optional<AgentExecutionProfile> resolve(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return Optional.empty();
        }
        if (ORDERCARE_FLOWORDER_V1.equals(scenarioId.trim())) {
            return Optional.of(orderCareProfileFactory.createProfile());
        }
        if (GENERAL_AGENT_V1.equals(scenarioId.trim())) {
            return Optional.of(generalProfileFactory.createProfile());
        }
        if (PROCUREMENT_SOURCING_READONLY_V1.equals(scenarioId.trim())) {
            return Optional.of(procurementProfileFactory.createProfile());
        }
        throw new IllegalArgumentException("unsupported agent scenarioId: " + scenarioId);
    }
}
