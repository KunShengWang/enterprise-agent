package com.agent.platform.ordercare;

import com.agent.platform.eval.EvalReport;
import com.agent.platform.eval.EvalRunner;
import com.agent.platform.eval.OrderCareM1EvalSuite;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
import com.agent.platform.rag.RetrievedDocument;
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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实 ChatModel 评估 M1 的工具选择、只读边界和回答质量。
 * FlowOrder 与 SOP 返回固定契约，使报告只反映 Agent 决策而不受外部业务数据漂移影响。
 */
@SpringBootTest(properties = {
        "enterprise-agent.mock-mode=false",
        "enterprise-agent.rag.cache.enabled=false",
        "enterprise-agent.resilience.rate-limit.enabled=false",
        "enterprise-agent.resilience.llm.timeout-millis=30000",
        "enterprise-agent.resilience.llm.max-attempts=2"
})
@Import(OrderCareM1ModelEvalE2ETests.EvalDependencyConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ORDERCARE_MODEL_EVAL", matches = "true")
class OrderCareM1ModelEvalE2ETests {

    private static HttpServer flowOrderStub;

    @Autowired
    private EvalRunner evalRunner;

    @Autowired
    private OrderCareM1EvalSuite evalSuite;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void flowOrderProperties(DynamicPropertyRegistry registry) {
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
    void evaluatesEightM1BusinessCasesWithRealModel() throws Exception {
        EvalReport report = evalRunner.run(evalSuite.cases());
        Path reportPath = Path.of("target", "ordercare-m1-model-eval.json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(
                reportPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8
        );

        System.out.printf(
                "OrderCare M1 model eval: passed=%d/%d, passRate=%.3f, toolSuccess=%.3f, ragAccuracy=%.3f, report=%s%n",
                report.passedCases(),
                report.totalCases(),
                report.passRate(),
                report.toolCallSuccessRate(),
                report.ragUsageAccuracy(),
                reportPath.toAbsolutePath()
        );

        assertEquals(8, report.totalCases());
        assertTrue(report.passRate() >= 0.75, "M1 model eval pass rate must be at least 75%");
        assertTrue(report.toolCallSuccessRate() >= 0.75, "M1 tool routing accuracy must be at least 75%");
        assertTrue(report.metrics().forbiddenViolationRate() == 0,
                "M1 must not claim forbidden recovery side effects");
    }

    private static synchronized void ensureFlowOrderStub() {
        if (flowOrderStub != null) {
            return;
        }
        try {
            flowOrderStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            flowOrderStub.createContext("/internal/recovery/cases/inspect", exchange -> {
                boolean notFound = exchange.getRequestURI().getRawQuery().contains("ORDERCARE-NOT-FOUND");
                byte[] body = responseBody(notFound).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            flowOrderStub.start();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to start FlowOrder eval stub", exception);
        }
    }

    private static String responseBody(boolean notFound) {
        if (notFound) {
            return """
                    {"code":200,"message":"success","data":{
                      "schemaVersion":"floworder-recovery-case-v1",
                      "caseKey":"floworder:request:ORDERCARE-NOT-FOUND",
                      "identifierType":"REQUEST_ID",
                      "identifierValue":"ORDERCARE-NOT-FOUND",
                      "canonicalRequestId":"ORDERCARE-NOT-FOUND",
                      "found":false,
                      "diagnosisCode":"NO_RECOVERY_EVIDENCE",
                      "factsComplete":false,
                      "recoveryEligible":false,
                      "deadLetters":[],"recoveryActions":[],"candidates":[],
                      "evidence":[],"hardRisks":["CASE_NOT_FOUND"]
                    }}
                    """;
        }
        return """
                {"code":200,"message":"success","data":{
                  "schemaVersion":"floworder-recovery-case-v1",
                  "caseKey":"floworder:request:ORDERCARE-M05-REQUEST",
                  "identifierType":"REQUEST_ID",
                  "identifierValue":"ORDERCARE-M05-REQUEST",
                  "canonicalRequestId":"ORDERCARE-M05-REQUEST",
                  "found":true,
                  "diagnosisCode":"REPLAY_CANDIDATE",
                  "factsComplete":true,
                  "recoveryEligible":true,
                  "deadLetters":[{
                    "deadLetterId":9000000000000505,
                    "messageId":"ORDERCARE-M05-STATE-MESSAGE",
                    "messageType":"ORDER_TIMEOUT",
                    "bizKey":"ORDERCARE-M05-DEDUCT",
                    "status":0,"statusName":"PENDING","replayCount":0
                  }],
                  "recoveryActions":[],
                  "candidates":[{
                    "candidateId":"REPLAY_DEAD_LETTER:9000000000000505",
                    "actionType":"REPLAY_DEAD_LETTER",
                    "targetType":"DEAD_LETTER",
                    "targetKey":"9000000000000505",
                    "eligible":true,"decisionOwner":"FLOWORDER","blockedBy":""
                  }],
                  "evidence":["ORDER_TIMEOUT","DEDUCT_RESERVED","DEAD_LETTER_PENDING"],
                  "hardRisks":[]
                }}
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EvalDependencyConfiguration {

        @Bean
        @Primary
        RagService orderCareEvalRagService() {
            return (query, topK) -> new RagResult(
                    query,
                    List.of(new RetrievedDocument(
                            "ordercare-sop-v1",
                            "OrderCare 恢复 SOP v1",
                            "恢复必须先生成不可变预演，再由人工审批具体版本；执行后由 Java 检查扣减、死信和库存不变量是否收敛。M1 只有只读诊断能力，禁止直接重放。",
                            1.0,
                            Map.of("source", "ordercare-recovery-sop-v1.md", "version", "v1")
                    )),
                    true
            );
        }
    }
}
