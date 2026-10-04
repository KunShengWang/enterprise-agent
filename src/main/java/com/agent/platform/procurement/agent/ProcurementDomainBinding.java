package com.agent.platform.procurement.agent;

import com.agent.platform.runtime.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Grants only a persisted RFQ child access to its server-bound sibling Finalize receipt. */
@Component
public final class ProcurementDomainBinding {
    private final AgentRunStore runs;
    private final ToolExecutionStore executions;
    private final AgentRunLinks links;
    public ProcurementDomainBinding(AgentRunStore runs,ToolExecutionStore executions){
        this.runs=runs;this.executions=executions;this.links=new AgentRunLinks(runs);
    }
    public String businessSession(String runId,String fallback){return links.businessSession(runId,fallback);}
    public Optional<ToolExecutionRecord> boundFinalization(String runId){
        var child=runs.find(runId).orElse(null);
        if(child==null || child.executionProfile()==null || !AgentRegistry.RFQ.equals(child.executionProfile().name()))return Optional.empty();
        var parent=links.parent(runId).orElseThrow();
        String ref=Objects.toString(child.request().metadata().get(ChildRunLaunch.FINALIZE),"");
        var execution=executions.findToolExecution(ref).orElseThrow();
        var source=runs.find(execution.runId()).orElseThrow();
        if(!links.sameRoot(runId,source.runId()) || !AgentRegistry.SOURCING.equals(source.executionProfile().name())
                || source.state()!=AgentRunState.COMPLETED || !AgentRegistry.SUPERVISOR.equals(parent.executionProfile().name()))
            throw new IllegalArgumentException("invalid cross-child recommendation binding");
        return Optional.of(execution);
    }
    public boolean allowsFinalization(String runId,String executionId){
        return boundFinalization(runId).map(e->e.toolCallId().equals(executionId)).orElse(false);
    }
}
