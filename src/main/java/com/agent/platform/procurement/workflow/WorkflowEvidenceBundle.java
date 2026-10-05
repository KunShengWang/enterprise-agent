package com.agent.platform.procurement.workflow;

import java.util.List;

/** Authorization held in server Run records. Context receives refs/projections, not receipt bodies. */
public record WorkflowEvidenceBundle(String workflowId, String caseId, long caseVersion,
        String recommendationId, long recommendationVersion, String targetStage, List<Entry> entries) {
    public WorkflowEvidenceBundle { entries=List.copyOf(entries); }
    public enum Scope { CURRENT_STAGE, WORKFLOW_CARRIED }
    public record Entry(String evidenceRef, Scope scope, String originRunId, String originStage,
            String originToolCallId, String originTool, String originOutcome, String validationStatus,
            String authorizationSource, String receiptSha256, String validationSha256) {}
}
