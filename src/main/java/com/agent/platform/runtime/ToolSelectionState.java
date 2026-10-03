package com.agent.platform.runtime;

/** Read-only server snapshot for schema selection; not a second persisted business state machine. */
public record ToolSelectionState(boolean authoritative, boolean caseExists, String caseId,
                                 long caseVersion, String caseStatus, boolean complete, boolean rfqFieldsReady) {
    public static ToolSelectionState unknown() {
        return new ToolSelectionState(false, false, "", -1, "UNKNOWN", false, false);
    }
    public static ToolSelectionState absent() {
        return new ToolSelectionState(true, false, "", -1, "UNCREATED", false, false);
    }
}
