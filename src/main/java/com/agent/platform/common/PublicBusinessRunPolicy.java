package com.agent.platform.common;

import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.runtime.AgentRunRecord;

/** External resume boundary only; internal Runtime profiles and stored checkpoints are unchanged. */
public final class PublicBusinessRunPolicy {
    private PublicBusinessRunPolicy() { }

    public static boolean resumable(AgentRunRecord run) {
        return run != null && run.executionProfile() != null
                && ProcurementSourcingExecutionProfileFactory.PROFILE_NAME.equals(run.executionProfile().name())
                && !BusinessRetirementPolicy.retiredScenario(run.request() == null ? null : run.request().scenarioId())
                && !BusinessRetirementPolicy.retiredTarget(run.request() == null ? null
                    : String.valueOf(run.request().metadata().get("executionTarget")));
    }

    public static void requireResumable(AgentRunRecord run) {
        if (!resumable(run)) throw new RetiredBusinessException();
    }
}
