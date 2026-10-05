package com.agent.platform.runtime;

import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.*;
import com.agent.platform.guardrail.*;
import com.agent.platform.mcp.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolEffectContractTests {
    static final String ITEM = "mcp.erpnext.procurement_get_item";
    static final String RFQ = "mcp.erpnext.procurement_create_rfq";

    @Test void actualServerDeclarationRoundTripsIntoRuntimeRetryWithSameRequest() {
        var gateway = gateway("canonical", true, "erpnext");
        try {
            var definitions = gateway.discoverTools();
            assertEquals(14, definitions.size());
            for (var d : definitions) {
                assertEquals(ToolRuntimeContract.Source.MCP, d.runtimeContract().source());
                assertEquals(d.name().equals(RFQ) ? ToolRuntimeContract.Effect.EXTERNAL_SIDE_EFFECT : ToolRuntimeContract.Effect.READ_ONLY, d.runtimeContract().effect());
                assertEquals(true, d.metadata().get("mcpTrustedProcurement"));
            }
            var d = find(definitions, ITEM);
            assertEquals(true, ((Map<?, ?>) d.metadata().get("mcpToolAnnotations")).get("readOnlyHint"));
            var executor = mock(AgentCapabilityExecutor.class);
            when(executor.execute(any())).thenAnswer(i -> gateway.callTool(d, i.getArgument(0)));
            var result = execute(executor, d);
            assertTrue(result.result().success());
            var md = result.result().metadata();
            assertEquals(2, md.get("attempts"));
            assertEquals(1L, md.get("_harnessToolErrors"));
            assertEquals(1L, md.get("_harnessRecoveredRetryableErrors"));
            var history = (List<Map<String, Object>>) md.get("_harnessAttempts");
            assertEquals("READ_ONLY", history.get(0).get("effect"));
            assertEquals("RETRYABLE_ERROR", history.get(0).get("outcome"));
            assertEquals(true, history.get(0).get("retryDecision"));
            assertEquals("SUCCESS", history.get(1).get("outcome"));
            var requests = org.mockito.ArgumentCaptor.forClass(ToolCallRequest.class);
            verify(executor, times(2)).execute(requests.capture());
            assertSame(requests.getAllValues().get(0), requests.getAllValues().get(1));
            assertEquals(Map.of("id", "V2-WS-001"), requests.getValue().arguments());
            assertEquals("one-logical-call", requests.getValue().requestId());
        } finally { gateway.shutdown(); }
    }

    @ParameterizedTest @ValueSource(strings={"absent", "false", "string", "idempotent"})
    void missingOrNonBooleanReadOnlyNeverGuessesFromGetName(String mode) {
        var gateway = gateway(mode, true, "erpnext");
        try { assertNoReplay(find(gateway.discoverTools(), ITEM)); }
        finally { gateway.shutdown(); }
    }

    @Test void openWorldDoesNotMeanExternalWrite() {
        var gateway = gateway("open", true, "erpnext");
        try { assertEquals(ToolRuntimeContract.Effect.READ_ONLY, find(gateway.discoverTools(), ITEM).runtimeContract().effect()); }
        finally { gateway.shutdown(); }
    }

    @Test void untrustedServerCannotSelfAttestThroughAnnotationsOrMeta() {
        var gateway = gateway("malicious", false, "erpnext");
        try {
            var ds = gateway.discoverTools();
            assertEquals(false, find(ds, RFQ).metadata().get("mcpTrustedProcurement"));
            assertNoReplay(find(ds, ITEM));
            assertNoReplay(find(ds, RFQ));
        } finally { gateway.shutdown(); }
    }

    @Test void optInDoesNotTrustAnotherConnectionIdentity() {
        var gateway = gateway("malicious", true, "unknown");
        try { assertNoReplay(find(gateway.discoverTools(), ITEM)); }
        finally { gateway.shutdown(); }
    }

    @Test void canonicalRfqWriteRemainsNonReplaySafeDespiteIdempotentHint() {
        var gateway = gateway("canonical", true, "erpnext");
        try {
            var d = find(gateway.discoverTools(), RFQ);
            var hints = (Map<?, ?>) d.metadata().get("mcpToolAnnotations");
            assertEquals(false, hints.get("readOnlyHint"));
            assertEquals(true, hints.get("idempotentHint"));
            assertNoReplay(d);
        } finally { gateway.shutdown(); }
    }

    private void assertNoReplay(ToolDefinition d) {
        assertEquals(ToolRuntimeContract.Effect.EXTERNAL_SIDE_EFFECT, d.runtimeContract().effect());
        var executor = mock(AgentCapabilityExecutor.class);
        when(executor.execute(any())).thenReturn(new ToolCallResult(d.name(), false, "", "transient or ambiguous write result", Map.of("errorType", "ERP_UNAVAILABLE")));
        var response = execute(executor, d);
        assertFalse(response.result().success());
        assertEquals(1, response.result().metadata().get("attempts"));
        var history = (List<Map<String, Object>>) response.result().metadata().get("_harnessAttempts");
        assertEquals("EFFECT_NOT_REPLAY_SAFE", history.get(0).get("retryReason"));
        verify(executor, times(1)).execute(any());
    }

    private AgentToolRuntimeResult execute(AgentCapabilityExecutor executor, ToolDefinition d) {
        var guard = mock(GuardrailService.class); var store = mock(ToolExecutionStore.class);
        var props = new AgentProperties(); props.setMaxToolExecutionAttempts(2); props.setToolRetryBackoffMillis(0);
        when(guard.checkToolCall(any(), any(), any())).thenReturn(GuardrailDecision.allow(GuardrailStage.TOOL, "allowed"));
        when(store.claim(any(), any())).thenReturn(ToolExecutionClaim.acquired());
        return new DefaultAgentToolRuntime(guard, mock(ApprovalService.class), store, executor, props)
                .execute("r", "s", "u", Map.of("caseVersion", 3), new AgentToolCall("one-logical-call", d.name(), Map.of("id", "V2-WS-001"), ""), d);
    }

    static ToolDefinition find(List<ToolDefinition> ds, String name) { return ds.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow(); }
    static StdioMcpToolGateway gateway(String mode, boolean trust, String name) {
        var server = new McpProperties.Server(); server.setServerName(name); server.setToolNamePrefix("mcp.erpnext.");
        server.setTrustedProcurement(trust);
        server.setCommand(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        server.setArgs(List.of("-cp", System.getProperty("java.class.path"), McpAnnotationTestServer.class.getName(), mode));
        var props = new McpProperties(); props.setServers(List.of(server));
        return new StdioMcpToolGateway(props, new ObjectMapper());
    }
}
