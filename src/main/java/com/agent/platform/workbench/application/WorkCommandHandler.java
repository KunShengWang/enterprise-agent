package com.agent.platform.workbench.application;

import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.model.WorkCommandDecision;
import com.agent.platform.workbench.model.WorkCommandExecution;
import com.agent.platform.workbench.model.WorkCommandExecutionStatus;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.persistence.WorkCommandClaim;
import com.agent.platform.workbench.persistence.WorkCommandCompletion;
import com.agent.platform.workbench.persistence.WorkCommandExecutionStore;
import com.agent.platform.workbench.persistence.WorkbenchCasConflictException;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionCommandCapabilityRegistry;
import com.agent.platform.workbench.target.ExecutionCommandSupport;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkCommandHandler {

    private static final Duration COMMAND_LEASE = Duration.ofMinutes(10);

    private final WorkbenchStore workbench;
    private final WorkCommandExecutionStore commands;
    private final ExecutionCommandCapabilityRegistry capabilities;
    private final AgentRunWorkCommandAdapter runCommands;

    public WorkCommandHandler(WorkbenchStore workbench,
                              WorkCommandExecutionStore commands,
                              ExecutionCommandCapabilityRegistry capabilities,
                              AgentRunWorkCommandAdapter runCommands) {
        this.workbench = workbench;
        this.commands = commands;
        this.capabilities = capabilities;
        this.runCommands = runCommands;
    }

    public WorkCommandResult handle(AuthenticatedPrincipal principal, WorkCommandRequest request) {
        AgentConversationTurn input = requireInput(principal, request.input());
        WorkCommandDecision decision = requireDecision(input, request.decision());
        WorkCommandType commandType = decision.commandType();
        // 判断命令类型（NORMAL_GOAL/START_NEW_WORK 直接拒绝，不该进这里）
        if (commandType == WorkCommandType.NORMAL_GOAL || commandType == WorkCommandType.START_NEW_WORK) {
            return error("INVALID_TARGET_STATE", "new goals do not enter WorkCommandHandler",
                    input, decision, "", "", WorkCommandExecutionStatus.REJECTED);
        }
        // 幂等查询（findByInput，已有执行记录直接复用）
        WorkCommandExecution existing = commands.findByInput(principal, input.inputId()).orElse(null);
        if (existing != null) {
            return fromExisting(principal, existing);
        }
        if (commandType == WorkCommandType.AMBIGUOUS) {
            return unboundError(principal, input, decision,
                    "FOCUS_AMBIGUOUS", "command target is ambiguous");
        }

        AgentWorkItem work;
        try {
            // 确定一个命令（如继续/暂停/取消）应该作用在哪个 WorkItem 上
            work = resolveWork(principal, input, decision, request.explicitWorkItemId());
        }
        catch (WorkbenchNotFoundException exception) {
            return unboundError(principal, input, decision,
                    "FOCUS_NOT_FOUND", "focused work item was not found");
        }
        catch (WorkbenchCasConflictException exception) {
            return unboundError(principal, input, decision,
                    "COMMAND_CAS_CONFLICT", exception.getMessage());
        }
        long expectedVersion = request.expectedWorkVersion() == null ? work.version() : request.expectedWorkVersion();
        String leaseOwner = "work-command-" + UUID.randomUUID();
        WorkCommandClaim claim;
        try {
            // 对同一条命令输入，只有一个人能真正执行，并给执行者一个有有效期的租约，防止并发冲突和重复执行
            claim = commands.claim(principal, input.inputId(), work.workItemId(), commandType,
                    expectedVersion, leaseOwner, COMMAND_LEASE);
        }
        catch (WorkbenchCasConflictException exception) {
            return error("COMMAND_CAS_CONFLICT", exception.getMessage(), input, decision,
                    work.activeExecutionTarget(), work.workItemId(), WorkCommandExecutionStatus.REJECTED);
        }
        if (!claim.acquired()) {
            // 查看任务是否还在执行，如果正在执行返回一个"命令进行中"的结果；如果命令已完成，返回数据库里已经保存的执行结果
            return fromExisting(principal, claim.execution());
        }

        if (commandType == WorkCommandType.ABANDON_ACTIVE_WORK) {
            return complete(principal, work, claim.execution(), leaseOwner, abandon(work, commandType));
        }

        ExecutionTargetId targetId;
        try {
            targetId = ExecutionTargetId.valueOf(work.activeExecutionTarget());
        }
        catch (RuntimeException exception) {
            return complete(principal, work, claim.execution(), leaseOwner,
                    rejected("UNSUPPORTED_FOR_TARGET", "work item has no registered execution target",
                            work, commandType));
        }
        // 返回某个执行目标（如 ORDERCARE_CASE）对每一种命令（暂停/继续/取消/放弃）支持到什么程度（SUPPORTED_EXISTING_RUNTIME / PRODUCT_ONLY / UNSUPPORTED），以及它有哪些约束
        ExecutionCommandSupport support = capabilities.require(targetId).support(commandType);
        if (support == ExecutionCommandSupport.UNSUPPORTED) {
            return complete(principal, work, claim.execution(), leaseOwner,
                    rejected("UNSUPPORTED_FOR_TARGET",
                            "command is not supported for " + targetId, work, commandType));
        }
        if (support == ExecutionCommandSupport.PRODUCT_ONLY) {
            return complete(principal, work, claim.execution(), leaseOwner, abandon(work, commandType));
        }

        AgentRunCommandResult runtimeResult;
        try {
            runtimeResult = runCommands.execute(principal, work, commandType);
        }
        catch (RuntimeException exception) {
            return complete(principal, work, claim.execution(), leaseOwner,
                    failed("COMMAND_RUNTIME_ERROR", safe(exception), work, commandType));
        }
        return complete(principal, work, claim.execution(), leaseOwner,
                runtimeCompletion(work, commandType, runtimeResult));
    }

    /**
     * 确定一个命令（如继续/暂停/取消）应该作用在哪个 WorkItem 上
     */
    private AgentWorkItem resolveWork(AuthenticatedPrincipal principal,
                                      AgentConversationTurn input,
                                      WorkCommandDecision decision,
                                      String explicitWorkItemId) {
        // 有显式 explicitWorkItemId
        if (explicitWorkItemId != null && !explicitWorkItemId.isBlank()) {
            // ① 按 ID 查 WorkItem，查不到就抛"不存在"
            AgentWorkItem explicit = workbench.findWorkItem(principal, explicitWorkItemId.trim())
                    .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
            // ② 校验：这个 WorkItem 必须属于当前会话
            if (!explicit.conversationId().equals(input.conversationId())) {
                throw new WorkbenchNotFoundException("work item not found");
            }
            return explicit;// 校验通过，直接返回
        }

        // 无显式 ID，用会话聚焦的 WorkItem
        // ① 查当前会话的焦点状态
        ConversationWorkState focus = workbench.findConversationState(principal, input.conversationId())
                .orElseThrow(() -> new WorkbenchNotFoundException("FOCUS_NOT_FOUND"));
        // ② 当前会话必须"聚焦"着某个 WorkItem
        if (focus.focusedWorkItemId() == null || focus.focusedWorkItemId().isBlank()) {
            throw new WorkbenchNotFoundException("FOCUS_NOT_FOUND");
        }
        // ③ 关键校验：分类时记录的焦点 vs 现在的焦点不能变
        if (decision.focusedWorkItemId() != null && !decision.focusedWorkItemId().isBlank()
                && !decision.focusedWorkItemId().equals(focus.focusedWorkItemId())) {
            throw new WorkbenchCasConflictException("focused work item changed after command classification");
        }
        // ④ 查到聚焦的 WorkItem，且确认属于当前会话
        return workbench.findWorkItem(principal, focus.focusedWorkItemId())
                .filter(item -> item.conversationId().equals(input.conversationId()))
                .orElseThrow(() -> new WorkbenchNotFoundException("FOCUS_NOT_FOUND"));
    }

    private WorkCommandResult complete(AuthenticatedPrincipal principal,
                                       AgentWorkItem work,
                                       WorkCommandExecution execution,
                                       String leaseOwner,
                                       WorkCommandCompletion completion) {
        // 当一个命令（暂停/继续/取消等）被真正执行完之后，把执行结果持久化到数据库，并释放租约，同时更新 WorkItem 的状态
        WorkCommandExecution completed = commands.complete(principal, execution.commandRequestId(),
                leaseOwner, execution.claimToken(), completion);
        // 重新从数据库加载这个 WorkItem 的最新状态，并保证它必须存在（否则抛异常）
        AgentWorkItem updated = commands.requireWorkItem(principal, work.workItemId());
        // 用最新状态的 WorkItem 组装返回值
        return new WorkCommandResult(completed.status() == WorkCommandExecutionStatus.SUCCEEDED,
                completed.resultCode(), completed.message(), completed.commandRequestId(), completed.inputId(),
                completed.commandType(), updated.activeExecutionTarget(), updated.workItemId(),
                completed.underlyingExecutionChanged(), completed.underlyingRunId(), completed.status(), updated);
    }

    /**
     * 查看任务是否还在执行，如果正在执行返回一个"命令进行中"的结果；如果命令已完成，返回数据库里已经保存的执行结果
     */
    private WorkCommandResult fromExisting(AuthenticatedPrincipal principal, WorkCommandExecution execution) {
        // ① 加载该命令对应的 WorkItem（可能没有，防御性处理）
        AgentWorkItem work = execution.workItemId() == null || execution.workItemId().isBlank()
                ? null : commands.requireWorkItem(principal, execution.workItemId());
        // ② 情况一：别人还在执行中，返回一个"命令进行中"的结果
        if (execution.status() == WorkCommandExecutionStatus.EXECUTING) {
            return new WorkCommandResult(false, "COMMAND_IN_PROGRESS", "command is owned by another instance",
                    execution.commandRequestId(), execution.inputId(), execution.commandType(),
                    work == null ? "" : work.activeExecutionTarget(),
                    work == null ? "" : work.workItemId(), false, execution.underlyingRunId(),
                    execution.status(), work);
        }
        // ③ 情况二：命令已完成 → 返回数据库里已经保存的执行结果
        return new WorkCommandResult(execution.status() == WorkCommandExecutionStatus.SUCCEEDED,
                execution.resultCode(), execution.message(), execution.commandRequestId(), execution.inputId(),
                execution.commandType(), work == null ? "" : work.activeExecutionTarget(),
                work == null ? "" : work.workItemId(),
                execution.underlyingExecutionChanged(), execution.underlyingRunId(), execution.status(), work);
    }

    private WorkCommandResult unboundError(AuthenticatedPrincipal principal,
                                           AgentConversationTurn input,
                                           WorkCommandDecision decision,
                                           String code,
                                           String message) {
        WorkCommandExecution execution = commands.recordUnboundRejection(
                principal, input.inputId(), decision.commandType(), code, message);
        return fromExisting(principal, execution);
    }

    private WorkCommandCompletion abandon(AgentWorkItem work, WorkCommandType commandType) {
        return new WorkCommandCompletion(WorkCommandExecutionStatus.SUCCEEDED, "OK", false,
                work.activeRunId(), "WorkItem abandoned; underlying execution remains unchanged",
                WorkControlState.ABANDONED, null, null, WorkEventType.WORK_ITEM_ABANDONED,
                "ABANDONED", Map.of("command", commandType.name(), "underlyingExecutionStopped", false));
    }

    private WorkCommandCompletion runtimeCompletion(AgentWorkItem work,
                                                    WorkCommandType commandType,
                                                    AgentRunCommandResult result) {
        if (!result.accepted()) {
            return rejected(result.code(), result.message(), work, commandType);
        }
        AgentRunRecord after = result.after();
        String resolvedRunId = after == null ? work.activeRunId() : after.runId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("command", commandType.name());
        payload.put("runId", resolvedRunId);
        payload.put("underlyingExecutionChanged", result.underlyingExecutionChanged());
        payload.put("runtimeState", after.state().name());
        payload.put("resumeCount", after.resumeCount());
        return switch (commandType) {
            case PAUSE_ACTIVE_WORK -> after.state() == AgentRunState.PAUSED
                    ? succeeded(result, work, WorkControlState.PAUSED, WorkExecutionState.PAUSED, null,
                            WorkEventType.WORK_ITEM_PAUSED, "PAUSED", payload)
                    : succeeded(result, work, WorkControlState.PAUSE_REQUESTED, WorkExecutionState.RUNNING, null,
                            WorkEventType.WORK_ITEM_PAUSE_REQUESTED, "PAUSE_REQUESTED", payload);
            case RESUME_ACTIVE_WORK -> resumeCompletion(result, work, payload);
            case CANCEL_ACTIVE_WORK -> cancelled(after)
                    ? succeeded(result, work, WorkControlState.CLOSED, WorkExecutionState.CANCELLED,
                            WorkOutcome.CANCELLED, WorkEventType.WORK_ITEM_CANCELLED, "CANCELLED", payload)
                    : succeeded(result, work, WorkControlState.CANCEL_REQUESTED, null, null,
                            WorkEventType.WORK_ITEM_CANCEL_REQUESTED, "CANCEL_REQUESTED", payload);
            default -> rejected("UNSUPPORTED_FOR_TARGET", "unsupported Runtime command", work, commandType);
        };
    }

    private WorkCommandCompletion resumeCompletion(AgentRunCommandResult result,
                                                   AgentWorkItem work,
                                                   Map<String, Object> payload) {
        AgentRunRecord after = result.after();
        return switch (after.state()) {
            case COMPLETED -> succeeded(result, work, WorkControlState.CLOSED, WorkExecutionState.COMPLETED,
                    WorkOutcome.ANSWERED, WorkEventType.WORK_ITEM_RESUMED, "COMPLETED", payload);
            case FAILED -> succeeded(result, work, WorkControlState.CLOSED, WorkExecutionState.FAILED,
                    WorkOutcome.FAILED, WorkEventType.WORK_ITEM_RESUMED, "FAILED", payload);
            case REJECTED -> succeeded(result, work, WorkControlState.CLOSED,
                    cancelled(after) ? WorkExecutionState.CANCELLED : WorkExecutionState.FAILED,
                    cancelled(after) ? WorkOutcome.CANCELLED : WorkOutcome.REJECTED,
                    WorkEventType.WORK_ITEM_RESUMED, after.state().name(), payload);
            case MANUAL_REVIEW, BLOCKED -> succeeded(result, work, WorkControlState.MANUAL_REVIEW,
                    WorkExecutionState.FAILED, WorkOutcome.MANUAL_REVIEW,
                    WorkEventType.WORK_ITEM_RESUMED, after.state().name(), payload);
            case WAITING_APPROVAL -> rejected("INVALID_TARGET_STATE", "Agent Run is still waiting for approval",
                    work, WorkCommandType.RESUME_ACTIVE_WORK);
            case PAUSED, PAUSE_REQUESTED -> rejected("INVALID_TARGET_STATE", "Agent Run did not leave paused state",
                    work, WorkCommandType.RESUME_ACTIVE_WORK);
            default -> succeeded(result, work, WorkControlState.DISPATCHED, WorkExecutionState.RUNNING,
                    WorkOutcome.UNDETERMINED, WorkEventType.WORK_ITEM_RESUMED, "RUNNING", payload);
        };
    }

    private WorkCommandCompletion succeeded(AgentRunCommandResult result,
                                            AgentWorkItem work,
                                            WorkControlState control,
                                            WorkExecutionState execution,
                                            WorkOutcome outcome,
                                            WorkEventType eventType,
                                            String phase,
                                            Map<String, Object> payload) {
        return new WorkCommandCompletion(WorkCommandExecutionStatus.SUCCEEDED, "OK",
                result.underlyingExecutionChanged(), result.after() == null ? work.activeRunId() : result.after().runId(), result.message(), control,
                execution, outcome, eventType, phase, payload);
    }

    private WorkCommandCompletion rejected(String code,
                                           String message,
                                           AgentWorkItem work,
                                           WorkCommandType commandType) {
        return new WorkCommandCompletion(WorkCommandExecutionStatus.REJECTED, code, false,
                work.activeRunId(), message, null, null, null, WorkEventType.WORK_COMMAND_REJECTED,
                "REJECTED", Map.of("command", commandType.name(), "code", code,
                        "underlyingExecutionChanged", false));
    }

    private WorkCommandCompletion failed(String code,
                                         String message,
                                         AgentWorkItem work,
                                         WorkCommandType commandType) {
        return new WorkCommandCompletion(WorkCommandExecutionStatus.FAILED, code, false,
                work.activeRunId(), message, null, null, null, WorkEventType.WORK_COMMAND_FAILED,
                "FAILED", Map.of("command", commandType.name(), "code", code));
    }

    private WorkCommandResult error(String code,
                                    String message,
                                    AgentConversationTurn input,
                                    WorkCommandDecision decision,
                                    String target,
                                    String workItemId,
                                    WorkCommandExecutionStatus status) {
        return new WorkCommandResult(false, code, message, "", input.inputId(), decision.commandType(),
                target, workItemId, false, "", status, null);
    }

    private AgentConversationTurn requireInput(AuthenticatedPrincipal principal, AgentConversationTurn input) {
        if (input == null || !input.tenantId().equals(principal.tenantId())
                || !input.ownerPrincipalId().equals(principal.principalId())) {
            throw new WorkbenchNotFoundException("input not found");
        }
        return input;
    }

    private WorkCommandDecision requireDecision(AgentConversationTurn input, WorkCommandDecision decision) {
        if (decision == null || !decision.inputId().equals(input.inputId())
                || !decision.tenantId().equals(input.tenantId())
                || !decision.ownerPrincipalId().equals(input.ownerPrincipalId())
                || decision.commandType() == null) {
            throw new WorkbenchCasConflictException("effective command decision is invalid");
        }
        return decision;
    }

    private boolean cancelled(AgentRunRecord record) {
        return record.state() == AgentRunState.REJECTED && "CANCELLED".equals(record.failureReason());
    }

    private String safe(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
