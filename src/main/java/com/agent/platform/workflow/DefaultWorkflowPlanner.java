package com.agent.platform.workflow;

import com.agent.platform.router.IntentRoute;
import com.agent.platform.router.IntentType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DefaultWorkflowPlanner implements WorkflowPlanner {

    @Override
    public WorkflowExecutionPlan plan(String traceId, String conversationId, IntentRoute route) {
        IntentType type = route == null ? IntentType.CHAT : route.type();
        List<WorkflowNode> nodes = new ArrayList<>(List.of(
                WorkflowNode.START,
                WorkflowNode.LOAD_MEMORY,
                WorkflowNode.INPUT_GUARDRAIL,
                WorkflowNode.SELECT_SKILL,
                WorkflowNode.ROUTE_INTENT
        ));
        if (type == IntentType.CLARIFY) {
            nodes.add(WorkflowNode.CLARIFY);
        }
        else {
            nodes.add(WorkflowNode.QUERY_REWRITE);
            if (type == IntentType.RAG) {
                nodes.add(WorkflowNode.RAG_RETRIEVE);
            }
            else if (type == IntentType.TOOL) {
                nodes.addAll(List.of(
                        WorkflowNode.TOOL_REGISTRY,
                        WorkflowNode.TOOL_PLAN,
                        WorkflowNode.TOOL_GUARDRAIL,
                        WorkflowNode.TOOL_APPROVAL,
                        WorkflowNode.TOOL_EXECUTE
                ));
            }
            else {
                nodes.add(WorkflowNode.CHAT_FALLBACK);
            }
            nodes.addAll(List.of(
                    WorkflowNode.PROMPT_ASSEMBLE,
                    WorkflowNode.LLM_CALL,
                    WorkflowNode.OUTPUT_GUARDRAIL,
                    WorkflowNode.SAVE_MEMORY
            ));
        }
        nodes.addAll(List.of(WorkflowNode.EVAL_RECORD, WorkflowNode.FINISH));
        return new WorkflowExecutionPlan(
                traceId,
                conversationId,
                type.name(),
                nodes,
                transitions(nodes, type),
                true,
                true,
                Instant.now(),
                Map.of("routeReason", route == null ? "" : route.reason(), "routeSlots", route == null ? Map.of() : route.slots())
        );
    }

    @Override
    public WorkflowNode mapStepName(String stepName) {
        String value = stepName == null ? "" : stepName.toLowerCase();
        if (value.contains("memory.load")) return WorkflowNode.LOAD_MEMORY;
        if (value.contains("guardrail.input")) return WorkflowNode.INPUT_GUARDRAIL;
        if (value.contains("skill.select")) return WorkflowNode.SELECT_SKILL;
        if (value.contains("intent.route")) return WorkflowNode.ROUTE_INTENT;
        if (value.contains("query.rewrite")) return WorkflowNode.QUERY_REWRITE;
        if (value.contains("rag.retrieve")) return WorkflowNode.RAG_RETRIEVE;
        if (value.contains("tool.registry")) return WorkflowNode.TOOL_REGISTRY;
        if (value.contains("tool.plan")) return WorkflowNode.TOOL_PLAN;
        if (value.contains("guardrail.tool")) return WorkflowNode.TOOL_GUARDRAIL;
        if (value.contains("approval")) return WorkflowNode.TOOL_APPROVAL;
        if (value.contains("tool.execute")) return WorkflowNode.TOOL_EXECUTE;
        if (value.contains("chat.fallback")) return WorkflowNode.CHAT_FALLBACK;
        if (value.contains("prompt.assemble")) return WorkflowNode.PROMPT_ASSEMBLE;
        if (value.contains("llm.call")) return WorkflowNode.LLM_CALL;
        if (value.contains("guardrail.output")) return WorkflowNode.OUTPUT_GUARDRAIL;
        if (value.contains("conversation.save")) return WorkflowNode.SAVE_MEMORY;
        if (value.contains("eval.record")) return WorkflowNode.EVAL_RECORD;
        if (value.contains("error")) return WorkflowNode.FAILED;
        return WorkflowNode.START;
    }

    @Override
    public boolean retryable(WorkflowNode node) {
        return node == WorkflowNode.RAG_RETRIEVE
                || node == WorkflowNode.TOOL_EXECUTE
                || node == WorkflowNode.LLM_CALL;
    }

    @Override
    public boolean resumable(WorkflowNode node) {
        return node == WorkflowNode.TOOL_APPROVAL
                || node == WorkflowNode.TOOL_EXECUTE
                || node == WorkflowNode.LLM_CALL
                || node == WorkflowNode.OUTPUT_GUARDRAIL;
    }

    private List<WorkflowTransition> transitions(List<WorkflowNode> nodes, IntentType type) {
        List<WorkflowTransition> transitions = new ArrayList<>();
        for (int index = 0; index < nodes.size() - 1; index++) {
            String condition = index == 4 ? "route=" + type.name() : "success";
            transitions.add(new WorkflowTransition(nodes.get(index), nodes.get(index + 1), condition));
        }
        return transitions;
    }
}
