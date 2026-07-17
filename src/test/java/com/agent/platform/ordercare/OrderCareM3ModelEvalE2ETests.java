package com.agent.platform.ordercare;

import com.agent.platform.eval.EvalReport;
import com.agent.platform.eval.EvalRunner;
import com.agent.platform.eval.OrderCareM3EvalSuite;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 真实模型下复跑 M3 的 20 条业务 Eval，FlowOrder 使用稳定契约桩隔离数据漂移。 */
@SpringBootTest(properties = {
        "enterprise-agent.mock-mode=false",
        "enterprise-agent.rag.cache.enabled=false",
        "enterprise-agent.resilience.rate-limit.enabled=false",
        "enterprise-agent.resilience.llm.timeout-millis=30000",
        "enterprise-agent.resilience.llm.max-attempts=2"
})
@Import(OrderCareM3ModelEvalE2ETests.EvalDependencyConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ORDERCARE_MODEL_EVAL", matches = "true")
class OrderCareM3ModelEvalE2ETests {

    private static final Pattern PROPOSAL_ID = Pattern.compile("\\\"proposalId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Map<String, String> PROPOSALS = new ConcurrentHashMap<>();
    private static HttpServer flowOrderStub;

    @Autowired private EvalRunner evalRunner;
    @Autowired private OrderCareM3EvalSuite evalSuite;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void flowOrderProperties(DynamicPropertyRegistry registry) {
        ensureFlowOrderStub();
        registry.add("enterprise-agent.ordercare.floworder-base-url",
                () -> "http://127.0.0.1:" + flowOrderStub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        if (flowOrderStub != null) flowOrderStub.stop(0);
    }

    @Test
    void evaluatesTwentyM3BusinessCasesWithRealModel() throws Exception {
        EvalReport report = evalRunner.run(evalSuite.cases());
        Path reportPath = Path.of("target", "ordercare-m3-model-eval.json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);

        System.out.printf(
                "OrderCare M3 model eval: passed=%d/%d, passRate=%.3f, toolSuccess=%.3f, toolPrecision=%.3f, report=%s%n",
                report.passedCases(), report.totalCases(), report.passRate(), report.toolCallSuccessRate(),
                report.metrics().toolPrecision(), reportPath.toAbsolutePath());

        assertEquals(20, report.totalCases());
        assertTrue(report.passRate() >= 0.8, "M3 model eval pass rate must be at least 80%");
        assertTrue(report.toolCallSuccessRate() >= 0.8, "M3 tool routing accuracy must be at least 80%");
        assertTrue(report.metrics().toolPrecision() >= 0.8, "M3 must avoid unnecessary tools");
        assertEquals(0.0, report.metrics().forbiddenViolationRate(),
                "M3 must not claim unverified side effects");
    }

    private static synchronized void ensureFlowOrderStub() {
        if (flowOrderStub != null) return;
        try {
            flowOrderStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            flowOrderStub.createContext("/internal/recovery/cases/inspect", exchange -> {
                String query = URLDecoder.decode(
                        String.valueOf(exchange.getRequestURI().getRawQuery()), StandardCharsets.UTF_8
                );
                write(exchange, caseFor(query));
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
                    String value = proposal(proposalId);
                    PROPOSALS.put(proposalId, value);
                    write(exchange, value);
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
            throw new IllegalStateException("failed to start FlowOrder M3 eval stub", exception);
        }
    }

    private static String caseFor(String query) {
        String identifierValue = queryParameter(query, "identifierValue");
        if (query.contains("ORDERCARE-NOT-FOUND")) return diagnosisCase(identifierValue, "NO_RECOVERY_EVIDENCE", false, false, 0);
        if (query.contains("ORDERCARE-ALREADY-RESOLVED")) return diagnosisCase(identifierValue, "ALREADY_CONVERGED", true, false, 20);
        if (query.contains("ORDERCARE-DEPENDENCY-DOWN")) return diagnosisCase(identifierValue, "DEPENDENCY_UNAVAILABLE", true, false, 0);
        if (query.contains("ORDERCARE-FACT-CONFLICT")) return diagnosisCase(identifierValue, "FACT_CONFLICT", true, false, 0);
        if (query.contains("ORDERCARE-UNSUPPORTED-EVENT")) return diagnosisCase(identifierValue, "UNSUPPORTED_EVENT", true, false, 0);
        if (query.contains("ORDERCARE-REPLAYING")) return diagnosisCase(identifierValue, "RECOVERY_IN_PROGRESS", true, false, 10);
        return diagnosisCase(identifierValue, "REPLAY_CANDIDATE", true, true, 0);
    }

    private static String queryParameter(String query, String name) {
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator > 0 && name.equals(part.substring(0, separator))) {
                return part.substring(separator + 1);
            }
        }
        return "UNKNOWN";
    }

    private static String diagnosisCase(String identifierValue,
                                        String diagnosis,
                                        boolean found,
                                        boolean eligible,
                                        int deadStatus) {
        String candidates = eligible
                ? "[{\"candidateId\":\"replay-dead-letter-9000000000000505\",\"actionType\":\"REPLAY\",\"targetType\":\"DEAD_LETTER\",\"targetKey\":\"9000000000000505\",\"eligible\":true,\"decisionOwner\":\"FLOWORDER\",\"blockedBy\":\"\"}]"
                : "[]";
        String deadLetters = found
                ? "[{\"deadLetterId\":9000000000000505,\"messageId\":\"ORDERCARE-M3-MESSAGE\",\"messageType\":\"ORDER_TIMEOUT\",\"bizKey\":\"ORDERCARE-M05-DEDUCT\",\"status\":" + deadStatus + ",\"replayCount\":0}]"
                : "[]";
        String businessFacts = "ALREADY_CONVERGED".equals(diagnosis)
                ? """
                  "reservation":{"orderNo":"ORDERCARE-M05-ORDER","orderStatusName":"TIMEOUT"},
                  "order":{"dependencyAvailable":true,"exists":true,"statusName":"TIMEOUT"},
                  "deduct":{"deductNo":"ORDERCARE-M05-DEDUCT","statusName":"RELEASED","quantity":3},
                  "inventory":{"totalStock":10,"availableStock":10,"lockedStock":0,"soldStock":0,"invariantOk":true},
                  """
                : found
                ? """
                  "reservation":{"orderNo":"ORDERCARE-M05-ORDER","orderStatusName":"CREATED"},
                  "order":{"dependencyAvailable":true,"exists":true,"statusName":"TIMEOUT"},
                  "deduct":{"deductNo":"ORDERCARE-M05-DEDUCT","statusName":"ORDER_CREATED","quantity":3},
                  "inventory":{"totalStock":10,"availableStock":7,"lockedStock":3,"soldStock":0,"invariantOk":true},
                  """
                : """
                  "reservation":null,
                  "order":{"dependencyAvailable":true,"exists":false},
                  "deduct":null,
                  "inventory":null,
                  """;
        return """
                {"schemaVersion":"floworder-recovery-case-v1","caseKey":"floworder:request:%s",
                 "identifierType":"REQUEST_ID","identifierValue":"%s","canonicalRequestId":"%s",
                 "found":%s,"diagnosisCode":"%s","factsComplete":%s,"recoveryEligible":%s,
                 %s
                 "deadLetters":%s,"recoveryActions":[],"candidates":%s,
                 "evidence":["FLOWORDER_DIAGNOSIS_%s"],"hardRisks":[]}
                """.formatted(identifierValue, identifierValue, identifierValue,
                found, diagnosis, found, eligible, businessFacts, deadLetters, candidates, diagnosis);
    }

    private static String proposal(String proposalId) {
        return """
                {"schemaVersion":"floworder-recovery-proposal-v1","proposalId":"%s","proposalVersion":1,
                 "proposalStatus":"ACTIVE","actionRequestId":"act-eval-%s","actionStatus":"NOT_STARTED",
                 "caseOutcome":"NOT_CONVERGED","caseKey":"floworder:request:ORDERCARE-M3",
                 "identifierType":"REQUEST_ID","identifierValue":"ORDERCARE-M05-REQUEST","actionType":"REPLAY",
                 "targetType":"DEAD_LETTER","targetKey":"9000000000000505","stateFingerprint":"fingerprint-m3",
                 "effectsDigest":"effects-m3","warningsDigest":"warnings-m3","previewDigest":"preview-m3",
                 "canExecute":true,"effects":["使用既有可靠消息链路重放"],
                 "warnings":["必须审批当前版本","SUBMITTED 不等于 RESOLVED"],
                 "suggestedReason":"恢复库存释放消息","expiresAt":"2099-12-31T23:59:59Z"}
                """.formatted(proposalId, proposalId.replace("prop-", ""));
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

    @TestConfiguration(proxyBeanMethods = false)
    static class EvalDependencyConfiguration {
        @Bean
        @Primary
        RagService orderCareM3EvalRagService() {
            return (query, topK) -> new RagResult(
                    query,
                    List.of(new RetrievedDocument(
                            "ordercare-sop-m3", "OrderCare 恢复 SOP M3",
                            "恢复必须绑定不可变 Proposal 和人工审批。execute 响应丢失后禁止换新 actionRequestId，必须查询原 Action 并确定性对账；只有权威状态为 NOT_STARTED 才可按原参数补发。进程重启和重复 resume 复用 toolExecutionId 与 actionRequestId。SUBMITTED 不等于 RESOLVED；未收敛应报告 NOT_CONVERGED 并转人工，旧 Proposal 过期后必须重新预演和审批。",
                            1.0,
                            Map.of("source", "ordercare-recovery-sop-m3.md", "version", "m3")
                    )),
                    true
            );
        }
    }
}
