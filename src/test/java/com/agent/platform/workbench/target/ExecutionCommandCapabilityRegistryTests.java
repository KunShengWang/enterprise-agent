package com.agent.platform.workbench.target;

import com.agent.platform.workbench.model.WorkCommandType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionCommandCapabilityRegistryTests {

    private final ExecutionCommandCapabilityRegistry registry = new ExecutionCommandCapabilityRegistry();

    @Test
    void frozenMatrixExposesRuntimeCommandsOnlyForGeneralAndOrderCare() {
        for (ExecutionTargetId target : ExecutionTargetId.values()) {
            ExecutionCommandCapabilities capabilities = registry.require(target);
            assertEquals(ExecutionCommandSupport.PRODUCT_ONLY,
                    capabilities.support(WorkCommandType.ABANDON_ACTIVE_WORK));
            assertEquals(ExecutionCommandSupport.UNSUPPORTED,
                    capabilities.support(WorkCommandType.ADD_INPUT_TO_ACTIVE_WORK));
            ExecutionCommandSupport expected = target == ExecutionTargetId.GENERAL_AGENT
                    || target == ExecutionTargetId.ORDERCARE_CASE
                    ? ExecutionCommandSupport.SUPPORTED_EXISTING_RUNTIME
                    : ExecutionCommandSupport.UNSUPPORTED;
            assertEquals(expected, capabilities.support(WorkCommandType.PAUSE_ACTIVE_WORK));
            assertEquals(expected, capabilities.support(WorkCommandType.RESUME_ACTIVE_WORK));
            assertEquals(expected, capabilities.support(WorkCommandType.CANCEL_ACTIVE_WORK));
        }
    }
}
