package com.agent.platform.eval;

import com.agent.platform.agent.AgentResponse;

public interface AnswerJudge {

    AnswerJudgement judge(EvalCase evalCase, AgentResponse response);
}
