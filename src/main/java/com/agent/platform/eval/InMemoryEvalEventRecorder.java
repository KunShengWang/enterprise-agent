package com.agent.platform.eval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
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
