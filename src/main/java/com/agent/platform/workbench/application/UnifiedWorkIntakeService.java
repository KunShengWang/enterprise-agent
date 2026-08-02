package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.WorkCommandClassification;
import com.agent.platform.workbench.model.WorkCommandDecision;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class UnifiedWorkIntakeService {

    private final RoutingStore routingStore;
    private final WorkbenchStore workbenchStore;
    private final WorkCommandClassifier classifier;

    public UnifiedWorkIntakeService(RoutingStore routingStore,
                                    WorkbenchStore workbenchStore,
                                    WorkCommandClassifier classifier) {
        this.routingStore = routingStore;
        this.workbenchStore = workbenchStore;
        this.classifier = classifier;
    }

    public UnifiedWorkIntakeResult accept(AuthenticatedPrincipal principal, UnifiedWorkInputRequest request) {
        // 路由方案扩展了 Workbench 基础表，因此首先在新数据库上初始化基础所有者
        workbenchStore.findConversationState(principal, request.conversationId());
        // 持久化用户输入（幂等：同一个 clientInputId 只存一次）
        AgentConversationTurn input = routingStore.persistUnclassifiedInput(
                principal, request.inputId(), request.clientInputId(), request.conversationId(), request.content());
        // 检查是否已有分类结果（幂等：重复请求直接拿缓存）
        Optional<WorkCommandDecision> existing = routingStore.findEffectiveCommand(principal, input.inputId());
        WorkCommandDecision commandDecision = existing.orElseGet(() -> classify(principal, input, request));
        WorkCommandClassification classification = classification(commandDecision);
        if (classification.commandType() != WorkCommandType.NORMAL_GOAL
                && classification.commandType() != WorkCommandType.START_NEW_WORK) {
            return new UnifiedWorkIntakeResult(input, commandDecision, null, true);
        }

        ConversationWorkState focus = workbenchStore.findConversationState(principal, input.conversationId())
                .orElseThrow(() -> new IllegalStateException("conversation focus state disappeared"));
        String goalText = classification.commandType() == WorkCommandType.START_NEW_WORK
                ? classification.derivedGoalText() : input.content();
        GoalOrigin origin = classification.commandType() == WorkCommandType.START_NEW_WORK
                ? GoalOrigin.DERIVED_FROM_START_NEW_WORK : GoalOrigin.DIRECT_NORMAL_GOAL;
        // 创建 WorkItem
        WorkItemCreationResult created = workbenchStore.createWorkItemFromPersistedInput(
                principal,
                new CreatePersistedInputWorkItemCommand(
                        input.inputId(), goalText, origin,
                        origin == GoalOrigin.DERIVED_FROM_START_NEW_WORK
                                ? commandDecision.commandDecisionId() : "",
                        "", null, focus.version()));
        return new UnifiedWorkIntakeResult(created.input(), commandDecision, created.workItem(), false);
    }

    private WorkCommandDecision classify(AuthenticatedPrincipal principal,
                                         AgentConversationTurn input,
                                         UnifiedWorkInputRequest request) {
        // 根据用户身份和 conversationId 查询当前会话状态
        ConversationWorkState focus = workbenchStore.findConversationState(principal, input.conversationId())
                .orElseThrow(() -> new IllegalStateException("conversation focus state disappeared"));
        // 存在 focusedWorkItemId 时，加载对应的工作项；否则 focused = null
        AgentWorkItem focused = focus.focusedWorkItemId().isBlank() ? null
                : workbenchStore.findWorkItem(principal, focus.focusedWorkItemId()).orElse(null);
        // 确定分类器类型并生成追踪 ID
        ClassifierType classifierType = request.classifierType();
        String traceId = "command-classifier-" + UUID.randomUUID();
        // 创建分类尝试记录
        WorkCommandDecision started = routingStore.beginCommandAttempt(
                principal, input.inputId(), classifierType, traceId);
        // 如果 beginCommandAttempt() 返回的记录已经是 EFFECTIVE，说明这条输入之前已经完成过有效分类，因此直接返回，不再重复调用分类器
        if (started.decisionStatus().name().equals("EFFECTIVE")) return started;
        try {
            CommandClassifierResult result = classifier.classify(new CommandClassificationRequest(
                    input,
                    focused == null ? "" : focused.workItemId(),
                    focusedSummary(focused),
                    classifierType,
                    request.explicitCommand(),
                    request.explicitGoalText()));
            result = normalizeForFocusedState(result, focused);
            return routingStore.completeCommandAttempt(principal, started.commandDecisionId(), result);
        }
        catch (RuntimeException exception) {
            routingStore.failCommandAttempt(principal, started.commandDecisionId(),
                    "COMMAND_CLASSIFICATION_FAILED", safeMessage(exception));
            throw exception;
        }
    }

    private CommandClassifierResult normalizeForFocusedState(CommandClassifierResult result,
                                                              AgentWorkItem focused) {
        WorkCommandClassification classification = result.classification();
        if (result.classifierType() != ClassifierType.MODEL
                || classification.commandType() != WorkCommandType.ADD_INPUT_TO_ACTIVE_WORK
                || !terminal(focused)) {
            return result;
        }
        WorkCommandClassification normalized = new WorkCommandClassification(
                WorkCommandType.NORMAL_GOAL,
                classification.modelConfidence(),
                "follow-up after terminal work starts a new WorkItem in the same conversation",
                "",
                "");
        return new CommandClassifierResult(normalized, result.classifierType(), result.modelName(),
                result.promptDigest(), result.rawOutputDigest(), result.rawOutput(), result.promptTokens(),
                result.completionTokens(), result.latencyMs(), result.traceId());
    }

    private boolean terminal(AgentWorkItem focused) {
        if (focused == null) return false;
        if (focused.controlState() == WorkControlState.CLOSED
                || focused.controlState() == WorkControlState.ABANDONED) return true;
        return focused.executionState() == WorkExecutionState.COMPLETED
                || focused.executionState() == WorkExecutionState.FAILED
                || focused.executionState() == WorkExecutionState.CANCELLED;
    }

    private String focusedSummary(AgentWorkItem focused) {
        if (focused == null) return "";
        return "goal=" + focused.normalizedGoal()
                + "; controlState=" + focused.controlState()
                + "; executionState=" + focused.executionState()
                + "; outcome=" + focused.outcome();
    }

    private WorkCommandClassification classification(WorkCommandDecision decision) {
        Object type = decision.decision().get("commandType");
        if (type == null) throw new IllegalStateException("effective command decision has no commandType");
        WorkCommandType commandType = WorkCommandType.valueOf(String.valueOf(type));
        return new WorkCommandClassification(
                commandType,
                decision.modelConfidence(),
                String.valueOf(decision.decision().getOrDefault("reason", "")),
                String.valueOf(decision.decision().getOrDefault("targetWorkItemId", "")),
                String.valueOf(decision.decision().getOrDefault("derivedGoalText", "")));
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
