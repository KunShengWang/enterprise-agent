package com.agent.platform.eval;

import java.util.List;
import java.util.Optional;

public interface EvalReportRecorder {

    void record(EvalReport report);

    Optional<EvalReport> find(String runId);

    List<EvalReport> recent(int limit);

    void clear();
}
