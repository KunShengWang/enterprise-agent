package com.agent.platform.eval;

import java.util.List;
import java.util.Optional;

public interface EvalCaseRepository {

    List<EvalCase> list();

    Optional<EvalCase> find(String id);

    EvalCase save(EvalCase evalCase);

    boolean delete(String id);
}
