package com.agent.platform.workbench.model;

public enum IdentifierSource {
    EXPLICIT_USER_INPUT,
    TRUSTED_CONVERSATION_CONTEXT,
    SERVER_RESOLVED_FROM_BATCH,
    SERVER_RESOLVED_FROM_SCOPE_DISCOVERY,
    MODEL_INFERRED
}
