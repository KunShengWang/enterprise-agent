package com.agent.platform.ordercare.incident.recovery.model;

import java.util.List;

/** 模型只能提出候选请求；该对象不授予写权限。 */
public record RecoveryPlanDraft(
        String schemaVersion,
        String summary,
        List<ProposalRequest> proposalRequests
) {
    public RecoveryPlanDraft {
        proposalRequests = proposalRequests == null ? List.of() : List.copyOf(proposalRequests);
    }

    public record ProposalRequest(
            String clientItemKey,
            String identifierType,
            String identifierValue,
            String actionType,
            String suggestedReason,
            List<String> evidenceIds,
            List<String> conflictIds
    ) {
        public ProposalRequest {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            conflictIds = conflictIds == null ? List.of() : List.copyOf(conflictIds);
        }
    }
}
