package com.agent.platform.workbench.target;

import com.agent.platform.workbench.model.WorkCommandType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionCommandCapabilityRegistryTests {

    private final ExecutionCommandCapabilityRegistry registry = new ExecutionCommandCapabilityRegistry();

    @Test
    void commandMatrixExposesRuntimeControlsForRunBackedTargets() {
        for (ExecutionTargetId target : ExecutionTargetId.values()) {
            ExecutionCommandCapabilities capabilities = registry.require(target);
            assertEquals(target.executable() ? ExecutionCommandSupport.PRODUCT_ONLY : ExecutionCommandSupport.UNSUPPORTED,
                    capabilities.support(WorkCommandType.ABANDON_ACTIVE_WORK));
            assertEquals(ExecutionCommandSupport.UNSUPPORTED,
                    capabilities.support(WorkCommandType.ADD_INPUT_TO_ACTIVE_WORK));
            ExecutionCommandSupport expected = target == ExecutionTargetId.PROCUREMENT_SOURCING
                    ? ExecutionCommandSupport.SUPPORTED_EXISTING_RUNTIME
                    : ExecutionCommandSupport.UNSUPPORTED;
            assertEquals(expected, capabilities.support(WorkCommandType.PAUSE_ACTIVE_WORK));
            assertEquals(expected, capabilities.support(WorkCommandType.RESUME_ACTIVE_WORK));
            assertEquals(expected, capabilities.support(WorkCommandType.CANCEL_ACTIVE_WORK));
        }
    }
}
