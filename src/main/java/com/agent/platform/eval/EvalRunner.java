package com.agent.platform.eval;

import java.util.List;

public interface EvalRunner {

    EvalReport run(List<EvalCase> evalCases);
}
