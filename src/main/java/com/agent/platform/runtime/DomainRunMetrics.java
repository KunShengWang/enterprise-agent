package com.agent.platform.runtime;

import com.agent.platform.procurement.agent.AgentRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Derived from existing persisted runs and tool receipts; never substitutes estimates for unavailable rubrics. */
public final class DomainRunMetrics {
    private DomainRunMetrics() {}
    public static Map<String,Object> snapshot(AgentRunRecord root,AgentRunStore runs,ToolExecutionStore executions){
        var byAgent=new TreeMap<String,Integer>();long turns=0,calls=0,tokens=0,failures=0;
        for(var e:executions.findByRun(root.runId())){
            if(!AgentRegistry.AGENT_TOOLS.contains(e.toolName()))continue;
            String childId=UUID.nameUUIDFromBytes((root.runId()+":"+e.toolCallId()).getBytes(StandardCharsets.UTF_8)).toString();
            var child=runs.find(childId).orElse(null);if(child==null)continue;
            byAgent.merge(child.executionProfile().name(),1,Integer::sum);
            if(child.budgetSnapshot()!=null){var b=child.budgetSnapshot();turns+=b.turns();calls+=b.toolCalls();tokens+=b.inputTokens()+b.outputTokens();}
            if(Set.of(AgentRunState.FAILED,AgentRunState.BLOCKED,AgentRunState.MANUAL_REVIEW).contains(child.state()))failures++;
        }
        var metrics=new LinkedHashMap<String,Object>();metrics.put("subagent_calls",byAgent.values().stream().mapToInt(Integer::intValue).sum());
        metrics.put("subagent_calls_by_agent",byAgent);metrics.put("child_turns",turns);metrics.put("child_tool_calls",calls);metrics.put("child_tokens",tokens);
        var b=root.budgetSnapshot();metrics.put("supervisor_tokens",b==null?0:Math.max(0,b.inputTokens()+b.outputTokens()-tokens));
        metrics.put("child_failures",failures);metrics.put("routing_errors","UNAVAILABLE_WITHOUT_TASK_RUBRIC");
        metrics.put("unnecessary_subagent_calls","UNAVAILABLE_WITHOUT_TASK_RUBRIC");return metrics;
    }
}
