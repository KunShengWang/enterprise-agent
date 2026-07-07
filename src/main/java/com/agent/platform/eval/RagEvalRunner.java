package com.agent.platform.eval;

import java.util.List;

public interface RagEvalRunner {

    RagEvalReport run(List<RagEvalCase> cases);
}
