package com.agent.platform.ordercare;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.ordercare.config.AgentScenarioProfileResolver;
import com.agent.platform.ordercare.tool.OrderCareToolCatalog;
import com.agent.platform.runtime.AgentMessageType;
import com.agent.platform.runtime.AgentModelGateway;
import com.agent.platform.runtime.AgentModelTurn;
import com.agent.platform.runtime.AgentToolCall;
import com.agent.platform.stream.AgentStreamEvent;
import com.agent.platform.stream.StreamingAgentExecutor;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 统一窗口证据：真实 PostgreSQL、统一 Runtime、Profile、Tool Runtime 和 HTTP Client。
 * 模型决策使用确定性测试网关，避免把外部模型波动混入执行链正确性门禁。
 */
@SpringBootTest(properties = {
        "enterprise-agent.mock-mode=true",
        "enterprise-agent.ordercare.inspect-max-attempts=1",
        "enterprise-agent.resilience.rate-limit.enabled=false"
})
@Import(OrderCareUnifiedRuntimeE2ETests.ModelGatewayConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ORDERCARE_RUNTIME_E2E", matches = "true")
class OrderCareUnifiedRuntimeE2ETests {

    private static final AtomicReference<String> RECEIVED_TRACE_ID = new AtomicReference<>();
    private static HttpServer flowOrderStub;

    @Autowired
    private StreamingAgentExecutor streamingAgentExecutor;

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
    void completesReadOnlyDiagnosisThroughOnePersistedSseRun() {
        String sessionId = "ordercare-m1-e2e-" + UUID.randomUUID();
        AgentRequest request = new AgentRequest(
                sessionId,
                "ordercare-e2e-user",
                "请诊断 requestId=ORDERCARE-M05-REQUEST，只依据权威事实回答。",
                Map.of("source", "m1-e2e"),
                AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1
        );

        List<AgentStreamEvent> events = streamingAgentExecutor.stream(request)
                .filter(event -> !"heartbeat".equals(event.type()))
                .collectList()
                .block(Duration.ofSeconds(30));

        assertNotNull(events);
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(event ->
                "tool_requested".equals(event.type())
                        && OrderCareToolCatalog.CASE_INSPECT.equals(event.metadata().get("toolName"))));
        assertTrue(events.stream().anyMatch(event ->
                "tool_completed".equals(event.type())
                        && OrderCareToolCatalog.CASE_INSPECT.equals(event.metadata().get("toolName"))
                        && Boolean.TRUE.equals(event.metadata().get("success"))));
        assertTrue(events.stream().anyMatch(event ->
                "run_completed".equals(event.type())
                        && event.content().contains("REPLAY_CANDIDATE")));
        assertTrue(events.stream().map(AgentStreamEvent::traceId)
                .filter(value -> value != null && !value.isBlank())
                .distinct().count() == 1);
        assertNotNull(RECEIVED_TRACE_ID.get());
        assertFalse(RECEIVED_TRACE_ID.get().isBlank());
    }

    private static synchronized void ensureFlowOrderStub() {
        if (flowOrderStub != null) {
            return;
        }
        try {
            flowOrderStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            flowOrderStub.createContext("/internal/recovery/cases/inspect", exchange -> {
                RECEIVED_TRACE_ID.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
                byte[] body = responseBody().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            flowOrderStub.start();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to start FlowOrder contract stub", exception);
        }
    }

    private static String responseBody() {
        return """
                {
                  "code": 200,
                  "message": "success",
                  "data": {
                    "schemaVersion": "floworder-recovery-case-v1",
                    "caseKey": "floworder:request:ORDERCARE-M05-REQUEST",
                    "identifierType": "REQUEST_ID",
                    "identifierValue": "ORDERCARE-M05-REQUEST",
                    "canonicalRequestId": "ORDERCARE-M05-REQUEST",
                    "found": true,
                    "diagnosisCode": "REPLAY_CANDIDATE",
                    "factsComplete": true,
                    "recoveryEligible": true,
                    "deadLetters": [{
                      "deadLetterId": 9000000000000505,
                      "messageId": "ORDERCARE-M05-STATE-MESSAGE",
                      "messageType": "ORDER_TIMEOUT",
                      "bizKey": "ORDERCARE-M05-DEDUCT",
                      "status": 0,
                      "statusName": "PENDING",
                      "replayCount": 0
                    }],
                    "recoveryActions": [],
                    "candidates": [{
                      "candidateId": "REPLAY_DEAD_LETTER:9000000000000505",
                      "actionType": "REPLAY_DEAD_LETTER",
                      "targetType": "DEAD_LETTER",
                      "targetKey": "9000000000000505",
                      "eligible": true,
                      "decisionOwner": "FLOWORDER",
                      "blockedBy": ""
                    }],
                    "evidence": ["ORDER_TIMEOUT", "DEDUCT_RESERVED", "DEAD_LETTER_PENDING"],
                    "hardRisks": []
                  }
                }
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ModelGatewayConfiguration {

        @Bean
        @Primary
        AgentModelGateway orderCareM1ModelGateway() {
            return request -> {
                boolean hasToolResult = request.messages().stream()
                        .anyMatch(message -> message.type() == AgentMessageType.TOOL_RESULT);
                LlmUsage usage = new LlmUsage(120, 60, 180, 0, 0, "deterministic-e2e", "test");
                if (!hasToolResult) {
                    AgentToolCall call = new AgentToolCall(
                            "model-call-ordercare-m1",
                            OrderCareToolCatalog.CASE_INSPECT,
                            Map.of(
                                    "identifierType", "REQUEST_ID",
                                    "identifierValue", "ORDERCARE-M05-REQUEST"
                            ),
                            "聚合 FlowOrder 权威案例事实"
                    );
                    return new AgentModelTurn("", List.of(call), "tool call", usage, "tool_calls");
                }
                return new AgentModelTurn(
                        "诊断代码 REPLAY_CANDIDATE；当前仅完成只读诊断，下一步应生成预演并等待人工审批。",
                        List.of(),
                        "final answer",
                        usage,
                        "final_answer"
                );
            };
        }
    }
}
