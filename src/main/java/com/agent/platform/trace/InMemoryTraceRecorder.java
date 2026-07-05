package com.agent.platform.trace;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InMemoryTraceRecorder implements TraceRecorder {

    @Override
    public TraceContext start(String conversationId, String question) {
        TraceContext context = new TraceContext(UUID.randomUUID().toString(), conversationId);
        context.addEvent("trace.start", "question received");
        return context;
    }

    @Override
    public void record(TraceContext context, String stage, String detail) {
        if (context != null) {
            context.addEvent(stage, detail);
        }
    }

    @Override
    public TraceSummary finish(TraceContext context) {
        if (context == null) {
            return new TraceSummary("", "", java.util.List.of());
        }
        context.addEvent("trace.finish", "agent run finished");
        return context.summary();
    }
}
