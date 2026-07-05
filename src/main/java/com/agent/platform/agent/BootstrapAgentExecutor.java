package com.agent.platform.agent;

import com.agent.platform.trace.TraceContext;
import com.agent.platform.trace.TraceRecorder;
import com.agent.platform.trace.TraceSummary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class BootstrapAgentExecutor implements AgentExecutor {

    private static final String DEFAULT_CONVERSATION_ID = "default-conversation";

    private final TraceRecorder traceRecorder;

    public BootstrapAgentExecutor(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        TraceContext trace = traceRecorder.start(conversationId, request.question());
        List<AgentStep> steps = new ArrayList<>();

        addStep(trace, steps, "memory.load", "SKIPPED", "V0 skeleton has no memory implementation yet");
        addStep(trace, steps, "guardrail.input", "SKIPPED", "V0 skeleton has no guardrail implementation yet");
        addStep(trace, steps, "intent.route", "READY", "IntentRouter extension point is ready");
        addStep(trace, steps, "rag.or.tool", "READY", "RAG and ToolExecutor extension points are ready");
        addStep(trace, steps, "llm.call", "MOCKED", "Real Spring AI model call will be added in V1");
        addStep(trace, steps, "eval.record", "READY", "EvalRunner extension point is ready");

        TraceSummary summary = traceRecorder.finish(trace);
        String answer = "V0 agent skeleton is ready. Next step is implementing the V1 execution chain for question: "
                + request.question();
        return new AgentResponse(conversationId, AgentRunStatus.COMPLETED, answer, steps, summary);
    }

    private void addStep(TraceContext trace, List<AgentStep> steps, String name, String status, String summary) {
        steps.add(new AgentStep(name, status, summary));
        traceRecorder.record(trace, name, summary);
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return DEFAULT_CONVERSATION_ID;
        }
        return conversationId.trim();
    }
}
