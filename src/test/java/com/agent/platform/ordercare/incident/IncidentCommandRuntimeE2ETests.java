package com.agent.platform.ordercare.incident;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.memory.MemorySearchResult;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.memory.UserProfile;
import com.agent.platform.ordercare.incident.application.IncidentInvestigationOrchestrator;
import com.agent.platform.ordercare.incident.application.IncidentTraceProjector;
import com.agent.platform.ordercare.incident.recovery.application.IncidentRecoveryExecutionService;
import com.agent.platform.ordercare.incident.recovery.application.IncidentRecoveryPlanner;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanDecisionRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanItemStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.model.OrderCareActionReconcileCommand;
import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.agent.platform.ordercare.model.OrderCareProposalCreateCommand;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryAction;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.ordercare.incident.model.EvidenceConflictType;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentInvestigationRequest;
import com.agent.platform.ordercare.incident.model.IncidentInvestigationResult;
import com.agent.platform.ordercare.incident.model.IncidentOutcome;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.tool.IncidentToolCatalog;
import com.agent.platform.runtime.AgentMessageType;
import com.agent.platform.runtime.AgentModelGateway;
import com.agent.platform.runtime.AgentModelTurn;
import com.agent.platform.runtime.AgentToolCall;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-E 三条纵向 E2E：真实 PostgreSQL、真实 Runtime/Tool/事务/Trace 投影，
 * 仅把外部 FlowOrder、Rabbit Management 与模型替换为确定性契约 Stub。
 */
@SpringBootTest(properties = {
        "enterprise-agent.mock-mode=true",
        "enterprise-agent.ordercare.incident-command.enabled=true",
        "enterprise-agent.ordercare.incident-command.recovery-planner-enabled=true",
        "enterprise-agent.ordercare.inspect-max-attempts=1",
        "enterprise-agent.ordercare.incident.rabbitmq-management.max-attempts=1",
        "enterprise-agent.ordercare.incident.rabbitmq-management.connect-timeout-millis=100",
        "enterprise-agent.ordercare.incident.rabbitmq-management.read-timeout-millis=120",
        "enterprise-agent.resilience.rate-limit.enabled=false"
})
@Import(IncidentCommandRuntimeE2ETests.DeterministicModelConfiguration.class)
@EnabledIfEnvironmentVariable(named = "INCIDENT_COMMAND_E2E", matches = "true")
class IncidentCommandRuntimeE2ETests {

    private enum Scenario { HAPPY, CONFLICT_126_100_93, MQ_TIMEOUT_PARTIAL }

    private static final AtomicReference<Scenario> SCENARIO = new AtomicReference<>(Scenario.HAPPY);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> INCIDENT_IDS = new ArrayList<>();
    private static final List<String> RECOVERY_PLAN_IDS = new ArrayList<>();
    private static final List<String> APPROVAL_IDS = new ArrayList<>();
    private static final List<String> PROPOSAL_IDS = new ArrayList<>();
    private static final AtomicReference<String> PLANNER_REQUEST_ID = new AtomicReference<>("");
    private static final AtomicReference<String> PLANNER_EVIDENCE_ID = new AtomicReference<>("");
    private static final AtomicInteger RECOVERY_EXECUTE_CALLS = new AtomicInteger();
    private static HttpServer dependencyStub;

    @Autowired
    private IncidentInvestigationOrchestrator orchestrator;

    @Autowired
    private IncidentTraceProjector traceProjector;

    @Autowired
    private IncidentRecoveryPlanner recoveryPlanner;

    @Autowired
    private IncidentRecoveryExecutionService recoveryExecutionService;

    @Autowired
    private AgentStorageProperties storageProperties;

    @DynamicPropertySource
    static void dependencyProperties(DynamicPropertyRegistry registry) {
        ensureDependencyStub();
        String baseUrl = "http://127.0.0.1:" + dependencyStub.getAddress().getPort();
        registry.add("enterprise-agent.ordercare.floworder-base-url", () -> baseUrl);
        registry.add("enterprise-agent.ordercare.incident.rabbitmq-management.base-url", () -> baseUrl + "/api");
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                storageProperties.getDatasource().getUrl(),
                storageProperties.getDatasource().getUsername(),
                storageProperties.getDatasource().getPassword())) {
            for (String incidentId : List.copyOf(INCIDENT_IDS)) {
                delete(connection, "DELETE FROM agent_incident_recovery_plan WHERE incident_id = ?", incidentId);
                delete(connection, "DELETE FROM agent_task_event WHERE incident_id = ?", incidentId);
                delete(connection, "DELETE FROM agent_evidence WHERE incident_id = ?", incidentId);
                delete(connection, "DELETE FROM agent_task WHERE incident_id = ?", incidentId);
                deleteRuntime(connection, incidentId);
                delete(connection, "DELETE FROM agent_incident WHERE incident_id = ?", incidentId);
            }
            for (String approvalId : List.copyOf(APPROVAL_IDS)) {
                deleteStoreRecord(connection, "approval", approvalId);
            }
            for (String proposalId : List.copyOf(PROPOSAL_IDS)) {
                deleteStoreRecord(connection, "ordercare-proposal-binding", proposalId);
            }
        }
        INCIDENT_IDS.clear();
        RECOVERY_PLAN_IDS.clear();
        APPROVAL_IDS.clear();
        PROPOSAL_IDS.clear();
        RECOVERY_EXECUTE_CALLS.set(0);
    }

    @AfterAll
    static void stopStub() {
        if (dependencyStub != null) {
            dependencyStub.stop(0);
        }
    }

    @Test
    void happyConsistentProducesAssessedEvidenceBoard() {
        IncidentInvestigationResult result = investigate(Scenario.HAPPY, ids("E2E-HAPPY-", 3));

        assertEquals(IncidentStatus.ASSESSED, result.incident().status(), () -> diagnostic(result));
        assertEquals(IncidentOutcome.ASSESSED, result.assessment().outcome(), () -> diagnostic(result));
        assertTrue(result.aggregate().evidence().stream().anyMatch(item ->
                item.evidenceSubtype() == EvidenceSubtype.QUEUE_RUNTIME_STATUS));
        assertFalse(result.aggregate().events().stream().anyMatch(item ->
                item.eventType() == TaskEventType.EVIDENCE_CONFLICT_DETECTED));
        var trace = traceProjector.project(result.incident().incidentId()).orElseThrow();
        assertEquals(5, trace.modelMetrics().get("modelRunCount"));
        assertEquals(0, trace.modelMetrics().get("syntheticCoordinatorModelCalls"));
    }

    @Test
    void conflict12610093ProducesExplicitManualReviewConflict() {
        IncidentInvestigationResult result = investigate(
                Scenario.CONFLICT_126_100_93, ids("E2E-CONFLICT-", 100));

        assertEquals(IncidentStatus.MANUAL_REVIEW, result.incident().status(), () -> diagnostic(result));
        assertEquals(IncidentOutcome.MANUAL_REVIEW, result.assessment().outcome(), () -> diagnostic(result));
        assertTrue(result.assessment().conflicts().stream().anyMatch(item ->
                item.conflictType() == EvidenceConflictType.COUNT_MISMATCH), () -> diagnostic(result));
        assertTrue(result.aggregate().evidence().stream()
                .filter(item -> item.evidenceSubtype() == EvidenceSubtype.DEAD_LETTER_SET)
                .anyMatch(item -> Integer.valueOf(126).equals(item.facts().get("recordCount"))
                        && Integer.valueOf(26).equals(item.facts().get("duplicateRecordCount"))));
    }

    @Test
    void mqTimeoutKeepsDeadLetterFactAndRecordsPartialGap() {
        IncidentInvestigationResult result = investigate(
                Scenario.MQ_TIMEOUT_PARTIAL, ids("E2E-MQTIMEOUT-", 3));

        assertTrue(result.aggregate().evidence().stream().anyMatch(item ->
                item.evidenceSubtype() == EvidenceSubtype.DEAD_LETTER_SET), () -> diagnostic(result));
        assertFalse(result.aggregate().evidence().stream().anyMatch(item ->
                item.evidenceSubtype() == EvidenceSubtype.QUEUE_RUNTIME_STATUS), () -> diagnostic(result));
        assertTrue(result.assessment().evidenceGaps().stream().anyMatch(gap ->
                "BROKER_TIMEOUT".equals(gap.code())), () -> diagnostic(result));
    }

    @Test
    void assessedIncidentPlansApprovesExecutesAndConvergesOneProposal() {
        List<String> requestIds = ids("E2E-RECOVERY-", 1);
        IncidentInvestigationResult result = investigate(Scenario.HAPPY, requestIds);
        String deadLetterEvidenceId = result.aggregate().evidence().stream()
                .filter(item -> item.evidenceSubtype() == EvidenceSubtype.DEAD_LETTER_SET)
                .findFirst().orElseThrow().evidenceId();
        PLANNER_REQUEST_ID.set(requestIds.get(0));
        PLANNER_EVIDENCE_ID.set(deadLetterEvidenceId);

        var started = recoveryPlanner.initialize(
                result.incident().incidentId(),
                new RecoveryPlanStartRequest("phase2-e2e-" + UUID.randomUUID(), "create controlled proposal"));
        var planned = recoveryPlanner.plan(started.planId(), "create controlled proposal");
        RECOVERY_PLAN_IDS.add(planned.planId());
        assertFalse(planned.items().isEmpty(), () -> "recovery plan produced no items: status="
                + planned.status() + ", errors=" + planned.validationErrors() + ", draft=" + planned.draft());
        APPROVAL_IDS.add(planned.items().get(0).approvalId());
        PROPOSAL_IDS.add(planned.items().get(0).proposal().proposalId());

        assertEquals(RecoveryPlanStatus.WAITING_APPROVAL, planned.status());
        assertEquals(RecoveryPlanItemStatus.WAITING_APPROVAL, planned.items().get(0).status());

        var completed = recoveryExecutionService.decideAndExecute(
                planned.planId(), planned.items().get(0).itemId(),
                new RecoveryPlanDecisionRequest(true, "phase2-e2e-reviewer", "immutable preview checked"));

        assertEquals(RecoveryPlanStatus.COMPLETED, completed.status());
        assertEquals(RecoveryPlanOutcome.RESOLVED, completed.outcome());
        assertEquals(RecoveryPlanItemStatus.RESOLVED, completed.items().get(0).status());
        assertEquals(1, RECOVERY_EXECUTE_CALLS.get());
        var trace = traceProjector.project(result.incident().incidentId()).orElseThrow();
        assertEquals(6, trace.modelMetrics().get("modelRunCount"));
        assertTrue(trace.childRuns().stream().anyMatch(child -> "RECOVERY_PLANNER".equals(child.runRole())));
    }

    private IncidentInvestigationResult investigate(Scenario scenario, List<String> requestIds) {
        SCENARIO.set(scenario);
        IncidentInvestigationResult result = orchestrator.investigate(new IncidentInvestigationRequest(
                "m1e-it-" + UUID.randomUUID(),
                "MQ_ORDER_INVENTORY_INCIDENT",
                Instant.now(),
                "deterministic incident command runtime E2E",
                requestIds,
                List.of("floworder.order.state.dlq")));
        INCIDENT_IDS.add(result.incident().incidentId());
        return result;
    }

    private static String diagnostic(IncidentInvestigationResult result) {
        return "assessment=" + result.assessment()
                + ", evidence=" + result.aggregate().evidence().stream()
                .map(item -> item.evidenceSubtype() + ":" + item.facts())
                .toList()
                + ", events=" + result.aggregate().events().stream()
                .map(item -> item.eventType() + ":" + item.payload())
                .toList();
    }

    private static synchronized void ensureDependencyStub() {
        if (dependencyStub != null) {
            return;
        }
        try {
            dependencyStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            dependencyStub.createContext("/internal/incidents/facts/orders", exchange -> fact(exchange, "orders"));
            dependencyStub.createContext("/internal/incidents/facts/inventory", exchange -> fact(exchange, "inventory"));
            dependencyStub.createContext("/internal/incidents/facts/dead-letters", exchange -> fact(exchange, "dead-letters"));
            dependencyStub.createContext("/api/queues", IncidentCommandRuntimeE2ETests::rabbit);
            dependencyStub.start();
        }
        catch (IOException exception) {
            throw new IllegalStateException("failed to start incident dependency stub", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void fact(HttpExchange exchange, String factType) throws IOException {
        Map<String, Object> query = JSON.readValue(exchange.getRequestBody(), Map.class);
        List<String> requestIds = JSON.convertValue(query.get("requestIds"), List.class);
        int size = requestIds.size();
        int unreleased = SCENARIO.get() == Scenario.CONFLICT_126_100_93 ? 93 : size;
        Map<String, Object> facts = new LinkedHashMap<>();
        if ("orders".equals(factType)) {
            facts.put("recordCount", size);
            facts.put("distinctRequestIdCount", size);
            facts.put("terminalDistinctRequestIdCount", size);
            facts.put("requestIds", requestIds);
            facts.put("terminalRequestIds", requestIds);
            facts.put("items", List.of());
        }
        else if ("inventory".equals(factType)) {
            facts.put("recordCount", size);
            facts.put("distinctRequestIdCount", size);
            facts.put("unreleasedDistinctRequestIdCount", unreleased);
            facts.put("requestIds", requestIds);
            facts.put("unreleasedRequestIds", requestIds.subList(0, unreleased));
            facts.put("invariantViolationStockItemIds", List.of());
            facts.put("items", List.of());
        }
        else {
            int records = SCENARIO.get() == Scenario.CONFLICT_126_100_93 ? 126 : size;
            int duplicates = records - size;
            facts.put("recordCount", records);
            facts.put("totalMatchingRecordCount", records);
            facts.put("distinctBizKeyCount", size);
            facts.put("distinctRequestIdCount", size);
            facts.put("duplicateRecordCount", duplicates);
            facts.put("unmappedRecordCount", 0);
            facts.put("bizKeys", requestIds.stream().map(id -> "DED-" + id).toList());
            facts.put("requestIds", requestIds);
            facts.put("deadLetterIds", java.util.stream.LongStream.rangeClosed(1, records).boxed().toList());
            facts.put("duplicateGroups", List.of());
            facts.put("items", List.of());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "floworder-incident-facts-v1");
        data.put("sourceSystem", "floworder-e2e-stub");
        data.put("sourceReference", "incident/" + factType + "/" + query.get("incidentId"));
        data.put("scopeHash", query.get("scopeHash"));
        data.put("observedAt", OffsetDateTime.now().toString());
        data.put("truncated", false);
        data.put("missingRequestIds", List.of());
        data.put("facts", facts);
        write(exchange, Map.of("code", 200, "message", "success", "data", data));
    }

    private static void rabbit(HttpExchange exchange) throws IOException {
        if (SCENARIO.get() == Scenario.MQ_TIMEOUT_PARTIAL) {
            try {
                Thread.sleep(500);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        int ready = SCENARIO.get() == Scenario.CONFLICT_126_100_93 ? 126 : 3;
        write(exchange, Map.of(
                "name", "floworder.order.state.dlq",
                "messages_ready", ready,
                "messages_unacknowledged", 0,
                "consumers", 1,
                "state", "running"));
    }

    private static void write(HttpExchange exchange, Object body) throws IOException {
        byte[] json = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, json.length);
        try {
            exchange.getResponseBody().write(json);
        }
        finally {
            exchange.close();
        }
    }

    private void deleteRuntime(Connection connection, String incidentId) throws Exception {
        String sessions = "SELECT run_id FROM agent_run_state WHERE conversation_id LIKE ?";
        delete(connection, "DELETE FROM agent_tool_execution WHERE run_id IN (" + sessions + ")", "incident:" + incidentId + "%");
        delete(connection, "DELETE FROM agent_runtime_event WHERE run_id IN (" + sessions + ")", "incident:" + incidentId + "%");
        delete(connection, "DELETE FROM agent_message WHERE run_id IN (" + sessions + ")", "incident:" + incidentId + "%");
        delete(connection, "DELETE FROM agent_run_control WHERE run_id IN (" + sessions + ")", "incident:" + incidentId + "%");
        delete(connection, "DELETE FROM agent_run_state WHERE conversation_id LIKE ?", "incident:" + incidentId + "%");
        delete(connection, "DELETE FROM agent_session_lease WHERE session_id LIKE ?", "incident:" + incidentId + "%");
        delete(connection, "DELETE FROM agent_session WHERE session_id LIKE ?", "incident:" + incidentId + "%");
    }

    private void delete(Connection connection, String sql, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private void deleteStoreRecord(Connection connection, String category, String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM agent_store_record WHERE category = ? AND record_key = ?")) {
            statement.setString(1, category);
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }

    private static List<String> ids(String prefix, int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> prefix + "%03d".formatted(index))
                .toList();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicModelConfiguration {

        @Bean
        @Primary
        MemoryService incidentCommandNoopMemoryService() {
            return new MemoryService() {
                @Override
                public void rememberLongTerm(String conversationId, String userId,
                                             com.agent.platform.memory.MemoryMessage message) { }

                @Override
                public List<MemorySearchResult> recall(String conversationId, String userId,
                                                       String query, int limit) {
                    return List.of();
                }

                @Override
                public UserProfile loadUserProfile(String userId) {
                    return UserProfile.empty(userId);
                }

                @Override
                public void upsertUserProfile(String userId, String key, String value,
                                              String source, Instant updatedAt) { }

                @Override
                public void clearConversation(String conversationId) { }

                @Override
                public void clearUserMemory(String userId) { }
            };
        }

        @Bean
        @Primary
        AgentModelGateway incidentCommandModelGateway() {
            return request -> {
                LlmUsage usage = new LlmUsage(100, 40, 140, 0, 0, "incident-e2e", "test");
                if (request.systemPrompt().contains("delegation-plan-v1")) {
                    String incidentId = String.valueOf(request.metadata().get("incidentId"));
                    String plan = """
                            {"schemaVersion":"delegation-plan-v1","incidentId":"%s","planSummary":"bounded read-only investigation","tasks":[
                              {"clientTaskKey":"orders","role":"ORDER_ANALYST","objective":"query order facts","priority":100,"dependencies":[],"requiredEvidenceSubtypes":["ORDER_STATUS_SET"]},
                              {"clientTaskKey":"inventory","role":"INVENTORY_ANALYST","objective":"query inventory facts","priority":100,"dependencies":[],"requiredEvidenceSubtypes":["INVENTORY_DEDUCT_SET","INVENTORY_INVARIANT"]},
                              {"clientTaskKey":"mq","role":"MQ_ANALYST","objective":"query persisted dead letters and queue runtime","priority":100,"dependencies":[],"requiredEvidenceSubtypes":["DEAD_LETTER_SET","QUEUE_RUNTIME_STATUS"]}
                            ]}
                            """.formatted(incidentId).trim();
                    return new AgentModelTurn(plan, List.of(), plan, usage, "final_answer");
                }
                if (request.systemPrompt().contains("incident-recovery-plan-v1")) {
                    String plan = """
                            {"schemaVersion":"incident-recovery-plan-v1","summary":"controlled replay","proposalRequests":[
                              {"clientItemKey":"replay-1","identifierType":"REQUEST_ID","identifierValue":"%s",
                               "actionType":"REPLAY","suggestedReason":"persisted dead letter is proven by FACT evidence",
                               "evidenceIds":["%s"],"conflictIds":[]}
                            ]}
                            """.formatted(PLANNER_REQUEST_ID.get(), PLANNER_EVIDENCE_ID.get()).trim();
                    return new AgentModelTurn(plan, List.of(), plan, usage, "final_answer");
                }
                if (!request.tools().isEmpty()) {
                    boolean hasToolResult = request.messages().stream()
                            .anyMatch(message -> message.type() == AgentMessageType.TOOL_RESULT);
                    if (!hasToolResult) {
                        String toolName = request.tools().get(0).name();
                        AgentToolCall call = new AgentToolCall(
                                "e2e-call-" + UUID.randomUUID(), toolName,
                                Map.of("snapshotId", String.valueOf(request.metadata().get("snapshotId"))),
                                "collect immutable incident facts");
                        return new AgentModelTurn("", List.of(call), "tool call", usage, "tool_calls");
                    }
                    return new AgentModelTurn(
                            "{\"schemaVersion\":\"specialist-report-v1\"}", List.of(),
                            "specialist complete", usage, "final_answer");
                }
                String reviewerInput = request.messages().stream()
                        .map(message -> message.content())
                        .collect(Collectors.joining("\n"));
                Matcher deadLetterFact = Pattern.compile(
                                "\\\"evidenceId\\\":\\\"([^\\\"]+)\\\".*?\\\"evidenceSubtype\\\":\\\"DEAD_LETTER_SET\\\"",
                                Pattern.DOTALL)
                        .matcher(reviewerInput);
                String review = deadLetterFact.find()
                        ? """
                          {"schemaVersion":"reviewer-assessment-v1","confirmedFacts":[
                            {"evidenceSubtype":"DEAD_LETTER_SET","statement":"persisted dead letter facts are confirmed","evidenceIds":["%s"]}
                          ],"rootCauseCandidates":[],"recommendations":[],"acknowledgedConflictIds":[]}
                          """.formatted(deadLetterFact.group(1)).trim()
                        : "{\"schemaVersion\":\"reviewer-assessment-v1\",\"confirmedFacts\":[],\"rootCauseCandidates\":[],\"recommendations\":[],\"acknowledgedConflictIds\":[]}";
                return new AgentModelTurn(review, List.of(), review, usage, "final_answer");
            };
        }

        @Bean
        @Primary
        FlowOrderClient incidentRecoveryFlowOrderClient() {
            return new FlowOrderClient() {
                private final Map<String, OrderCareRecoveryProposal> proposals = new java.util.concurrent.ConcurrentHashMap<>();

                @Override
                public OrderCareCaseSnapshot inspectCase(String identifierType, String identifierValue, String traceId) {
                    return convergedCase(identifierType, identifierValue);
                }

                @Override
                public OrderCareRecoveryProposal createProposal(OrderCareProposalCreateCommand command, String traceId) {
                    return proposals.computeIfAbsent(command.proposalId(), ignored -> proposal(
                            command.proposalId(), command.identifierValue(), "NOT_STARTED", "NOT_CONVERGED"));
                }

                @Override
                public OrderCareRecoveryProposal getProposal(String proposalId, String traceId) {
                    return proposals.get(proposalId);
                }

                @Override
                public OrderCareRecoveryProposal executeProposal(OrderCareProposalExecuteCommand command, String traceId) {
                    RECOVERY_EXECUTE_CALLS.incrementAndGet();
                    OrderCareRecoveryProposal current = proposals.get(command.proposalId());
                    OrderCareRecoveryProposal completed = proposal(
                            current.proposalId(), current.identifierValue(), "SUBMITTED", "RESOLVED");
                    proposals.put(command.proposalId(), completed);
                    return completed;
                }

                @Override
                public OrderCareRecoveryAction getAction(String actionRequestId, String traceId) {
                    return null;
                }

                @Override
                public OrderCareRecoveryAction reconcileAction(String actionRequestId,
                                                               OrderCareActionReconcileCommand command,
                                                               String traceId) {
                    return null;
                }

                private OrderCareRecoveryProposal proposal(String proposalId,
                                                           String requestId,
                                                           String actionStatus,
                                                           String caseOutcome) {
                    return new OrderCareRecoveryProposal(
                            "floworder-recovery-proposal-v1", proposalId, 1, "ACTIVE",
                            "action-" + proposalId, actionStatus, caseOutcome, "case-" + requestId,
                            "REQUEST_ID", requestId, "REPLAY", "DEAD_LETTER", "target-" + requestId,
                            "fingerprint", "effects", "warnings", "preview", true,
                            List.of("replay persisted dead letter"), List.of("human approval required"),
                            "controlled recovery", "", "", "", "",
                            Instant.now().plusSeconds(300).toString(), Instant.now().toString(), Instant.now().toString());
                }

                private OrderCareCaseSnapshot convergedCase(String identifierType, String requestId) {
                    return new OrderCareCaseSnapshot(
                            "floworder-recovery-case-v1", "case-" + requestId, identifierType, requestId,
                            requestId, true, "ALREADY_CONVERGED", true, false, Instant.now().toString(),
                            null, null,
                            new OrderCareCaseSnapshot.DeductFact(
                                    true, 1L, "DED-" + requestId, "ORDER-1", 1L, 1, 30,
                                    "RELEASED", "", "", Instant.now().toString()),
                            new OrderCareCaseSnapshot.InventoryFact(
                                    true, 1L, 100, 100, 0, 0, 0, true, 1, Instant.now().toString()),
                            List.of(new OrderCareCaseSnapshot.DeadLetterFact(
                                    1L, "msg-1", "queue", "resource", "STOCK_RELEASE", "DED-" + requestId,
                                    20, "RESOLVED", 1, "timeout", "", Instant.now().toString(),
                                    Instant.now().toString(), Instant.now().toString())),
                            List.of(), List.of(), List.of(), List.of());
                }
            };
        }
    }
}
