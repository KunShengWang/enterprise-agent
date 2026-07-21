package com.agent.platform.workbench.application;

import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentStopReason;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class AgentRunWorkCommandAdapter {

    private static final Set<AgentRunState> TERMINAL = EnumSet.of(
            AgentRunState.COMPLETED, AgentRunState.BLOCKED, AgentRunState.FAILED,
            AgentRunState.REJECTED, AgentRunState.MANUAL_REVIEW);

    private final AgentRuntime runtime;
    private final AgentRunStore runStore;

    public AgentRunWorkCommandAdapter(AgentRuntime runtime, AgentRunStore runStore) {
        this.runtime = runtime;
        this.runStore = runStore;
    }

    public AgentRunCommandResult execute(AuthenticatedPrincipal principal,
                                         AgentWorkItem work,
                                         WorkCommandType commandType) {
        AgentRunRecord before = resolveRun(work, commandType);
        if (before == null) {
            return rejected("INVALID_TARGET_STATE", "work item has no active Agent Run", null);
        }
        if (!before.conversationId().equals(work.conversationId())) {
            return rejected("INVALID_TARGET_STATE", "linked Agent Run is missing or does not belong to the conversation", before);
        }
        if (before.userId() != null && !before.userId().isBlank()
                && !before.userId().equals(principal.principalId())) {
            return rejected("FORBIDDEN", "linked Agent Run is not owned by the authenticated principal", before);
        }
        return switch (commandType) {
            case PAUSE_ACTIVE_WORK -> pause(before);
            case RESUME_ACTIVE_WORK -> resume(before);
            case CANCEL_ACTIVE_WORK -> cancel(before);
            default -> rejected("UNSUPPORTED_FOR_TARGET", "command is not a Run control command", before);
        };
    }

    private AgentRunRecord resolveRun(AgentWorkItem work, WorkCommandType commandType) {
        if (work.activeRunId() != null && !work.activeRunId().isBlank()) {
            return runStore.find(work.activeRunId()).orElse(null);
        }
        if (commandType != WorkCommandType.CANCEL_ACTIVE_WORK
                || work.dispatchRequestId() == null || work.dispatchRequestId().isBlank()) {
            return null;
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            AgentRunRecord discovered = runStore.findByDispatchRequestId(work.dispatchRequestId()).orElse(null);
            if (discovered != null) return discovered;
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private AgentRunCommandResult pause(AgentRunRecord before) {
        if (TERMINAL.contains(before.state()) || (before.state() != AgentRunState.RUNNING
                && before.state() != AgentRunState.PAUSED
                && before.state() != AgentRunState.PAUSE_REQUESTED)) {
            return rejected("INVALID_TARGET_STATE", "Agent Run is not pausable from " + before.state(), before);
        }
        boolean accepted = runtime.pause(before.runId());
        AgentRunRecord after = runStore.find(before.runId()).orElse(before);
        if (!accepted) return rejected("INVALID_TARGET_STATE", "Agent Runtime rejected pause", before, after);
        return accepted(before, after, "pause accepted by Agent Runtime");
    }

    private AgentRunCommandResult resume(AgentRunRecord before) {
        if (before.state() != AgentRunState.PAUSED
                && before.state() != AgentRunState.PAUSE_REQUESTED
                && before.state() != AgentRunState.WAITING_APPROVAL) {
            return rejected("INVALID_TARGET_STATE", "Agent Run is not resumable from " + before.state(), before);
        }
        AgentRuntimeResult result = runtime.resume(before.runId());
        AgentRunRecord after = runStore.find(before.runId()).orElse(before);
        if (result.state() == AgentRunState.WAITING_APPROVAL
                && result.stopReason() == AgentStopReason.WAITING_APPROVAL) {
            return rejected("INVALID_TARGET_STATE", "Agent Run is still waiting for approval", before, after);
        }
        return accepted(before, after, "Agent Run resumed with the same runId");
    }

    private AgentRunCommandResult cancel(AgentRunRecord before) {
        if (TERMINAL.contains(before.state())) {
            return rejected("INVALID_TARGET_STATE", "Agent Run is already terminal: " + before.state(), before);
        }
        boolean accepted = runtime.cancel(before.runId());
        AgentRunRecord after = runStore.find(before.runId()).orElse(before);
        if (!accepted) return rejected("INVALID_TARGET_STATE", "Agent Runtime rejected cancellation", before, after);
        return accepted(before, after, "cancellation accepted by Agent Runtime");
    }

    private AgentRunCommandResult accepted(AgentRunRecord before,
                                           AgentRunRecord after,
                                           String message) {
        boolean changed = before.state() != after.state()
                || before.version() != after.version()
                || before.resumeCount() != after.resumeCount();
        return new AgentRunCommandResult(true, changed, "OK", message, before, after);
    }

    private AgentRunCommandResult rejected(String code, String message, AgentRunRecord before) {
        return rejected(code, message, before, before);
    }

    private AgentRunCommandResult rejected(String code,
                                           String message,
                                           AgentRunRecord before,
                                           AgentRunRecord after) {
        return new AgentRunCommandResult(false, false, code, message, before, after);
    }
}
