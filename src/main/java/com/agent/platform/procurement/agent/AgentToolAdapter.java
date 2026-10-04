package com.agent.platform.procurement.agent;

import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Component
public final class AgentToolAdapter implements ContextualToolHandler {
    private final SubAgentExecutor executor;
    private final ProcurementCaseStore cases;
    private final ObjectMapper json;
    private final AgentRunStore runs;
    public AgentToolAdapter(SubAgentExecutor executor,ProcurementCaseStore cases,ObjectMapper json,AgentRunStore runs){
        this.executor=executor;this.cases=cases;this.json=json;this.runs=runs;
    }
    @Override public boolean supports(String tool){return AgentRegistry.ROOT_TOOLS.contains(tool);}
    @Override public ToolCallResult execute(ToolCallRequest request,ToolExecutionContext context){
        try{
            var run=runs.find(context.runId()).orElseThrow();
            if(run.executionProfile()==null || !AgentRegistry.SUPERVISOR.equals(run.executionProfile().name())
                    || !run.userId().equals(context.userId()) || !run.conversationId().equals(context.sessionId())
                    || !Objects.equals(run.request().metadata().get("tenantId"),context.tenantId()))
                return SubAgentExecutor.failure(request,"DOMAIN_DELEGATION_DENIED",false);
            if(!AgentRegistry.INSPECT.equals(request.toolName()))return executor.execute(request,context);
            if(!request.arguments().isEmpty())return SubAgentExecutor.failure(request,"INVALID_ARGUMENTS",false);
            var current=cases.findByTenantUserAndConversationId(context.tenantId(),context.userId(),context.sessionId());
            var out=new LinkedHashMap<String,Object>();
            out.put("case",current.<Object>map(c->Map.of("caseId",c.caseId(),"caseVersion",c.version(),"status",c.status(),"constraints",c.state())).orElse(Map.of("status","UNCREATED")));
            var finalization=executor.latestFinalization(run.runId());
            if(finalization!=null)out.put("recommendation",executor.recommendationSummary(finalization));
            out.put("approvalId",run.approvalId());
            out.put("rootExecutionSummary",executor.rootExecutionSummary(run.runId()));
            return new ToolCallResult(request.toolName(),true,json.writeValueAsString(out),"",Map.of("readOnly",true));
        }catch(RuntimeException failure){return SubAgentExecutor.failure(request,"CHILD_RUN_FAILED",true);}
    }
}
