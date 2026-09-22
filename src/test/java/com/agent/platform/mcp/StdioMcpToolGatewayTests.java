package com.agent.platform.mcp;

import com.agent.platform.config.McpProperties;
import com.agent.platform.tool.LocalToolRegistry;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StdioMcpToolGatewayTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void lazyPersistentSessionIsReusedByRegistryLookupsAndCalls() throws Exception {
        FakeServer server = FakeServer.create("normal", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            assertFalse(Files.exists(server.startedPath));

            LocalToolRegistry registry = new LocalToolRegistry(
                    provider(gateway), emptyContributors());
            List<ToolDefinition> first = registry.listTools();
            ToolDefinition echo = registry.findTool("mcp.fake.echo").orElseThrow();
            List<ToolDefinition> second = registry.listTools();

            assertTrue(Files.exists(server.startedPath));
            assertEquals(1, first.stream().filter(tool -> tool.name().equals("mcp.fake.echo")).count());
            assertEquals(1, second.stream().filter(tool -> tool.name().equals("mcp.fake.echo")).count());
            assertEquals(1L, generation(echo));
            assertThrows(UnsupportedOperationException.class, () -> first.add(echo));
            assertThrows(UnsupportedOperationException.class,
                    () -> echo.metadata().put("mcpServerId", "forged"));

            ToolCallResult firstCall = gateway.callTool(echo, request(echo.name(), "1"));
            ToolCallResult secondCall = gateway.callTool(echo, request(echo.name(), "2"));

            assertTrue(firstCall.success(), firstCall.errorMessage());
            assertTrue(secondCall.success(), secondCall.errorMessage());
            assertEquals(sessionToken(firstCall.content()), sessionToken(secondCall.content()));
            assertEquals(1, countEvents(server, "started"));
            assertEquals(1, countEvents(server, "initialize"));
            assertEquals(1, countEvents(server, "initialized"));
            assertEquals(1, countEvents(server, "list"));
            assertEquals(2, countEvents(server, "call"));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void discoverReusesCurrentImmutableSnapshotUntilExplicitRefresh() throws Exception {
        FakeServer server = FakeServer.create("normal", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            List<ToolDefinition> first = gateway.discoverTools();
            List<ToolDefinition> repeated = gateway.discoverTools();
            ToolDefinition echo = first.stream()
                    .filter(tool -> tool.name().equals("mcp.fake.echo"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(first, repeated);
            assertSame(first.get(0), repeated.get(0));
            assertSame(first.get(1), repeated.get(1));
            assertEquals(2, first.size());
            assertEquals(1L, generation(echo));
            assertEquals("session=" + sessionToken(echo.description()) + ";list=1", echo.description());
            assertThrows(UnsupportedOperationException.class, () -> repeated.add(echo));
            assertThrows(UnsupportedOperationException.class,
                    () -> echo.metadata().put("mcpToolName", "forged"));
            assertEquals(1, countEvents(server, "started"));
            assertEquals(1, countEvents(server, "initialize"));
            assertEquals(1, countEvents(server, "initialized"));
            assertEquals(1, countEvents(server, "list"));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void explicitRefreshDoesNotStartAServerWithoutAReadySession() throws Exception {
        FakeServer server = FakeServer.create("normal", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            assertTrue(gateway.refreshTools().isEmpty());
            assertFalse(Files.exists(server.startedPath));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void refreshPublishesNewImmutableSnapshotWithoutChangingGenerationOrProcess() throws Exception {
        FakeServer server = FakeServer.create("refresh-add", "old", "new");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            List<ToolDefinition> first = gateway.discoverTools();
            ToolDefinition oldDefinition = first.get(0);

            List<ToolDefinition> refreshed = gateway.refreshTools();
            ToolDefinition refreshedOldDefinition = refreshed.get(0);
            ToolDefinition newDefinition = refreshed.get(1);
            List<ToolDefinition> repeated = gateway.discoverTools();
            LocalToolRegistry registry = new LocalToolRegistry(
                    provider(gateway), emptyContributors());
            List<ToolDefinition> registryTools = registry.listTools();

            assertEquals("mcp.fake.old", oldDefinition.name());
            assertEquals(1, first.size());
            assertEquals("mcp.fake.old", refreshedOldDefinition.name());
            assertEquals("mcp.fake.new", newDefinition.name());
            assertEquals(1L, generation(oldDefinition));
            assertEquals(1L, generation(refreshedOldDefinition));
            assertEquals(1L, generation(newDefinition));
            assertEquals("session=" + sessionToken(oldDefinition.description()) + ";list=1",
                    oldDefinition.description());
            assertEquals("session=" + sessionToken(refreshedOldDefinition.description()) + ";list=2",
                    refreshedOldDefinition.description());
            assertEquals("session=" + sessionToken(newDefinition.description()) + ";list=2",
                    newDefinition.description());
            assertEquals(sessionToken(oldDefinition.description()), sessionToken(refreshedOldDefinition.description()));
            assertEquals(sessionToken(oldDefinition.description()), sessionToken(newDefinition.description()));
            assertNotSame(first, refreshed);
            assertNotSame(first.get(0), refreshedOldDefinition);
            assertEquals(refreshed, repeated);
            assertSame(refreshedOldDefinition, repeated.get(0));
            assertSame(newDefinition, repeated.get(1));
            assertTrue(registry.findTool("mcp.fake.new").isPresent());
            assertEquals(2, registryTools.stream()
                    .filter(tool -> "mcp".equals(tool.metadata().get("provider")))
                    .count());
            assertThrows(UnsupportedOperationException.class, () -> first.add(newDefinition));
            assertThrows(UnsupportedOperationException.class, () -> refreshed.add(newDefinition));
            assertThrows(UnsupportedOperationException.class, () -> repeated.add(newDefinition));
            assertEquals(1, countEvents(server, "started"));
            assertEquals(1, countEvents(server, "initialize"));
            assertEquals(1, countEvents(server, "initialized"));
            assertEquals(2, countEvents(server, "list"));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void refreshRetainsOldPublishedBindingAndRejectsForgedDefinition() throws Exception {
        FakeServer server = FakeServer.create("refresh-change", "old", "new");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            ToolDefinition oldDefinition = gateway.discoverTools().get(0);
            ToolDefinition newDefinition = gateway.refreshTools().get(0);
            ToolDefinition forged = new ToolDefinition(
                    oldDefinition.name(),
                    "forged description",
                    oldDefinition.inputSchema(),
                    oldDefinition.riskLevel(),
                    oldDefinition.metadata());

            ToolCallResult oldCall = gateway.callTool(oldDefinition, request(oldDefinition.name(), "old"));
            ToolCallResult newCall = gateway.callTool(newDefinition, request(newDefinition.name(), "new"));
            ToolCallResult forgedCall = gateway.callTool(forged, request(forged.name(), "forged"));

            assertTrue(oldCall.success(), oldCall.errorMessage());
            assertTrue(newCall.success(), newCall.errorMessage());
            assertFalse(forgedCall.success());
            assertEquals("definition-not-in-snapshot", forgedCall.metadata().get("bindingFailure"));
            assertEquals(1L, generation(oldDefinition));
            assertEquals(1L, generation(newDefinition));
            assertEquals(2, countEvents(server, "list"));
            assertEquals(2, countEvents(server, "call"));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void refreshProtocolFailureKeepsLastKnownGoodSnapshotWithoutRestarting() throws Exception {
        FakeServer server = FakeServer.create("refresh-protocol-error", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            List<ToolDefinition> first = gateway.discoverTools();
            ToolDefinition echo = first.get(0);

            List<ToolDefinition> refreshed = gateway.refreshTools();
            List<ToolDefinition> repeated = gateway.discoverTools();
            ToolCallResult call = gateway.callTool(echo, request(echo.name(), "lkg"));

            assertEquals(first, refreshed);
            assertEquals(first, repeated);
            assertSame(echo, refreshed.get(0));
            assertSame(echo, repeated.get(0));
            assertEquals("session=" + sessionToken(echo.description()) + ";list=1", echo.description());
            assertTrue(call.success(), call.errorMessage());
            assertEquals(1L, generation(echo));
            assertEquals(1, countEvents(server, "started"));
            assertEquals(1, countEvents(server, "initialize"));
            assertEquals(1, countEvents(server, "initialized"));
            assertEquals(2, countEvents(server, "list"));
            assertEquals(1, countEvents(server, "call"));
            assertTrue(server.isAlive());
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void refreshTimeoutKeepsLastKnownGoodSnapshotAndDropsLateResponse() throws Exception {
        FakeServer server = FakeServer.create("refresh-timeout", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            ToolDefinition echo = gateway.discoverTools().get(0);

            List<ToolDefinition> refreshed = gateway.refreshTools();
            assertEquals(2, refreshed.size());
            assertSame(echo, refreshed.get(0));
            assertEquals(1L, generation(echo));
            waitUntil(() -> countEvents(server, "list") == 2,
                    "fake server should receive the refresh list request");

            Thread.sleep(1_400L);
            ToolCallResult call = gateway.callTool(echo, request(echo.name(), "after-late"));

            assertTrue(call.success(), call.errorMessage());
            assertEquals(1, countEvents(server, "started"));
            assertEquals(1, countEvents(server, "initialize"));
            assertEquals(1, countEvents(server, "initialized"));
            assertEquals(2, countEvents(server, "list"));
            assertEquals(1, countEvents(server, "call"));
            assertTrue(server.isAlive());
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void refreshTransportFailureInvalidatesWithoutReconnectUntilOrdinaryDiscovery() throws Exception {
        FakeServer server = FakeServer.create("refresh-die-once", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            ToolDefinition generationOne = gateway.discoverTools().get(0);

            assertTrue(gateway.refreshTools().isEmpty());
            waitUntil(() -> !server.isAlive(), "refresh failure should terminate generation one");
            assertEquals(1, countEvents(server, "started"));
            assertEquals(1, countEvents(server, "initialize"));
            assertEquals(1, countEvents(server, "initialized"));
            assertEquals(2, countEvents(server, "list"));

            ToolCallResult staleBeforeDiscover = gateway.callTool(
                    generationOne, request(generationOne.name(), "stale-before-discover"));
            assertFalse(staleBeforeDiscover.success());
            assertEquals("stale-session", staleBeforeDiscover.metadata().get("bindingFailure"));
            assertEquals(1, countEvents(server, "started"));

            ToolDefinition generationTwo = gateway.discoverTools().get(0);
            ToolCallResult staleAfterDiscover = gateway.callTool(
                    generationOne, request(generationOne.name(), "stale-after-discover"));
            ToolCallResult current = gateway.callTool(
                    generationTwo, request(generationTwo.name(), "current"));

            assertEquals(2L, generation(generationTwo));
            assertTrue(generationOne.name().equals(generationTwo.name()));
            assertFalse(staleAfterDiscover.success());
            assertEquals("stale-session", staleAfterDiscover.metadata().get("bindingFailure"));
            assertTrue(current.success(), current.errorMessage());
            assertEquals(2, countEvents(server, "started"));
            assertEquals(2, countEvents(server, "initialize"));
            assertEquals(2, countEvents(server, "initialized"));
            assertEquals(3, countEvents(server, "list"));
            assertEquals(1, countEvents(server, "call"));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void refreshFailureInOneServerDoesNotAffectAnotherServer() throws Exception {
        FakeServer failed = FakeServer.create("refresh-die-once", "echo", "probe");
        FakeServer healthy = FakeServer.create("refresh-change", "old", "new");
        McpProperties properties = new McpProperties();
        properties.setServers(List.of(
                failed.config("mcp.failed."),
                healthy.config("mcp.healthy.")));
        StdioMcpToolGateway gateway = new StdioMcpToolGateway(properties, objectMapper, Duration.ofSeconds(1));
        try {
            List<ToolDefinition> definitions = gateway.discoverTools();
            ToolDefinition failedOld = definitions.stream()
                    .filter(tool -> tool.name().equals("mcp.failed.echo"))
                    .findFirst()
                    .orElseThrow();
            ToolDefinition healthyOld = definitions.stream()
                    .filter(tool -> tool.name().equals("mcp.healthy.old"))
                    .findFirst()
                    .orElseThrow();

            List<ToolDefinition> refreshed = gateway.refreshTools();
            ToolDefinition healthyNew = refreshed.stream()
                    .filter(tool -> tool.name().equals("mcp.healthy.new"))
                    .findFirst()
                    .orElseThrow();
            waitUntil(() -> !failed.isAlive(), "failed server should terminate during refresh");

            ToolCallResult failedCall = gateway.callTool(failedOld, request(failedOld.name(), "failed"));
            ToolCallResult healthyOldCall = gateway.callTool(healthyOld, request(healthyOld.name(), "healthy-old"));
            ToolCallResult healthyNewCall = gateway.callTool(healthyNew, request(healthyNew.name(), "healthy-new"));

            assertEquals(1, refreshed.size());
            assertEquals(1L, generation(healthyNew));
            assertFalse(failedCall.success());
            assertEquals("stale-session", failedCall.metadata().get("bindingFailure"));
            assertTrue(healthyOldCall.success(), healthyOldCall.errorMessage());
            assertTrue(healthyNewCall.success(), healthyNewCall.errorMessage());
            assertEquals(1, countEvents(failed, "started"));
            assertEquals(2, countEvents(failed, "list"));
            assertEquals(1, countEvents(healthy, "started"));
            assertEquals(1, countEvents(healthy, "initialize"));
            assertEquals(1, countEvents(healthy, "initialized"));
            assertEquals(2, countEvents(healthy, "list"));
            assertEquals(2, countEvents(healthy, "call"));
        }
        finally {
            gateway.shutdown();
            failed.close();
            healthy.close();
        }
    }

    @Test
    void shutdownCompletesPendingRefreshAndTerminatesChildProcess() throws Exception {
        FakeServer server = FakeServer.create("refresh-hang", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(5), server.config());
        try {
            gateway.discoverTools();
            CompletableFuture<List<ToolDefinition>> pending = CompletableFuture.supplyAsync(gateway::refreshTools);
            waitUntil(() -> countEvents(server, "list") == 2,
                    "fake server should receive the pending refresh request");

            gateway.shutdown();
            assertTrue(pending.get(2, TimeUnit.SECONDS).isEmpty());
            waitUntil(() -> !server.isAlive(), "shutdown should terminate the child process");
            assertEquals(1, countEvents(server, "started"));
            assertEquals(1, countEvents(server, "initialize"));
            assertEquals(1, countEvents(server, "initialized"));
            assertEquals(2, countEvents(server, "list"));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void transportRecoveryCreatesNewGenerationAndOldBindingStaysStale() throws Exception {
        FakeServer server = FakeServer.create("die-once", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            ToolDefinition generationOne = gateway.discoverTools().get(0);
            ToolCallResult failedCall = gateway.callTool(generationOne, request(generationOne.name(), "once"));
            assertFalse(failedCall.success());
            waitUntil(() -> !server.isAlive(), "generation one process should exit");

            ToolDefinition generationTwo = gateway.discoverTools().get(0);
            assertEquals(2L, generation(generationTwo));
            assertTrue(generation(generationOne) < generation(generationTwo));

            ToolCallResult staleCall = gateway.callTool(generationOne, request(generationOne.name(), "stale"));
            assertFalse(staleCall.success());
            assertEquals("stale-session", staleCall.metadata().get("bindingFailure"));
            ToolCallResult currentCall = gateway.callTool(generationTwo, request(generationTwo.name(), "current"));
            assertTrue(currentCall.success(), currentCall.errorMessage());

            assertEquals(2, countEvents(server, "started"));
            assertEquals(2, countEvents(server, "initialize"));
            assertEquals(2, countEvents(server, "initialized"));
            assertEquals(2, countEvents(server, "list"));
            assertEquals(2, countEvents(server, "call"));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void forgedDefinitionWithCopiedBindingIsRejected() throws Exception {
        FakeServer server = FakeServer.create("normal", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            ToolDefinition original = gateway.discoverTools().stream()
                    .filter(tool -> tool.name().equals("mcp.fake.echo"))
                    .findFirst()
                    .orElseThrow();
            ToolDefinition forged = new ToolDefinition(
                    original.name(),
                    "forged description",
                    "{\"type\":\"string\"}",
                    original.riskLevel(),
                    original.metadata());

            ToolCallResult forgedCall = gateway.callTool(forged, request(forged.name(), "forged"));

            assertFalse(forgedCall.success());
            assertEquals("definition-not-in-snapshot", forgedCall.metadata().get("bindingFailure"));
            assertEquals(0, countEvents(server, "call"));

            ToolCallResult currentCall = gateway.callTool(original, request(original.name(), "current"));
            assertTrue(currentCall.success(), currentCall.errorMessage());
            assertEquals(1, countEvents(server, "call"));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void timeoutRemovesOnlyCurrentPendingRequestAndLateResponseIsIgnored() throws Exception {
        FakeServer server = FakeServer.create("late", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            ToolDefinition echo = gateway.discoverTools().get(0);
            ToolCallResult timedOut = gateway.callTool(echo, request(echo.name(), "late"));
            assertFalse(timedOut.success());
            assertEquals("MCP tool call timed out", timedOut.errorMessage());

            Thread.sleep(1_800L);
            ToolCallResult nextCall = gateway.callTool(echo, request(echo.name(), "next"));
            assertTrue(nextCall.success(), nextCall.errorMessage());
            assertTrue(nextCall.content().contains("calls=2"), nextCall.content());
            assertEquals(1, countEvents(server, "started"));
            assertEquals(1, countEvents(server, "initialize"));
            assertEquals(1, countEvents(server, "initialized"));
            assertEquals(1, countEvents(server, "list"));
            assertEquals(2, countEvents(server, "call"));
            assertTrue(server.isAlive());
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"protocol-error", "result-error"})
    void remoteToolErrorsAreControlledWithoutRestartingSession(String mode) throws Exception {
        FakeServer server = FakeServer.create(mode, "error", "echo");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(1), server.config());
        try {
            List<ToolDefinition> definitions = gateway.discoverTools();
            ToolDefinition errorTool = definitions.stream()
                    .filter(tool -> tool.name().equals("mcp.fake.error"))
                    .findFirst()
                    .orElseThrow();
            ToolDefinition echoTool = definitions.stream()
                    .filter(tool -> tool.name().equals("mcp.fake.echo"))
                    .findFirst()
                    .orElseThrow();

            ToolCallResult error = gateway.callTool(errorTool, request(errorTool.name(), "error"));
            ToolCallResult success = gateway.callTool(echoTool, request(echoTool.name(), "ok"));

            assertFalse(error.success());
            assertTrue(success.success(), success.errorMessage());
            assertEquals(1, countEvents(server, "started"));
            assertEquals(1, countEvents(server, "initialize"));
            assertEquals(1, countEvents(server, "initialized"));
            assertEquals(1, countEvents(server, "list"));
            assertEquals(2, countEvents(server, "call"));
            assertTrue(server.isAlive());
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    @Test
    void oneServerStartFailureDoesNotAffectAnotherServer() throws Exception {
        FakeServer healthy = FakeServer.create("normal", "echo", "probe");
        McpProperties.Server broken = new McpProperties.Server();
        broken.setEnabled(true);
        broken.setServerName("broken");
        broken.setToolNamePrefix("mcp.broken.");
        broken.setCommand("__enterprise_agent_missing_mcp_command__");
        McpProperties properties = new McpProperties();
        properties.setServers(List.of(healthy.config(), broken));
        StdioMcpToolGateway gateway = new StdioMcpToolGateway(properties, objectMapper, Duration.ofSeconds(5));
        try {
            List<ToolDefinition> definitions = gateway.discoverTools();
            ToolDefinition echo = definitions.stream()
                    .filter(tool -> tool.name().equals("mcp.fake.echo"))
                    .findFirst()
                    .orElseThrow();
            ToolCallResult call = gateway.callTool(echo, request(echo.name(), "healthy"));

            assertEquals(2, definitions.size());
            assertTrue(call.success(), call.errorMessage());
            assertEquals(1, countEvents(healthy, "started"));
            assertEquals(1, countEvents(healthy, "call"));
        }
        finally {
            gateway.shutdown();
            healthy.close();
        }
    }

    @Test
    void transportFailureInOneLiveServerDoesNotInvalidateAnother() throws Exception {
        FakeServer failed = FakeServer.create("die", "echo", "probe");
        FakeServer healthy = FakeServer.create("normal", "echo", "probe");
        McpProperties properties = new McpProperties();
        properties.setServers(List.of(
                failed.config("mcp.failed."),
                healthy.config("mcp.healthy.")));
        StdioMcpToolGateway gateway = new StdioMcpToolGateway(properties, objectMapper, Duration.ofSeconds(1));
        try {
            List<ToolDefinition> definitions = gateway.discoverTools();
            ToolDefinition failedEcho = definitions.stream()
                    .filter(tool -> tool.name().equals("mcp.failed.echo"))
                    .findFirst()
                    .orElseThrow();
            ToolDefinition healthyEcho = definitions.stream()
                    .filter(tool -> tool.name().equals("mcp.healthy.echo"))
                    .findFirst()
                    .orElseThrow();

            ToolCallResult failedCall = gateway.callTool(failedEcho, request(failedEcho.name(), "failed"));
            assertFalse(failedCall.success());
            waitUntil(() -> !failed.isAlive(), "failed server should exit");

            ToolCallResult healthyCall = gateway.callTool(healthyEcho, request(healthyEcho.name(), "healthy"));

            assertTrue(healthyCall.success(), healthyCall.errorMessage());
            assertEquals(1L, generation(healthyEcho));
            assertEquals(1, countEvents(failed, "started"));
            assertEquals(1, countEvents(failed, "initialize"));
            assertEquals(1, countEvents(failed, "initialized"));
            assertEquals(1, countEvents(failed, "list"));
            assertEquals(1, countEvents(failed, "call"));
            assertEquals(1, countEvents(healthy, "started"));
            assertEquals(1, countEvents(healthy, "initialize"));
            assertEquals(1, countEvents(healthy, "initialized"));
            assertEquals(1, countEvents(healthy, "list"));
            assertEquals(1, countEvents(healthy, "call"));
            assertTrue(healthy.isAlive());
        }
        finally {
            gateway.shutdown();
            failed.close();
            healthy.close();
        }
    }

    @Test
    void sameExposedNameAcrossServersIsDeterministicallyFailClosed() throws Exception {
        FakeServer first = FakeServer.create("normal", "echo", "probe");
        FakeServer second = FakeServer.create("normal", "echo", "probe");
        McpProperties.Server firstConfig = first.config("mcp.shared.");
        McpProperties.Server secondConfig = second.config("mcp.shared.");
        McpProperties properties = new McpProperties();
        properties.setServers(List.of(firstConfig, secondConfig));
        StdioMcpToolGateway gateway = new StdioMcpToolGateway(properties, objectMapper, Duration.ofSeconds(5));
        try {
            List<ToolDefinition> definitions = gateway.discoverTools();
            ToolCallResult directCall = gateway.callTool(
                    new ToolCallRequest("mcp.shared.echo", "collision", Map.of()));
            LocalToolRegistry registry = new LocalToolRegistry(provider(gateway), emptyContributors());

            assertEquals(4, definitions.size());
            assertFalse(directCall.success());
            assertEquals("duplicate-tool-name", directCall.metadata().get("bindingFailure"));
            IllegalStateException collision = assertThrows(IllegalStateException.class, registry::listTools);
            assertTrue(collision.getMessage().contains("mcp.shared.echo"));
            assertEquals(0, countEvents(first, "call"));
            assertEquals(0, countEvents(second, "call"));
        }
        finally {
            gateway.shutdown();
            first.close();
            second.close();
        }
    }

    @Test
    void shutdownCompletesPendingCallAndTerminatesChildProcess() throws Exception {
        FakeServer server = FakeServer.create("hang", "echo", "probe");
        StdioMcpToolGateway gateway = gateway(Duration.ofSeconds(5), server.config());
        try {
            ToolDefinition echo = gateway.discoverTools().get(0);
            CompletableFuture<ToolCallResult> pending = CompletableFuture.supplyAsync(
                    () -> gateway.callTool(echo, request(echo.name(), "pending")));
            waitUntil(() -> countEvents(server, "call") == 1, "fake server should receive the pending call");

            gateway.shutdown();
            ToolCallResult result = pending.get(2, TimeUnit.SECONDS);

            assertFalse(result.success());
            waitUntil(() -> !server.isAlive(), "shutdown should terminate the child process");
            assertEquals(1, countEvents(server, "call"));
        }
        finally {
            gateway.shutdown();
            server.close();
        }
    }

    private StdioMcpToolGateway gateway(Duration timeout, McpProperties.Server... servers) {
        McpProperties properties = new McpProperties();
        properties.setServers(List.of(servers));
        return new StdioMcpToolGateway(properties, objectMapper, timeout);
    }

    private static ToolCallRequest request(String toolName, String requestId) {
        return new ToolCallRequest(toolName, requestId, Map.of("value", requestId));
    }

    private static long generation(ToolDefinition definition) {
        return ((Number) definition.metadata().get("mcpSessionGeneration")).longValue();
    }

    private static String sessionToken(String content) {
        int start = content.indexOf("session=");
        int end = content.indexOf(';', start);
        return start < 0 || end < 0 ? content : content.substring(start + "session=".length(), end);
    }

    private static int countEvents(FakeServer server, String event) throws IOException {
        if (!Files.exists(server.eventPath)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(server.eventPath)) {
            return (int) lines.filter(line -> line.equals(event) || line.startsWith(event + " ")).count();
        }
    }

    private static void waitUntil(CheckedCondition condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(20L);
        }
        fail(message);
    }

    private static ObjectProvider<McpToolGateway> provider(McpToolGateway gateway) {
        return new ObjectProvider<>() {
            @Override
            public McpToolGateway getIfAvailable() {
                return gateway;
            }
        };
    }

    private static ObjectProvider<ToolCatalogContributor> emptyContributors() {
        return new ObjectProvider<>() {
            @Override
            public Stream<ToolCatalogContributor> orderedStream() {
                return Stream.empty();
            }
        };
    }

    @FunctionalInterface
    private interface CheckedCondition {

        boolean test() throws Exception;
    }

    private static final class FakeServer implements AutoCloseable {

        private final Path directory;

        private final Path startedPath;

        private final Path pidPath;

        private final Path eventPath;

        private final Path oneShotPath;

        private final String mode;

        private final String firstTool;

        private final String secondTool;

        private final String serverName;

        private final String command;

        private FakeServer(String mode, String firstTool, String secondTool) throws IOException {
            this.directory = Files.createTempDirectory("enterprise-agent-mcp-");
            this.startedPath = directory.resolve("started");
            this.pidPath = directory.resolve("pid");
            this.eventPath = directory.resolve("events.log");
            this.oneShotPath = directory.resolve("one-shot");
            this.mode = mode;
            this.firstTool = firstTool;
            this.secondTool = secondTool;
            this.serverName = "fake-" + mode;
            this.command = Path.of(System.getProperty("java.home"), "bin",
                    isWindows() ? "java.exe" : "java").toString();
        }

        private static FakeServer create(String mode, String firstTool, String secondTool) throws IOException {
            return new FakeServer(mode, firstTool, secondTool);
        }

        private McpProperties.Server config() {
            return config("mcp.fake.");
        }

        private McpProperties.Server config(String prefix) {
            McpProperties.Server server = new McpProperties.Server();
            server.setEnabled(true);
            server.setServerName(serverName);
            server.setToolNamePrefix(prefix);
            server.setProtocolVersion("2025-11-25");
            server.setCommand(command);
            server.setArgs(List.of(
                    "-Xms16m", "-Xmx64m", "-cp", testClasspath(), FakeMcpServerApplication.class.getName(),
                    mode, firstTool, secondTool, startedPath.toString(), pidPath.toString(),
                    eventPath.toString(), oneShotPath.toString()));
            return server;
        }

        private boolean isAlive() throws IOException {
            if (!Files.exists(pidPath)) {
                return false;
            }
            long pid = Long.parseLong(Files.readString(pidPath).trim());
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }

        @Override
        public void close() throws IOException {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }

        private static String testClasspath() {
            String surefireClasspath = System.getProperty("surefire.test.class.path");
            return surefireClasspath == null || surefireClasspath.isBlank()
                    ? System.getProperty("java.class.path")
                    : surefireClasspath;
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT)
                    .contains("win");
        }
    }
}
