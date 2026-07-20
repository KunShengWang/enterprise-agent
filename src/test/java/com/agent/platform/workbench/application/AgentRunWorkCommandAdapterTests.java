package com.agent.platform.workbench.application;

import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunWorkCommandAdapterTests {

    @Test
    void resumeUsesTheLinkedRunIdAndReturnsTheSameAuthoritativeRun() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        AgentRunRecord before = run("run-1", AgentRunState.PAUSED, 3, 0);
        AgentRunRecord after = run("run-1", AgentRunState.COMPLETED, 6, 1);
        AgentRuntimeResult runtimeResult = mock(AgentRuntimeResult.class);
        when(runtimeResult.state()).thenReturn(AgentRunState.COMPLETED);
        when(runs.find("run-1")).thenReturn(Optional.of(before), Optional.of(after));
        when(runtime.resume("run-1")).thenReturn(runtimeResult);

        AgentRunCommandResult result = new AgentRunWorkCommandAdapter(runtime, runs).execute(
                principal(), work("GENERAL_AGENT", "run-1"), WorkCommandType.RESUME_ACTIVE_WORK);

        assertTrue(result.accepted());
        assertTrue(result.underlyingExecutionChanged());
        assertEquals("run-1", result.after().runId());
        assertEquals(1, result.after().resumeCount());
        verify(runtime).resume("run-1");
    }

    @Test
    void terminalRunIsRejectedBeforeCallingRuntimeControl() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        AgentRunRecord completed = run("run-1", AgentRunState.COMPLETED, 2, 0);
        when(runs.find("run-1")).thenReturn(Optional.of(completed));

        AgentRunCommandResult result = new AgentRunWorkCommandAdapter(runtime, runs).execute(
                principal(), work("ORDERCARE_CASE", "run-1"), WorkCommandType.CANCEL_ACTIVE_WORK);

        assertEquals(false, result.accepted());
        assertEquals("INVALID_TARGET_STATE", result.code());
    }

    private AgentRunRecord run(String runId, AgentRunState state, long version, int resumeCount) {
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.runId()).thenReturn(runId);
        when(run.conversationId()).thenReturn("conversation-1");
        when(run.userId()).thenReturn("alice");
        when(run.state()).thenReturn(state);
        when(run.version()).thenReturn(version);
        when(run.resumeCount()).thenReturn(resumeCount);
        return run;
    }

    private AgentWorkItem work(String target, String runId) {
        AgentWorkItem work = mock(AgentWorkItem.class);
        when(work.activeRunId()).thenReturn(runId);
        when(work.activeExecutionTarget()).thenReturn(target);
        when(work.conversationId()).thenReturn("conversation-1");
        return work;
    }

    private AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal("tenant-test", "alice", Set.of("USER"));
    }
}
