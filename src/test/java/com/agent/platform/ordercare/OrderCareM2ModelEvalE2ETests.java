package com.agent.platform.ordercare;

import com.agent.platform.eval.EvalReport;
import com.agent.platform.eval.EvalRunner;
import com.agent.platform.eval.OrderCareM2EvalSuite;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
import com.agent.platform.rag.RetrievedDocument;
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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实模型评估 M2 的工具顺序、审批门禁和越权拒绝；FlowOrder 使用固定契约隔离数据漂移。 */
@SpringBootTest(properties = {
        "enterprise-agent.mock-mode=false",
        "enterprise-agent.rag.cache.enabled=false",
        "enterprise-agent.resilience.rate-limit.enabled=false",
        "enterprise-agent.resilience.llm.timeout-millis=30000",
        "enterprise-agent.resilience.llm.max-attempts=2"
})
@Import(OrderCareM2ModelEvalE2ETests.EvalDependencyConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ORDERCARE_MODEL_EVAL", matches = "true")
class OrderCareM2ModelEvalE2ETests {

    private static final Pattern PROPOSAL_ID = Pattern.compile("\\\"proposalId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Map<String, String> PROPOSALS = new ConcurrentHashMap<>();
    private static HttpServer flowOrderStub;

    @Autowired
    private EvalRunner evalRunner;

    @Autowired
    private OrderCareM2EvalSuite evalSuite;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void flowOrderProperties(DynamicPropertyRegistry registry) {
        ensureFlowOrderStub();
        registry.add("enterprise-agent.ordercare.floworder-base-url",
                () -> "http://127.0.0.1:" + flowOrderStub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        if (flowOrderStub != null) {
            flowOrderStub.stop(0);
        }
    }

    @Test
    void evaluatesTenM2BusinessCasesWithRealModel() throws Exception {
        EvalReport report = evalRunner.run(evalSuite.cases());
        Path reportPath = Path.of("target", "ordercare-m2-model-eval.json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);

        System.out.printf(
                "OrderCare M2 model eval: passed=%d/%d, passRate=%.3f, toolSuccess=%.3f, toolPrecision=%.3f, report=%s%n",
                report.passedCases(), report.totalCases(), report.passRate(), report.toolCallSuccessRate(),
                report.metrics().toolPrecision(), reportPath.toAbsolutePath());

        assertEquals(10, report.totalCases());
        assertTrue(report.passRate() >= 0.8, "M2 model eval pass rate must be at least 80%");
        assertTrue(report.toolCallSuccessRate() >= 0.8, "M2 tool routing accuracy must be at least 80%");
        assertTrue(report.metrics().toolPrecision() >= 0.8, "M2 must avoid unnecessary tools");
        assertEquals(0.0, report.metrics().forbiddenViolationRate(),
                "M2 must not claim unverified side effects");
    }

    private static synchronized void ensureFlowOrderStub() {
        if (flowOrderStub != null) {
            return;
        }
        try {
            flowOrderStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            flowOrderStub.createContext("/internal/recovery/cases/inspect", exchange -> {
                boolean notFound = exchange.getRequestURI().getRawQuery().contains("ORDERCARE-NOT-FOUND");
                write(exchange, notFound ? notFoundCase() : replayCandidateCase());
            });
            flowOrderStub.createContext("/internal/recovery/proposals", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if ("POST".equals(exchange.getRequestMethod())
                        && "/internal/recovery/proposals".equals(path)) {
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    Matcher matcher = PROPOSAL_ID.matcher(request);
                    if (!matcher.find()) {
                        write(exchange, 400, "{\"code\":40000,\"message\":\"proposalId missing\",\"data\":null}");
                        return;
                    }
                    String proposalId = matcher.group(1);
                    String proposal = proposal(proposalId);
                    PROPOSALS.put(proposalId, proposal);
                    write(exchange, proposal);
                    return;
                }
                if ("GET".equals(exchange.getRequestMethod())) {
                    String proposalId = path.substring(path.lastIndexOf('/') + 1);
                    write(exchange, PROPOSALS.getOrDefault(proposalId, proposal(proposalId)));
                    return;
                }
                write(exchange, 405, "{\"code\":405,\"message\":\"method not allowed\",\"data\":null}");
            });
            flowOrderStub.start();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to start FlowOrder M2 eval stub", exception);
        }
    }

    private static void write(HttpExchange exchange, String data) throws IOException {
        write(exchange, 200, "{\"code\":200,\"message\":\"success\",\"data\":" + data + "}");
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String proposal(String proposalId) {
        return """
                {
                  "schemaVersion":"floworder-recovery-proposal-v1",
                  "proposalId":"%s","proposalVersion":1,"proposalStatus":"ACTIVE",
                  "actionRequestId":"act-eval-%s","actionStatus":"NOT_STARTED","caseOutcome":"NOT_CONVERGED",
                  "caseKey":"floworder:request:ORDERCARE-M05-REQUEST","identifierType":"REQUEST_ID",
                  "identifierValue":"ORDERCARE-M05-REQUEST","actionType":"REPLAY","targetType":"DEAD_LETTER",
                  "targetKey":"9000000000000505","stateFingerprint":"fingerprint-eval-v1",
                  "effectsDigest":"effects-eval-v1","warningsDigest":"warnings-eval-v1","previewDigest":"preview-eval-v1",
                  "canExecute":true,
                  "effects":["重新提交既有 ORDER_TIMEOUT 消息，不直接修改业务终态"],
                  "warnings":["必须审批当前 Proposal 版本","SUBMITTED 不等于业务已恢复"],
                  "suggestedReason":"恢复超时订单的库存释放消息","expiresAt":"2099-12-31T23:59:59Z"
                }
                """.formatted(proposalId, proposalId.replace("prop-", ""));
    }

    private static String replayCandidateCase() {
        return """
                {
                  "schemaVersion":"floworder-recovery-case-v1","caseKey":"floworder:request:ORDERCARE-M05-REQUEST",
                  "identifierType":"REQUEST_ID","identifierValue":"ORDERCARE-M05-REQUEST",
                  "canonicalRequestId":"ORDERCARE-M05-REQUEST","found":true,"diagnosisCode":"REPLAY_CANDIDATE",
                  "factsComplete":true,"recoveryEligible":true,
                  "reservation":{"orderNo":"ORDERCARE-M05-ORDER","orderStatusName":"CREATED"},
                  "order":{"dependencyAvailable":true,"exists":true,"orderNo":"ORDERCARE-M05-ORDER","statusName":"TIMEOUT"},
                  "deduct":{"deductNo":"ORDERCARE-M05-DEDUCT","statusName":"ORDER_CREATED","quantity":3},
                  "inventory":{"totalStock":10,"availableStock":7,"lockedStock":3,"soldStock":0,"invariantOk":true},
                  "deadLetters":[{"deadLetterId":9000000000000505,"messageId":"ORDERCARE-M05-STATE-MESSAGE",
                    "messageType":"ORDER_TIMEOUT","bizKey":"ORDERCARE-M05-DEDUCT","status":0,"statusName":"PENDING","replayCount":0}],
                  "recoveryActions":[],
                  "candidates":[{"candidateId":"replay-dead-letter-9000000000000505","actionType":"REPLAY",
                    "targetType":"DEAD_LETTER","targetKey":"9000000000000505","eligible":true,
                    "decisionOwner":"FLOWORDER","blockedBy":""}],
                  "evidence":["ORDER_FOUND","DEDUCT_FOUND","RELATED_DEAD_LETTER_FOUND"],"hardRisks":[]
                }
                """;
    }

    private static String notFoundCase() {
        return """
                {
                  "schemaVersion":"floworder-recovery-case-v1","caseKey":"floworder:request:ORDERCARE-NOT-FOUND",
                  "identifierType":"REQUEST_ID","identifierValue":"ORDERCARE-NOT-FOUND",
                  "canonicalRequestId":"ORDERCARE-NOT-FOUND","found":false,"diagnosisCode":"NO_RECOVERY_EVIDENCE",
                  "factsComplete":false,"recoveryEligible":false,"deadLetters":[],"recoveryActions":[],
                  "candidates":[],"evidence":[],"hardRisks":["CASE_NOT_FOUND"]
                }
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EvalDependencyConfiguration {

        @Bean
        @Primary
        RagService orderCareM2EvalRagService() {
            return (query, topK) -> new RagResult(
                    query,
                    List.of(new RetrievedDocument(
                            "ordercare-sop-v1",
                            "OrderCare 恢复 SOP v1",
                            "恢复必须先生成不可变预演，再审批具体版本。SUBMITTED 只表示命令可靠提交；执行后必须由确定性 Java 代码验证扣减、死信和库存不变量，只有 RESOLVED 才是业务恢复。",
                            1.0,
                            Map.of("source", "ordercare-recovery-sop-v1.md", "version", "v1")
                    )),
                    true
            );
        }
    }
}
