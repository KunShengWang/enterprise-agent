package com.agent.platform.trace;

public interface TraceRecorder {

    TraceContext start(String conversationId, String question);

    void record(TraceContext context, String stage, String detail);

    TraceSummary finish(TraceContext context);
}
