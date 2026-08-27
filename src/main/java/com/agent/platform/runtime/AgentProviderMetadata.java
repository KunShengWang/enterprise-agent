package com.agent.platform.runtime;

/** Internal metadata required to replay a provider-native assistant tool-call turn. */
final class AgentProviderMetadata {

    static final String MODEL_TOOL_CALL_ID = "modelToolCallId";
    static final String REASONING_CONTENT = "providerReasoningContent";

    private AgentProviderMetadata() {
    }
}
