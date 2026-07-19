package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.WorkCommandClassification;
import com.agent.platform.workbench.model.WorkCommandDecision;
import com.agent.platform.workbench.model.WorkCommandType;
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
        AgentConversationTurn input = routingStore.persistUnclassifiedInput(
                principal, request.inputId(), request.clientInputId(), request.conversationId(), request.content());
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
        ConversationWorkState focus = workbenchStore.findConversationState(principal, input.conversationId())
                .orElseThrow(() -> new IllegalStateException("conversation focus state disappeared"));
        AgentWorkItem focused = focus.focusedWorkItemId().isBlank() ? null
                : workbenchStore.findWorkItem(principal, focus.focusedWorkItemId()).orElse(null);
        ClassifierType classifierType = request.classifierType();
        String traceId = "command-classifier-" + UUID.randomUUID();
        WorkCommandDecision started = routingStore.beginCommandAttempt(
                principal, input.inputId(), classifierType, traceId);
        if (started.decisionStatus().name().equals("EFFECTIVE")) return started;
        try {
            CommandClassifierResult result = classifier.classify(new CommandClassificationRequest(
                    input,
                    focused == null ? "" : focused.workItemId(),
                    focused == null ? "" : focused.normalizedGoal(),
                    classifierType,
                    request.explicitCommand(),
                    request.explicitGoalText()));
            return routingStore.completeCommandAttempt(principal, started.commandDecisionId(), result);
        }
        catch (RuntimeException exception) {
            routingStore.failCommandAttempt(principal, started.commandDecisionId(),
                    "COMMAND_CLASSIFICATION_FAILED", safeMessage(exception));
            throw exception;
        }
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
