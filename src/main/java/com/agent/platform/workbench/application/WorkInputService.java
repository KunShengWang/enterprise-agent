package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.NormalGoalEnvelope;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Trusted M1-A entry point. Classification and routing are intentionally outside this service. */
@Service
public class WorkInputService {

    private final WorkItemService workItemService;

    public WorkInputService(WorkItemService workItemService) {
        this.workItemService = workItemService;
    }

    public WorkItemCreationResult submit(AuthenticatedPrincipal principal,
                                         SubmitWorkInputCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String inputId = "input-" + UUID.randomUUID();
        NormalGoalEnvelope envelope = new NormalGoalEnvelope(
                inputId,
                command.goalText(),
                command.goalOrigin(),
                command.commandDecisionId(),
                command.parentWorkItemId(),
                command.relationType()
        );
        return workItemService.create(
                principal,
                new CreateWorkItemCommand(
                        command.clientInputId(),
                        command.conversationId(),
                        command.content(),
                        envelope,
                        command.expectedFocusVersion()
                )
        );
    }
}
