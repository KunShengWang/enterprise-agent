package com.agent.platform.ordercare;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.ordercare.application.OrderCareProposalBinding;
import com.agent.platform.ordercare.application.OrderCareProposalBindingStore;
import com.agent.platform.ordercare.config.AgentScenarioProfileResolver;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.ordercare.tool.OrderCareToolCatalog;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.ToolCallRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实 PostgreSQL 中构造 EXECUTING_TOOL 崩溃检查点，并由新 Runtime 调用恢复。 */
@SpringBootTest(properties = {
        "enterprise-agent.mock-mode=true",
        "enterprise-agent.ordercare.inspect-max-attempts=1",
        "enterprise-agent.ordercare.convergence-max-attempts=2",
        "enterprise-agent.ordercare.convergence-interval-millis=0",
        "enterprise-agent.ordercare.reconciliation-max-attempts=2",
        "enterprise-agent.ordercare.reconciliation-interval-millis=0",
        "enterprise-agent.resilience.rate-limit.enabled=false"
})
@Import(OrderCareM3CrashRecoveryRuntimeE2ETests.ModelGatewayConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ORDERCARE_RUNTIME_E2E", matches = "true")
class OrderCareM3CrashRecoveryRuntimeE2ETests {

    private static final AtomicInteger EXECUTE_COUNT = new AtomicInteger();
    private static HttpServer flowOrderStub;

    @Autowired private AgentRuntime agentRuntime;
    @Autowired private AgentRunStore runStore;
    @Autowired private ToolExecutionStore toolExecutionStore;
    @Autowired private AgentTimelineStore timelineStore;
    @Autowired private OrderCareProposalBindingStore bindingStore;

    @DynamicPropertySource
    static void orderCareProperties(DynamicPropertyRegistry registry) {
        ensureFlowOrderStub();
        registry.add("enterprise-agent.ordercare.floworder-base-url",
                () -> "http://127.0.0.1:" + flowOrderStub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        if (flowOrderStub != null) flowOrderStub.stop(0);
    }

    @Test
    void staleExecutingCheckpointIsRecoveredFromFlowOrderFacts() {
        EXECUTE_COUNT.set(0);
        String suffix = UUID.randomUUID().toString();
        String runId = "ordercare-m3-crash-run-" + suffix;
        String sessionId = "ordercare-m3-crash-session-" + suffix;
        String proposalId = "prop-ordercare-m3-crash-" + suffix;
        String actionRequestId = "act-ordercare-m3-crash-" + suffix;
        String toolExecutionId = "tool-ordercare-m3-crash-" + suffix;
        OrderCareRecoveryProposal proposal = proposal(proposalId, actionRequestId);
        ToolCallRequest pending = new ToolCallRequest(
                OrderCareToolCatalog.RECOVERY_EXECUTE,
                toolExecutionId,
                Map.ofEntries(
                        Map.entry("proposalId", proposalId),
                        Map.entry("proposalVersion", 1),
                        Map.entry("stateFingerprint", proposal.stateFingerprint()),
                        Map.entry("effectsDigest", proposal.effectsDigest()),
                        Map.entry("warningsDigest", proposal.warningsDigest()),
                        Map.entry("previewDigest", proposal.previewDigest()),
                        Map.entry("approvalId", "approval-crash-1"),
                        Map.entry("approvedBy", "operator-wang"),
                        Map.entry("approvalComment", "approved before crash")
                )
        );
        AgentRunLimits limits = new AgentRunLimits(6, 4, 4, 8_000, 2_000, 0.2, 30_000);
        AgentRunBudget budget = new AgentRunBudget(limits);
        AgentExecutionProfile profile = new AgentExecutionProfile(
                AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1,
                "M3 crash recovery profile",
                Set.of(OrderCareToolCatalog.RECOVERY_EXECUTE),
                limits,
                false
        );
        AgentRunRecord stale = AgentRunRecord.create(
                        runId, runId, sessionId,
                        new AgentRequest(sessionId, "operator-user", "recover after crash", Map.of(),
                                AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1),
                        profile, budget.snapshot())
                .checkpoint(AgentRunPhase.EXECUTING_TOOL, pending, List.of(), List.of(), false,
                        budget.snapshot());
        runStore.create(stale);
        timelineStore.appendMessages(sessionId, "operator-user", runId, List.of(
                AgentMessageDraft.toolCall(
                        toolExecutionId,
                        OrderCareToolCatalog.RECOVERY_EXECUTE,
                        pending.arguments(),
                        Map.of("reason", "approved recovery interrupted after action submission"),
                        32
                )
        ));
        assertTrue(toolExecutionStore.claim(runId, pending).claimed());
        bindingStore.bind(new OrderCareProposalBinding(
                proposalId, actionRequestId, proposal.caseKey(),
                "preview-before-crash", runId, proposal, Instant.now()
        ));

        AgentRuntimeResult result = agentRuntime.resume(runId);

        assertEquals(AgentRunState.COMPLETED, result.state());
        ToolExecutionRecord execution = toolExecutionStore.findToolExecution(toolExecutionId).orElseThrow();
        assertEquals(ToolExecutionState.SUCCEEDED, execution.state());
        assertEquals(true, execution.result().metadata().get("recoveredAfterCrash"));
        assertEquals(actionRequestId, execution.result().metadata().get("actionRequestId"));
        assertEquals(0, EXECUTE_COUNT.get(), "已提交 Action 的崩溃恢复不能再次 execute");
        assertTrue(runStore.find(runId).orElseThrow().resumeCount() >= 1);
    }

    private static OrderCareRecoveryProposal proposal(String proposalId, String actionRequestId) {
        return new OrderCareRecoveryProposal(
                "floworder-recovery-proposal-v1", proposalId, 1, "APPROVED", actionRequestId,
                "EXECUTING", "NOT_CONVERGED", "floworder:request:ORDERCARE-M3-CRASH",
                "REQUEST_ID", "ORDERCARE-M3-CRASH", "REPLAY", "DEAD_LETTER", "401",
                "fingerprint-crash", "effects-crash", "warnings-crash", "preview-crash",
                false, List.of("replay"), List.of("approval required"), "crash recovery",
                "approval-crash-1", "operator-wang", "approved before crash",
                "2026-07-17T18:00:00", "2099-07-17T18:00:00", "2026-07-17T17:00:00",
                "2026-07-17T17:00:00"
        );
    }

    private static synchronized void ensureFlowOrderStub() {
        if (flowOrderStub != null) return;
        try {
            flowOrderStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            flowOrderStub.createContext("/internal/recovery/actions", exchange ->
                    respond(exchange, actionBody(pathTail(exchange))));
            flowOrderStub.createContext("/internal/recovery/proposals", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) EXECUTE_COUNT.incrementAndGet();
                respond(exchange, proposalBody(pathProposalId(exchange)));
            });
            flowOrderStub.createContext("/internal/recovery/cases/inspect", exchange ->
                    respond(exchange, caseBody()));
            flowOrderStub.start();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to start M3 crash recovery stub", exception);
        }
    }

    private static String pathTail(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String pathProposalId(HttpExchange exchange) {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        return parts.length >= 5 ? parts[4] : "prop-unknown";
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String actionBody(String actionId) {
        String proposalId = actionId.replace("act-", "prop-");
        return "{\"code\":200,\"message\":\"success\",\"data\":{" +
                "\"schemaVersion\":\"floworder-recovery-action-v1\",\"proposalId\":\"" + proposalId +
                "\",\"actionRequestId\":\"" + actionId +
                "\",\"actionStatus\":\"SUBMITTED\",\"caseOutcome\":\"RESOLVED\",\"reconciliationStatus\":\"RESOLVED\"}}";
    }

    private static String proposalBody(String proposalId) {
        return "{\"code\":200,\"message\":\"success\",\"data\":{" +
                "\"schemaVersion\":\"floworder-recovery-proposal-v1\",\"proposalId\":\"" + proposalId +
                "\",\"proposalVersion\":1,\"proposalStatus\":\"APPROVED\",\"actionRequestId\":\"" + proposalId.replace("prop-", "act-") +
                "\",\"actionStatus\":\"SUBMITTED\",\"caseOutcome\":\"RESOLVED\",\"caseKey\":\"floworder:request:ORDERCARE-M3-CRASH\"," +
                "\"identifierType\":\"REQUEST_ID\",\"identifierValue\":\"ORDERCARE-M3-CRASH\",\"targetType\":\"DEAD_LETTER\",\"targetKey\":\"401\"}}";
    }

    private static String caseBody() {
        return """
                {"code":200,"message":"success","data":{"schemaVersion":"floworder-recovery-case-v1",
                 "caseKey":"floworder:request:ORDERCARE-M3-CRASH","identifierType":"REQUEST_ID","identifierValue":"ORDERCARE-M3-CRASH",
                 "found":true,"diagnosisCode":"ALREADY_CONVERGED","factsComplete":true,"recoveryEligible":false,
                 "deduct":{"exists":true,"status":30,"statusName":"RELEASED"},
                 "inventory":{"exists":true,"invariantOk":true},
                 "deadLetters":[{"deadLetterId":401,"status":20,"statusName":"RESOLVED"}],
                 "recoveryActions":[],"candidates":[],"evidence":["RECOVERY_CONFIRMED"],"hardRisks":[]}}
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ModelGatewayConfiguration {
        @Bean
        @Primary
        AgentModelGateway crashRecoveryModelGateway() {
            return request -> new AgentModelTurn(
                    "崩溃恢复完成：原 Action 已对账并确认 RESOLVED。", List.of(), "final",
                    new LlmUsage(60, 30, 90, 0, 0, "deterministic-m3-crash", "test"),
                    "final_answer"
            );
        }
    }
}
