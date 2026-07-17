package com.agent.platform.ordercare;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.ordercare.config.AgentScenarioProfileResolver;
import com.agent.platform.ordercare.tool.OrderCareToolCatalog;
import com.agent.platform.runtime.AgentMessage;
import com.agent.platform.runtime.AgentMessageType;
import com.agent.platform.runtime.AgentModelGateway;
import com.agent.platform.runtime.AgentModelTurn;
import com.agent.platform.runtime.AgentToolCall;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 统一闭环证据：真实 PostgreSQL + Runtime 暂停/审批/恢复，FlowOrder 使用强类型 HTTP stub。
 */
@SpringBootTest(properties = {
        "enterprise-agent.mock-mode=true",
        "enterprise-agent.ordercare.inspect-max-attempts=1",
        "enterprise-agent.ordercare.convergence-max-attempts=3",
        "enterprise-agent.ordercare.convergence-interval-millis=0",
        "enterprise-agent.resilience.rate-limit.enabled=false"
})
@Import(OrderCareControlledRecoveryRuntimeE2ETests.ModelGatewayConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ORDERCARE_RUNTIME_E2E", matches = "true")
class OrderCareControlledRecoveryRuntimeE2ETests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREVIEW_TOOL_CALL_ID = "model-call-ordercare-m2-preview";
    private static final String EXECUTE_TOOL_CALL_ID = "model-call-ordercare-m2-execute";
    private static final AtomicReference<String> PROPOSAL_ID = new AtomicReference<>();
    private static final AtomicBoolean EXECUTED = new AtomicBoolean(false);
    private static final AtomicInteger EXECUTE_COUNT = new AtomicInteger();
    private static final AtomicReference<Map<String, Object>> EXECUTE_BODY = new AtomicReference<>();
    private static HttpServer flowOrderStub;

    @Autowired
    private StreamingAgentExecutor streamingAgentExecutor;

    @Autowired
    private ApprovalService approvalService;

    @DynamicPropertySource
    static void orderCareProperties(DynamicPropertyRegistry registry) {
        ensureFlowOrderStub();
        registry.add(
                "enterprise-agent.ordercare.floworder-base-url",
                () -> "http://127.0.0.1:" + flowOrderStub.getAddress().getPort()
        );
    }

    @AfterAll
    static void stopStub() {
        if (flowOrderStub != null) {
            flowOrderStub.stop(0);
        }
    }

    @Test
    void pausesOnImmutableProposalApprovalThenResumesOriginalToolCallToConvergence() {
        EXECUTED.set(false);
        EXECUTE_COUNT.set(0);
        EXECUTE_BODY.set(null);
        PROPOSAL_ID.set(null);
        String sessionId = "ordercare-m2-e2e-" + UUID.randomUUID();
        AgentRequest request = new AgentRequest(
                sessionId,
                "ordercare-e2e-user",
                "请恢复 requestId=ORDERCARE-M2-REQUEST，严格走预演、审批和收敛验证。",
                Map.of("source", "m2-e2e", "authenticatedRoles", List.of("operator")),
                AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1
        );

        List<AgentStreamEvent> beforeApproval = events(streamingAgentExecutor.stream(request));
        assertTrue(beforeApproval.stream().anyMatch(event -> "approval_required".equals(event.type())),
                () -> "approval_required missing, events=" + beforeApproval);
        AgentStreamEvent approvalEvent = beforeApproval.stream()
                .filter(event -> "approval_required".equals(event.type()))
                .findFirst()
                .orElseThrow();
        String approvalId = String.valueOf(approvalEvent.metadata().get("approvalId"));
        String runId = approvalEvent.traceId();
        assertTrue(beforeApproval.stream().anyMatch(event -> completedTool(event, OrderCareToolCatalog.CASE_INSPECT)));
        assertTrue(beforeApproval.stream().anyMatch(event -> completedTool(event, OrderCareToolCatalog.RECOVERY_PREVIEW)));
        assertTrue(beforeApproval.stream().anyMatch(event -> requestedTool(event, OrderCareToolCatalog.RECOVERY_EXECUTE)));
        assertFalse(EXECUTED.get());

        ApprovalRecord requested = approvalService.find(approvalId).orElseThrow();
        assertEquals(ApprovalStatus.REQUESTED, requested.status());
        assertEquals(PROPOSAL_ID.get(), requested.toolCallRequest().arguments().get("proposalId"));
        assertEquals("fingerprint-m2", requested.toolCallRequest().arguments().get("stateFingerprint"));
        assertEquals("preview-digest-m2", requested.toolCallRequest().arguments().get("previewDigest"));
        assertEquals(List.of("replay original message"), requested.toolCallRequest().arguments().get("effects"));
        assertFalse(requested.toolCallRequest().arguments().containsKey("force"));

        approvalService.decide(approvalId, true, "operator-zhang", "已核对影响、警告和有效期");
        List<AgentStreamEvent> afterApproval = events(streamingAgentExecutor.resume(runId));

        assertTrue(afterApproval.stream().anyMatch(event -> completedTool(event, OrderCareToolCatalog.RECOVERY_EXECUTE)));
        assertTrue(afterApproval.stream().anyMatch(event ->
                "run_completed".equals(event.type())
                        && event.content().contains("RESOLVED")));
        assertEquals(1, EXECUTE_COUNT.get());
        assertTrue(EXECUTED.get());
        Map<String, Object> executeBody = EXECUTE_BODY.get();
        assertNotNull(executeBody);
        assertEquals(approvalId, executeBody.get("approvalId"));
        assertEquals("operator-zhang", executeBody.get("approvedBy"));
        assertEquals("已核对影响、警告和有效期", executeBody.get("approvalComment"));
        assertEquals("preview-digest-m2", executeBody.get("previewDigest"));
    }

    private List<AgentStreamEvent> events(Flux<AgentStreamEvent> source) {
        List<AgentStreamEvent> events = source
                .filter(event -> !"heartbeat".equals(event.type()))
                .collectList()
                .block(Duration.ofSeconds(30));
        assertNotNull(events);
        return events;
    }

    private boolean requestedTool(AgentStreamEvent event, String toolName) {
        return "tool_requested".equals(event.type())
                && toolName.equals(event.metadata().get("toolName"));
    }

    private boolean completedTool(AgentStreamEvent event, String toolName) {
        return "tool_completed".equals(event.type())
                && toolName.equals(event.metadata().get("toolName"))
                && Boolean.TRUE.equals(event.metadata().get("success"));
    }

    private static synchronized void ensureFlowOrderStub() {
        if (flowOrderStub != null) {
            return;
        }
        try {
            flowOrderStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            flowOrderStub.createContext("/internal/recovery/cases/inspect", exchange ->
                    respond(exchange, caseBody(EXECUTED.get())));
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
                    EXECUTE_BODY.set(requestBody(exchange));
                    EXECUTE_COUNT.incrementAndGet();
                    EXECUTED.set(true);
                    respond(exchange, proposalBody(true));
                    return;
                }
                if ("GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, proposalBody(EXECUTED.get()));
                    return;
                }
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            flowOrderStub.start();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to start FlowOrder M2 contract stub", exception);
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
        int deductStatus = resolved ? 30 : 20;
        int deadStatus = resolved ? 20 : 0;
        String diagnosis = resolved ? "ALREADY_CONVERGED" : "REPLAY_CANDIDATE";
        return ("""
                {"code":200,"message":"success","data":{
                  "schemaVersion":"floworder-recovery-case-v1",
                  "caseKey":"floworder:request:ORDERCARE-M2-REQUEST",
                  "identifierType":"REQUEST_ID",
                  "identifierValue":"ORDERCARE-M2-REQUEST",
                  "canonicalRequestId":"ORDERCARE-M2-REQUEST",
                  "found":true,
                  "diagnosisCode":"%s",
                  "factsComplete":true,
                  "recoveryEligible":%s,
                  "order":{"dependencyAvailable":true,"exists":true,"orderNo":"order-m2","status":40,"statusName":"TIMEOUT","queryError":""},
                  "deduct":{"exists":true,"id":1,"deductNo":"deduct-m2","orderNo":"order-m2","stockItemId":1,"quantity":1,"status":%d,"statusName":"%s","releaseReason":"TIMEOUT","lastError":"","updatedAt":"2026-07-17T17:51:00"},
                  "inventory":{"exists":true,"stockItemId":1,"totalStock":100,"availableStock":100,"lockedStock":0,"soldStock":0,"invariantDiff":0,"invariantOk":true,"version":2,"updatedAt":"2026-07-17T17:51:00"},
                  "deadLetters":[{"deadLetterId":101,"messageId":"message-m2","messageType":"ORDER_TIMEOUT","bizKey":"deduct-m2","status":%d,"statusName":"%s","replayCount":1}],
                  "recoveryActions":[],
                  "candidates":%s,
                  "evidence":["ORDER_TIMEOUT"],
                  "hardRisks":[]
                }}
                """).formatted(
                diagnosis,
                !resolved,
                deductStatus,
                resolved ? "RELEASED" : "ORDER_CREATED",
                deadStatus,
                resolved ? "RESOLVED" : "PENDING",
                resolved ? "[]" : "[{\"candidateId\":\"replay-dead-letter-101\",\"actionType\":\"REPLAY\",\"targetType\":\"DEAD_LETTER\",\"targetKey\":\"101\",\"eligible\":true,\"decisionOwner\":\"FLOWORDER\",\"blockedBy\":\"\"}]"
        );
    }

    private static String proposalBody(boolean resolved) {
        return ("""
                {"code":200,"message":"success","data":{
                  "schemaVersion":"floworder-recovery-proposal-v1",
                  "proposalId":"%s",
                  "proposalVersion":1,
                  "proposalStatus":"%s",
                  "actionRequestId":"act-ordercare-m2",
                  "actionStatus":"%s",
                  "caseOutcome":"%s",
                  "caseKey":"floworder:request:ORDERCARE-M2-REQUEST",
                  "identifierType":"REQUEST_ID",
                  "identifierValue":"ORDERCARE-M2-REQUEST",
                  "actionType":"REPLAY",
                  "targetType":"DEAD_LETTER",
                  "targetKey":"101",
                  "stateFingerprint":"fingerprint-m2",
                  "effectsDigest":"effects-digest-m2",
                  "warningsDigest":"warnings-digest-m2",
                  "previewDigest":"preview-digest-m2",
                  "canExecute":%s,
                  "effects":["replay original message"],
                  "warnings":["human approval required"],
                  "suggestedReason":"diagnosed timeout dead letter",
                  "expiresAt":"2099-07-17T18:00:00"
                }}
                """).formatted(
                PROPOSAL_ID.get(),
                resolved ? "APPROVED" : "ACTIVE",
                resolved ? "SUBMITTED" : "NOT_STARTED",
                resolved ? "RESOLVED" : "NOT_CONVERGED",
                !resolved
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ModelGatewayConfiguration {

        @Bean
        @Primary
        AgentModelGateway orderCareM2ModelGateway() {
            return request -> {
                List<AgentMessage> toolResults = request.messages().stream()
                        .filter(message -> message.type() == AgentMessageType.TOOL_RESULT)
                        .toList();
                LlmUsage usage = new LlmUsage(140, 60, 200, 0, 0, "deterministic-m2-e2e", "test");
                if (toolResults.isEmpty()) {
                    return toolTurn(
                            "model-call-ordercare-m2-inspect",
                            OrderCareToolCatalog.CASE_INSPECT,
                            Map.of("identifierType", "REQUEST_ID", "identifierValue", "ORDERCARE-M2-REQUEST"),
                            usage
                    );
                }
                if (toolResults.size() == 1) {
                    return toolTurn(
                            PREVIEW_TOOL_CALL_ID,
                            OrderCareToolCatalog.RECOVERY_PREVIEW,
                            Map.of(
                                    "identifierType", "REQUEST_ID",
                                    "identifierValue", "ORDERCARE-M2-REQUEST",
                                    "suggestedReason", "diagnosed timeout dead letter"
                            ),
                            usage
                    );
                }
                if (toolResults.size() == 2) {
                    return toolTurn(
                            EXECUTE_TOOL_CALL_ID,
                            OrderCareToolCatalog.RECOVERY_EXECUTE,
                            Map.of("proposalId", PROPOSAL_ID.get()),
                            usage
                    );
                }
                return new AgentModelTurn(
                        "恢复闭环完成：proposalStatus=APPROVED，actionStatus=SUBMITTED，caseOutcome=RESOLVED，convergence.status=RESOLVED。",
                        List.of(),
                        "final answer",
                        usage,
                        "final_answer"
                );
            };
        }

        private AgentModelTurn toolTurn(String callId,
                                        String toolName,
                                        Map<String, Object> arguments,
                                        LlmUsage usage) {
            return new AgentModelTurn(
                    "",
                    List.of(new AgentToolCall(callId, toolName, arguments, "OrderCare M2 controlled flow")),
                    "tool call",
                    usage,
                    "tool_calls"
            );
        }
    }
}
