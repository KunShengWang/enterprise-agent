package com.agent.platform.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class TraceContext {

    private final String traceId;

    private final String conversationId;

    private final List<TraceEvent> events = new ArrayList<>();

    public TraceContext(String traceId, String conversationId) {
        this.traceId = traceId;
        this.conversationId = conversationId;
    }

    public String traceId() {
        return traceId;
    }

    public String conversationId() {
        return conversationId;
    }

    public void addEvent(String stage, String detail) {
        events.add(new TraceEvent(Instant.now(), stage, detail));
    }

    public TraceSummary summary() {
        return new TraceSummary(traceId, conversationId, events);
    }
}
