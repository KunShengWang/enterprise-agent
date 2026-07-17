package com.agent.platform.eval;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.AgentResponse;
import com.agent.platform.agent.AgentRunStatus;
import com.agent.platform.agent.AgentStep;
import com.agent.platform.trace.TraceSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class DefaultAgentEvalRunnerRuntimeEvidenceTests {

    @Test
    void recognizesPersistedRuntimeToolEventsInsteadOfLegacyStepNames() {
        AgentExecutor executor = mock(AgentExecutor.class);
        EvalCaseRepository repository = mock(EvalCaseRepository.class);
        when(executor.execute(any())).thenReturn(new AgentResponse(
                "run-1",
                "session-1",
                AgentRunStatus.COMPLETED,
                "诊断代码为 REPLAY_CANDIDATE。",
                "",
                List.of(
                        new AgentStep("tool.requested", "COMPLETED", "model requested capability",
                                Map.of("toolName", "floworder_case_inspect")),
                        new AgentStep("tool.completed", "COMPLETED", "capability completed",
                                Map.of("toolName", "floworder_case_inspect", "success", true))
                ),
                new TraceSummary("trace-1", "session-1", List.of())
        ));
        EvalCase evalCase = new EvalCase(
                "case-1",
                "runtime tool evidence",
                "诊断 request-1",
                List.of("REPLAY_CANDIDATE"),
                List.of(),
                List.of("floworder_case_inspect"),
                false,
                true,
                0.7,
                Map.of("scenarioId", "ordercare-floworder-v1")
        );

        EvalReport report = new DefaultAgentEvalRunner(
                executor,
                repository,
                new HeuristicAnswerJudge()
        ).run(List.of(evalCase));

        assertEquals(1, report.passedCases());
        assertTrue(report.results().get(0).actualTools().contains("floworder_case_inspect"));
        assertTrue(report.results().get(0).grounded());
    }

    @Test
    void isolatesConversationForEveryEvalRun() {
        AgentExecutor executor = mock(AgentExecutor.class);
        EvalCaseRepository repository = mock(EvalCaseRepository.class);
        when(executor.execute(any())).thenReturn(new AgentResponse(
                "run-1",
                "session-1",
                AgentRunStatus.COMPLETED,
                "ok",
                "",
                List.of(),
                new TraceSummary("trace-1", "session-1", List.of())
        ));
        EvalCase evalCase = new EvalCase("case-1", "question", List.of("ok"), List.of());
        DefaultAgentEvalRunner runner = new DefaultAgentEvalRunner(
                executor,
                repository,
                new HeuristicAnswerJudge()
        );

        runner.run(List.of(evalCase));
        runner.run(List.of(evalCase));

        ArgumentCaptor<AgentRequest> requests = ArgumentCaptor.forClass(AgentRequest.class);
        verify(executor, atLeast(2)).execute(requests.capture());
        List<AgentRequest> values = requests.getAllValues();
        assertTrue(values.get(0).conversationId().startsWith("eval-"));
        assertTrue(values.get(1).conversationId().startsWith("eval-"));
        assertFalse(values.get(0).conversationId().equals(values.get(1).conversationId()));
    }
}
