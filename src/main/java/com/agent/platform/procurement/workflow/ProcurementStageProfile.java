package com.agent.platform.procurement.workflow;

import com.agent.platform.runtime.*;
import java.util.Set;

public record ProcurementStageProfile(ProcurementStage stage, String instructions, ContextPolicy contextPolicy,
        Set<String> toolScope, String outputSchema, AgentRunLimits runBudget) {
    public StructuredOutputSpec outputSpec(){return new StructuredOutputSpec(switch(stage){
        case REQUISITION -> RequisitionStageResult.class;
        case SOURCING -> SourcingStageResult.class;
        case RFQ -> RfqStageResult.class;
    });}
    public AgentExecutionProfile executionProfile() {
        return new AgentExecutionProfile("PROCUREMENT", instructions + "\n最终只输出符合以下 JSON Schema 的 JSON 对象，不使用 markdown fence：\n" + outputSchema,
                toolScope, runBudget, false, contextPolicy, outputSpec());
    }
}
