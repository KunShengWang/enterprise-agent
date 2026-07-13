package com.agent.platform.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Deprecated(forRemoval = true)
public class InMemoryEvalEventRecorder implements EvalEventRecorder {

    private final List<AgentRunEvalEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void record(AgentRunEvalEvent event) {
        events.add(event);
    }

    @Override
    public List<AgentRunEvalEvent> snapshot() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }
}
