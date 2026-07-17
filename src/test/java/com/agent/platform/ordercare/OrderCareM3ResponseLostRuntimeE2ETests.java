package com.agent.platform.ordercare;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.ordercare.config.AgentScenarioProfileResolver;
import com.agent.platform.ordercare.tool.OrderCareToolCatalog;
import com.agent.platform.runtime.*;
import com.agent.platform.stream.AgentStreamEvent;
import com.agent.platform.stream.StreamingAgentExecutor;
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
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL Runtime + HTTP 响应丢失：只用原 Action 对账，重复 resume 不重复执行。 */
@SpringBootTest(properties = {
        "enterprise-agent.mock-mode=true",
        "enterprise-agent.ordercare.inspect-max-attempts=1",
        "enterprise-agent.ordercare.convergence-max-attempts=3",
        "enterprise-agent.ordercare.convergence-interval-millis=0",
        "enterprise-agent.ordercare.reconciliation-max-attempts=3",
        "enterprise-agent.ordercare.reconciliation-interval-millis=0",
        "enterprise-agent.resilience.rate-limit.enabled=false"
})
@Import(OrderCareM3ResponseLostRuntimeE2ETests.ModelGatewayConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ORDERCARE_RUNTIME_E2E", matches = "true")
class OrderCareM3ResponseLostRuntimeE2ETests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> PROPOSAL_ID = new AtomicReference<>();
    private static final AtomicBoolean EXECUTED = new AtomicBoolean(false);
    private static final AtomicInteger EXECUTE_COUNT = new AtomicInteger();
    private static final AtomicInteger ACTION_QUERY_COUNT = new AtomicInteger();
    private static HttpServer flowOrderStub;

    @Autowired private StreamingAgentExecutor streamingAgentExecutor;
    @Autowired private ApprovalService approvalService;

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
    void responseLossIsReconciledAndRepeatedResumeReusesOneAction() {
        EXECUTED.set(false);
        EXECUTE_COUNT.set(0);
        ACTION_QUERY_COUNT.set(0);
        PROPOSAL_ID.set(null);
        String sessionId = "ordercare-m3-response-lost-" + UUID.randomUUID();
        AgentRequest request = new AgentRequest(
                sessionId, "operator-user",
                "恢复 requestId=ORDERCARE-M3-REQUEST，并验证响应丢失后的确定性结果。",
                Map.of("source", "m3-response-lost", "authenticatedRoles", List.of("operator")),
                AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1
        );

        List<AgentStreamEvent> beforeApproval = events(streamingAgentExecutor.stream(request));
        AgentStreamEvent approval = beforeApproval.stream()
                .filter(event -> "approval_required".equals(event.type()))
                .findFirst().orElseThrow();
        String approvalId = String.valueOf(approval.metadata().get("approvalId"));
        String runId = approval.traceId();
        approvalService.decide(approvalId, true, "operator-li", "核对 Proposal 后批准");

        List<AgentStreamEvent> afterApproval = events(streamingAgentExecutor.resume(runId));
        AgentStreamEvent executeCompleted = afterApproval.stream()
                .filter(event -> completedTool(event, OrderCareToolCatalog.RECOVERY_EXECUTE))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMetadata = (Map<String, Object>) executeCompleted.metadata().get("metadata");

        assertEquals(1, EXECUTE_COUNT.get());
        assertTrue(ACTION_QUERY_COUNT.get() >= 1);
        assertEquals(true, resultMetadata.get("responseLost"));
        assertEquals(true, resultMetadata.get("reconciled"));
        assertEquals("act-ordercare-m3", resultMetadata.get("actionRequestId"));
        assertEquals(approvalId, resultMetadata.get("approvalId"));
        assertTrue(afterApproval.stream().anyMatch(event ->
                "run_completed".equals(event.type()) && event.content().contains("RESOLVED")));

        events(streamingAgentExecutor.resume(runId));
        assertEquals(1, EXECUTE_COUNT.get(), "重复 resume 不能产生第二次 FlowOrder execute");
    }

    private List<AgentStreamEvent> events(Flux<AgentStreamEvent> source) {
        List<AgentStreamEvent> events = source.filter(event -> !"heartbeat".equals(event.type()))
                .collectList().block(Duration.ofSeconds(30));
        assertNotNull(events);
        return events;
    }

    private boolean completedTool(AgentStreamEvent event, String toolName) {
        return "tool_completed".equals(event.type())
                && toolName.equals(event.metadata().get("toolName"))
                && Boolean.TRUE.equals(event.metadata().get("success"));
    }

    private static synchronized void ensureFlowOrderStub() {
        if (flowOrderStub != null) return;
        try {
            flowOrderStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            flowOrderStub.createContext("/internal/recovery/cases/inspect",
                    exchange -> respond(exchange, caseBody(EXECUTED.get())));
            flowOrderStub.createContext("/internal/recovery/proposals", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if ("POST".equals(exchange.getRequestMethod())
                        && "/internal/recovery/proposals".equals(path)) {
                    Map<String, Object> body = requestBody(exchange);
                    PROPOSAL_ID.compareAndSet(null, String.valueOf(body.get("proposalId")));
                    respond(exchange, proposalBody(false));
                    return;
                }
                if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/execute")) {
                    requestBody(exchange);
                    EXECUTE_COUNT.incrementAndGet();
                    EXECUTED.set(true);
                    exchange.close(); // 业务已提交，但调用方收不到 HTTP 响应头。
                    return;
                }
                if ("GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, proposalBody(EXECUTED.get()));
                    return;
                }
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            flowOrderStub.createContext("/internal/recovery/actions/act-ordercare-m3", exchange -> {
                ACTION_QUERY_COUNT.incrementAndGet();
                respond(exchange, actionBody());
            });
            flowOrderStub.start();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to start FlowOrder M3 fault stub", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestBody(HttpExchange exchange) throws IOException {
        return JSON.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String caseBody(boolean resolved) {
        return ("""
                {"code":200,"message":"success","data":{
                  "schemaVersion":"floworder-recovery-case-v1","caseKey":"floworder:request:ORDERCARE-M3-REQUEST",
                  "identifierType":"REQUEST_ID","identifierValue":"ORDERCARE-M3-REQUEST","canonicalRequestId":"ORDERCARE-M3-REQUEST",
                  "found":true,"diagnosisCode":"%s","factsComplete":true,"recoveryEligible":%s,
                  "order":{"dependencyAvailable":true,"exists":true,"orderNo":"order-m3","status":40,"statusName":"TIMEOUT"},
                  "deduct":{"exists":true,"deductNo":"deduct-m3","status":%d,"statusName":"%s","quantity":1},
                  "inventory":{"exists":true,"totalStock":100,"availableStock":100,"lockedStock":0,"soldStock":0,"invariantOk":true},
                  "deadLetters":[{"deadLetterId":301,"messageId":"message-m3","messageType":"ORDER_TIMEOUT","bizKey":"deduct-m3","status":%d,"replayCount":1}],
                  "recoveryActions":[],"candidates":%s,"evidence":["ORDER_TIMEOUT"],"hardRisks":[]}}
                """).formatted(
                resolved ? "ALREADY_CONVERGED" : "REPLAY_CANDIDATE", !resolved,
                resolved ? 30 : 20, resolved ? "RELEASED" : "ORDER_CREATED", resolved ? 20 : 0,
                resolved ? "[]" : "[{\"candidateId\":\"replay-dead-letter-301\",\"actionType\":\"REPLAY\",\"targetType\":\"DEAD_LETTER\",\"targetKey\":\"301\",\"eligible\":true,\"decisionOwner\":\"FLOWORDER\",\"blockedBy\":\"\"}]"
        );
    }

    private static String proposalBody(boolean resolved) {
        return ("""
                {"code":200,"message":"success","data":{
                  "schemaVersion":"floworder-recovery-proposal-v1","proposalId":"%s","proposalVersion":1,
                  "proposalStatus":"%s","actionRequestId":"act-ordercare-m3","actionStatus":"%s","caseOutcome":"%s",
                  "caseKey":"floworder:request:ORDERCARE-M3-REQUEST","identifierType":"REQUEST_ID","identifierValue":"ORDERCARE-M3-REQUEST",
                  "actionType":"REPLAY","targetType":"DEAD_LETTER","targetKey":"301","stateFingerprint":"fingerprint-m3",
                  "effectsDigest":"effects-m3","warningsDigest":"warnings-m3","previewDigest":"preview-m3","canExecute":%s,
                  "effects":["replay original message"],"warnings":["approval required"],
                  "suggestedReason":"timeout recovery","expiresAt":"2099-07-17T18:00:00"}}
                """).formatted(PROPOSAL_ID.get(), resolved ? "APPROVED" : "ACTIVE",
                resolved ? "SUBMITTED" : "NOT_STARTED", resolved ? "RESOLVED" : "NOT_CONVERGED", !resolved);
    }

    private static String actionBody() {
        return ("""
                {"code":200,"message":"success","data":{
                  "schemaVersion":"floworder-recovery-action-v1","proposalId":"%s","actionRequestId":"act-ordercare-m3",
                  "actionType":"REPLAY","targetType":"DEAD_LETTER","targetKey":"301","actionStatus":"SUBMITTED",
                  "caseOutcome":"RESOLVED","reconciliationStatus":"RESOLVED","executionOwner":"tool-exec-m3",
                  "leaseExpired":false,"reconcileCount":1}}
                """).formatted(PROPOSAL_ID.get());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ModelGatewayConfiguration {
        @Bean
        @Primary
        AgentModelGateway orderCareM3ModelGateway() {
            return request -> {
                List<AgentMessage> results = request.messages().stream()
                        .filter(message -> message.type() == AgentMessageType.TOOL_RESULT).toList();
                LlmUsage usage = new LlmUsage(120, 50, 170, 0, 0, "deterministic-m3", "test");
                if (results.isEmpty()) return turn("m3-inspect", OrderCareToolCatalog.CASE_INSPECT,
                        Map.of("identifierType", "REQUEST_ID", "identifierValue", "ORDERCARE-M3-REQUEST"), usage);
                if (results.size() == 1) return turn("m3-preview", OrderCareToolCatalog.RECOVERY_PREVIEW,
                        Map.of("identifierType", "REQUEST_ID", "identifierValue", "ORDERCARE-M3-REQUEST",
                                "suggestedReason", "timeout recovery"), usage);
                if (results.size() == 2) return turn("m3-execute", OrderCareToolCatalog.RECOVERY_EXECUTE,
                        Map.of("proposalId", PROPOSAL_ID.get()), usage);
                return new AgentModelTurn(
                        "响应虽丢失，但原 actionRequestId 对账确认 convergence.status=RESOLVED。",
                        List.of(), "final", usage, "final_answer"
                );
            };
        }

        private AgentModelTurn turn(String id, String tool, Map<String, Object> args, LlmUsage usage) {
            return new AgentModelTurn("", List.of(new AgentToolCall(id, tool, args, "M3 fault flow")),
                    "tool", usage, "tool_calls");
        }
    }
}
