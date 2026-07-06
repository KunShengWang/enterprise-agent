package com.agent.platform.eval;

import java.util.List;

public interface EvalEventRecorder {

    void record(AgentRunEvalEvent event);

    List<AgentRunEvalEvent> snapshot();
}
