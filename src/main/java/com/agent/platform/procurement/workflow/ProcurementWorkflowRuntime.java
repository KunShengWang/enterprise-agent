package com.agent.platform.procurement.workflow;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.runtime.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Public Procurement entry delegates macro execution to Java, and all model loops to the existing Runtime. */
@Primary
@Service
public final class ProcurementWorkflowRuntime implements AgentRuntime {
    private final DefaultAgentRuntime delegate;private final ProcurementWorkflowService workflow;private final AgentRunStore runs;private final ObjectMapper json;
    @Value("${enterprise-agent.procurement.erpnext.enabled:false}") private boolean enabled;
    public ProcurementWorkflowRuntime(DefaultAgentRuntime delegate,ProcurementWorkflowService workflow,AgentRunStore runs,ObjectMapper json){this.delegate=delegate;this.workflow=workflow;this.runs=runs;this.json=json;}
    public AgentRuntimeResult run(AgentRequest request,AgentEventListener listener){return run(request,null,listener);}
    public AgentRuntimeResult run(AgentRequest request,AgentExecutionProfile profile,AgentEventListener listener){
        boolean procurement=ProcurementSourcingExecutionProfileFactory.PROFILE_NAME.equals(request.scenarioId())
                || profile!=null && ProcurementSourcingExecutionProfileFactory.PROFILE_NAME.equals(profile.name());
        if(!enabled || !procurement)return delegate.run(request,profile,listener);
        if(Set.of("现在采购到哪一步？","现在采购到哪一步?","采购状态","查询采购状态").contains(request.question().trim()))
            return new AgentRuntimeResult("",request.conversationId(),AgentRunState.COMPLETED,AgentStopReason.COMPLETED,json.writeValueAsString(workflow.status(request)),"",null,List.of());
        return workflow.execute(request,listener);
    }
    public AgentRuntimeResult resume(String id,AgentEventListener listener){
        return runs.find(id).filter(r->r.executionProfile()!=null && "PROCUREMENT".equals(r.executionProfile().name())).isPresent()?workflow.resume(id,listener):delegate.resume(id,listener);
    }
    public boolean cancel(String id){return delegate.cancel(id);}
    public boolean pause(String id){return delegate.pause(id);}
}
