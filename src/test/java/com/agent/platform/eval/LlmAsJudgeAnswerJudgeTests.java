package com.agent.platform.eval;

import com.agent.platform.agent.AgentResponse;
import com.agent.platform.agent.AgentRunStatus;
import com.agent.platform.agent.AgentStep;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.llm.LlmService;
import com.agent.platform.trace.TraceSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmAsJudgeAnswerJudgeTests {

    @Test
    void structuredTraceOwnsGroundednessWhenModelJudgeMisreadsEvidence() {
        LlmService llmService = mock(LlmService.class);
        AgentProperties properties = mock(AgentProperties.class);
        when(properties.isMockMode()).thenReturn(false);
        when(llmService.complete(any())).thenReturn(
                "score: 1.0\ngrounded: false\nreason: model overlooked the tool trace"
        );
        LlmAsJudgeAnswerJudge judge = new LlmAsJudgeAnswerJudge(
                llmService, new HeuristicAnswerJudge(), properties
        );
        AgentResponse response = new AgentResponse(
                "run-1", "session-1", AgentRunStatus.COMPLETED, "REPLAY_CANDIDATE", "",
                List.of(new AgentStep(
                        "tool.completed", "COMPLETED", "case inspected",
                        Map.of("toolName", "floworder_case_inspect", "success", true)
                )),
                new TraceSummary("trace-1", "session-1", List.of())
        );

        AnswerJudgement result = judge.judge(evalCase(), response);

        assertTrue(result.grounded());
        assertTrue(result.reason().contains("deterministicGrounded=true"));
        assertTrue(result.reason().contains("modelGrounded=false"));
    }

    @Test
    void modelJudgeCannotInventGroundingWithoutStructuredEvidence() {
        LlmService llmService = mock(LlmService.class);
        AgentProperties properties = mock(AgentProperties.class);
        when(properties.isMockMode()).thenReturn(false);
        when(llmService.complete(any())).thenReturn(
                "score: 1.0\ngrounded: true\nreason: looks plausible"
        );
        LlmAsJudgeAnswerJudge judge = new LlmAsJudgeAnswerJudge(
                llmService, new HeuristicAnswerJudge(), properties
        );
        AgentResponse response = new AgentResponse(
                "run-2", "session-2", AgentRunStatus.COMPLETED, "REPLAY_CANDIDATE", "",
                List.of(),
                new TraceSummary("trace-2", "session-2", List.of())
        );

        AnswerJudgement result = judge.judge(evalCase(), response);

        assertFalse(result.grounded());
        assertTrue(result.reason().contains("deterministicGrounded=false"));
        assertTrue(result.reason().contains("modelGrounded=true"));
    }

    private EvalCase evalCase() {
        return new EvalCase(
                "case-grounding", "grounding", "诊断 requestId=R1",
                List.of("REPLAY_CANDIDATE"), List.of(), List.of("floworder_case_inspect"),
                false, true, 0.7, Map.of()
        );
    }
}
