package com.agent.platform.procurement;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalDecision;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.llm.ConfiguredLlmCostCalculator;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.multiagent.SubAgentRunner;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementCaseStatus;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.tool.ProcurementRfqApprovalPreparer;
import com.agent.platform.procurement.tool.ProcurementRfqGateway;
import com.agent.platform.procurement.tool.ProcurementRfqToolHandler;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.procurement.tool.SimulatedProcurementRfqGateway;
import com.agent.platform.rag.RagService;
import com.agent.platform.runtime.AgentCapabilityExecutor;
import com.agent.platform.runtime.AgentCapabilityRegistry;
import com.agent.platform.runtime.AgentContextManager;
import com.agent.platform.runtime.AgentContextView;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventDraft;
import com.agent.platform.runtime.AgentEventListener;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentMessage;
import com.agent.platform.runtime.AgentMessageDraft;
import com.agent.platform.runtime.AgentMessageType;
import com.agent.platform.runtime.AgentModelGateway;
import com.agent.platform.runtime.AgentModelRequest;
import com.agent.platform.runtime.AgentModelTurn;
import com.agent.platform.runtime.AgentRunBudgetSnapshot;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentSession;
import com.agent.platform.runtime.AgentToolCall;
import com.agent.platform.runtime.AgentToolRuntimeResult;
import com.agent.platform.runtime.DefaultAgentCapabilityExecutor;
import com.agent.platform.runtime.DefaultAgentCapabilityRegistry;
import com.agent.platform.runtime.DefaultAgentRuntime;
import com.agent.platform.runtime.DefaultAgentToolRuntime;
import com.agent.platform.runtime.ToolExecutionClaim;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.runtime.ToolResultProjector;
import com.agent.platform.runtime.AgentRunControlStore;
import com.agent.platform.runtime.ConservativeTokenEstimator;
import com.agent.platform.skill.SkillRegistry;
import com.agent.platform.tool.JsonSchemaToolParameterValidator;
import com.agent.platform.tool.LocalToolExecutor;
import com.agent.platform.tool.LocalToolRegistry;
import com.agent.platform.tool.TicketStore;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolExecutionContext;
import com.agent.platform.tool.ToolHandler;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolRunRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ProcurementRfqHitlTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Set<String> EXPECTED_RFQ_PREPARED_ARGUMENTS = Set.of(
            "caseId", "caseVersion", "supplierId", "productCategory", "productDescription",
            "quantity", "currency", "requiredDeliveryDays", "hardConstraints",
            "sourceRecommendationToolCallId", "idempotencyKey");
    private static final Set<String> FORBIDDEN_RFQ_PREPARED_ARGUMENTS = Set.of(
            "approvalId", "budget", "preferences", "excludedSuppliers", "unitPrice", "totalPrice",
            "confidence", "evidence", "evidenceRefs", "evaluatedCaseVersion", "selectedSupplierId",
            "tradeoffDimensions", "tenantId", "userId", "runId", "sessionId");

    @Test
    void preparerRebuildsExactAuthoritativeRequestAndDiscardsModelFields() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> modelArguments = new LinkedHashMap<>();
        modelArguments.put("supplierId", "supplier-b");
        modelArguments.put("quantity", 1);
        modelArguments.put("caseVersion", 999);
        modelArguments.put("idempotencyKey", "model-key");
        modelArguments.put("approvalId", "forged-approval");
        modelArguments.put("budget", 1);
        modelArguments.put("preferences", Map.of("deliveryPriority", "LOW"));
        modelArguments.put("excludedSuppliers", List.of("supplier-d"));
        modelArguments.put("unitPrice", 1);
        modelArguments.put("totalPrice", 1);
        modelArguments.put("confidence", 0.01);
        modelArguments.put("tenantId", "forged-tenant");
        modelArguments.put("userId", "forged-user");
        ToolCallRequest modelRequest = new ToolCallRequest(ProcurementToolCatalog.CREATE_RFQ, "model-rfq", modelArguments);

        ToolCallRequest prepared = fixture.preparer.prepare("approval-123", modelRequest, policyContext());

        assertCanonicalPreparedRequest(prepared);
        assertEquals("supplier-d", prepared.arguments().get("supplierId"));
        assertEquals(50, prepared.arguments().get("quantity"));
        assertEquals(1L, prepared.arguments().get("caseVersion"));
        assertEquals("rfq:approval-123", prepared.arguments().get("idempotencyKey"));
        assertEquals("finalize-1", prepared.arguments().get("sourceRecommendationToolCallId"));
        assertEquals("{}", new ObjectMapper().writeValueAsString(Map.of()));
    }

    @Test
    void realDefaultToolRuntimeDoesNotCreateApprovalBeforeRfqPreparationSucceeds() {
        RuntimeFixture fixture = runtimeFixture();
        fixture.caseStore.put(caseValue(1));

        assertThrows(IllegalArgumentException.class, () -> fixture.toolRuntime.execute(
                "run-prepare-failure", "conversation", "buyer",
                Map.of("tenantId", "tenant", "authenticatedRoles", Set.of("USER")),
                new AgentToolCall("rfq-prepare-failure", ProcurementToolCatalog.CREATE_RFQ, Map.of(), "发起 RFQ"),
                rfqDefinition()));

        assertTrue(fixture.approvalService.recent(10).isEmpty());
        assertTrue(fixture.approvalService.lastApproval == null);
        verify(fixture.approvalService, never()).requestApproval(any());
        assertTrue(fixture.executions.findToolExecution("rfq-prepare-failure").isEmpty());
        assertTrue(fixture.executions.findByRun("run-prepare-failure").stream()
                .noneMatch(record -> ProcurementToolCatalog.CREATE_RFQ.equals(record.toolName())));
        assertTrue(fixture.executions.findByRun("run-prepare-failure").stream()
                .noneMatch(record -> ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(record.toolName())
                        && record.state() == ToolExecutionState.SUCCEEDED));
        assertEquals(0, fixture.gateway.createCount.get());
    }

    @Test
    void preparerFailsClosedWithoutCurrentRunFinalizeOrWhenFinalizeIsStale() {
        Fixture missing = fixture();
        missing.executions.records.clear();
        assertThrows(IllegalArgumentException.class, () -> missing.preparer.prepare(
                "approval-1", intentRequest(), policyContext()));

        Fixture stale = fixture();
        stale.caseStore.put(caseValue(2));
        assertThrows(IllegalArgumentException.class, () -> stale.preparer.prepare(
                "approval-2", intentRequest(), policyContext()));
    }

    @Test
    void simulatedGatewayIsIdempotentByApprovalBoundKey() {
        SimulatedProcurementRfqGateway gateway = new SimulatedProcurementRfqGateway();
        ProcurementRfqGateway.CreateRequest request = gatewayRequest("rfq:approval-1");

        ProcurementRfqGateway.Receipt first = gateway.create(request);
        ProcurementRfqGateway.Receipt second = gateway.create(request);

        assertEquals(first, second);
        assertEquals(Optional.of(first), gateway.findByIdempotencyKey("rfq:approval-1"));
    }

    @Test
    void handlerRejectsStalePreparedRequestWithoutGatewayCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-stale", intentRequest(), policyContext());
        fixture.caseStore.put(caseValue(2));

        ToolCallResult result = fixture.handler.execute(prepared, executionContext());

        assertFalse(result.success());
        assertEquals(0, fixture.gateway.createCount.get());
        assertEquals(false, result.metadata().get("retryable"));
    }

    @Test
    void handlerRejectsPreparedRequestWithMissingCanonicalFieldWithoutGatewayCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-missing-field", intentRequest(), policyContext());
        Map<String, Object> arguments = new LinkedHashMap<>(prepared.arguments());
        arguments.remove("currency");

        ToolCallResult result = fixture.handler.execute(
                new ToolCallRequest(prepared.toolName(), prepared.requestId(), arguments), executionContext());

        assertFalse(result.success());
        assertEquals(0, fixture.gateway.createCount.get());
        assertEquals("RFQ_REQUEST_REJECTED", result.metadata().get("errorType"));
    }

    @Test
    void handlerRejectsPreparedRequestWithForgedApprovalIdWithoutGatewayCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-extra-field", intentRequest(), policyContext());
        Map<String, Object> arguments = new LinkedHashMap<>(prepared.arguments());
        arguments.put("approvalId", "forged");

        ToolCallResult result = fixture.handler.execute(
                new ToolCallRequest(prepared.toolName(), prepared.requestId(), arguments), executionContext());

        assertFalse(result.success());
        assertEquals(0, fixture.gateway.createCount.get());
        assertEquals("RFQ_REQUEST_REJECTED", result.metadata().get("errorType"));
    }

    @Test
    void handlerRejectsMalformedIdempotencyKeyWithoutGatewayCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-malformed-key", intentRequest(), policyContext());
        Map<String, Object> arguments = new LinkedHashMap<>(prepared.arguments());
        arguments.put("idempotencyKey", "approval-malformed-key");

        ToolCallResult result = fixture.handler.execute(
                new ToolCallRequest(prepared.toolName(), prepared.requestId(), arguments), executionContext());

        assertFalse(result.success());
        assertEquals(0, fixture.gateway.createCount.get());
        assertEquals("RFQ_REQUEST_REJECTED", result.metadata().get("errorType"));
    }

    @Test
    void handlerRejectsUnknownFinalizeSourceWithoutGatewayCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-unknown-finalize", intentRequest(), policyContext());
        Map<String, Object> arguments = new LinkedHashMap<>(prepared.arguments());
        arguments.put("sourceRecommendationToolCallId", "missing-finalize");

        ToolCallResult result = fixture.handler.execute(
                new ToolCallRequest(prepared.toolName(), prepared.requestId(), arguments), executionContext());

        assertFalse(result.success());
        assertEquals(0, fixture.gateway.createCount.get());
    }

    @Test
    void handlerRejectsFinalizeSourceWithWrongToolNameWithoutGatewayCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-wrong-finalize-tool", intentRequest(), policyContext());
        replaceFinalizeExecution(fixture, ProcurementToolCatalog.SUPPLIER_SEARCH, ToolExecutionState.SUCCEEDED, true);

        ToolCallResult result = fixture.handler.execute(prepared, executionContext());

        assertFalse(result.success());
        assertEquals("RFQ_REQUEST_REJECTED", result.metadata().get("errorType"));
        assertEquals(0, fixture.gateway.createCount.get());
        assertEquals(0, fixture.gateway.findCount.get());
    }

    @Test
    void handlerRejectsFailedFinalizeSourceWithoutGatewayCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-failed-finalize", intentRequest(), policyContext());
        replaceFinalizeExecution(fixture, ProcurementToolCatalog.RECOMMENDATION_FINALIZE, ToolExecutionState.FAILED, false);

        ToolCallResult result = fixture.handler.execute(prepared, executionContext());

        assertFalse(result.success());
        assertEquals("RFQ_REQUEST_REJECTED", result.metadata().get("errorType"));
        assertEquals(0, fixture.gateway.createCount.get());
        assertEquals(0, fixture.gateway.findCount.get());
    }

    @Test
    void handlerRejectsSupplierNotGroundedInCurrentFinalizeWithoutGatewayCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-wrong-supplier", intentRequest(), policyContext());
        Map<String, Object> arguments = new LinkedHashMap<>(prepared.arguments());
        arguments.put("supplierId", "supplier-b");

        ToolCallResult result = fixture.handler.execute(
                new ToolCallRequest(prepared.toolName(), prepared.requestId(), arguments), executionContext());

        assertFalse(result.success());
        assertEquals(0, fixture.gateway.createCount.get());
    }

    @Test
    void commitThenThrowIsReconciledWithoutSecondCreate() {
        Fixture fixture = fixture();
        fixture.gateway.commitThenThrow = true;
        ToolCallRequest prepared = fixture.preparer.prepare("approval-commit", intentRequest(), policyContext());

        ToolCallResult result = fixture.handler.execute(prepared, executionContext());

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, fixture.gateway.createCount.get());
        assertEquals(1, fixture.gateway.findCount.get());
        assertEquals(true, result.metadata().get("reconciled"));
        assertEquals("rfq:approval-commit", mapper.readTree(result.content()).path("idempotencyKey").asText());
    }

    @Test
    void unresolvedExternalStateRequiresManualReviewAndIsNotRetryable() {
        Fixture fixture = fixture();
        fixture.gateway.throwOnCreate = true;
        ToolCallRequest prepared = fixture.preparer.prepare("approval-unknown", intentRequest(), policyContext());

        ToolCallResult result = fixture.handler.execute(prepared, executionContext());

        assertFalse(result.success());
        assertEquals(1, fixture.gateway.createCount.get());
        assertEquals(1, fixture.gateway.findCount.get());
        assertUnresolvedMetadata(result);
    }

    @Test
    void mismatchedCreateReceiptIsReconciledWithoutSecondCreate() throws Exception {
        Fixture fixture = fixture();
        fixture.gateway.returnMismatchedReceipt = true;
        ToolCallRequest prepared = fixture.preparer.prepare("approval-mismatched-receipt", intentRequest(), policyContext());

        ToolCallResult result = fixture.handler.execute(prepared, executionContext());

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, fixture.gateway.createCount.get());
        assertEquals(1, fixture.gateway.findCount.get());
        assertEquals(true, result.metadata().get("reconciled"));
        assertEquals("rfq:approval-mismatched-receipt",
                mapper.readTree(result.content()).path("idempotencyKey").asText());
    }

    @Test
    void postCreateResultMaterializationFailureIsManualReviewWithoutSecondCreate() {
        Fixture fixture = fixture();
        ObjectMapper failingMapper = spy(mapper);
        doThrow(new IllegalStateException("result serialization failed"))
                .when(failingMapper).writeValueAsString(any());
        ProcurementRfqToolHandler handler = new ProcurementRfqToolHandler(
                fixture.gateway, fixture.caseStore, fixture.executions, failingMapper);
        ToolCallRequest prepared = fixture.preparer.prepare("approval-serialization", intentRequest(), policyContext());

        ToolCallResult result = handler.execute(prepared, executionContext());

        assertFalse(result.success());
        assertEquals(1, fixture.gateway.createCount.get());
        assertEquals(1, fixture.gateway.findCount.get());
        assertUnresolvedMetadata(result);
    }

    @Test
    void resolverOnlyLooksUpStoredRequestAndNeverCreates() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-recovery", intentRequest(), policyContext());
        fixture.gateway.seed(new ProcurementRfqGateway.Receipt(
                "rfq-existing", "rfq:approval-recovery", "supplier-d", "CREATED", Instant.now(), "test"));
        ToolExecutionRecord running = ToolExecutionRecord.running("run-1", prepared);

        ToolCallResult result = fixture.handler.resolve(running);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, fixture.gateway.createCount.get());
        assertEquals(1, fixture.gateway.findCount.get());
        assertEquals(true, result.metadata().get("reconciled"));
    }

    @Test
    void resolverMissingReceiptReturnsManualReviewWithoutCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-missing", intentRequest(), policyContext());

        ToolCallResult result = fixture.handler.resolve(ToolExecutionRecord.running("run-1", prepared));

        assertFalse(result.success());
        assertEquals(0, fixture.gateway.createCount.get());
        assertUnresolvedMetadata(result);
    }

    @Test
    void resolverMalformedStoredRequestRequiresManualReviewWithoutLookupOrCreate() {
        Fixture fixture = fixture();
        ToolCallRequest prepared = fixture.preparer.prepare("approval-malformed-recovery", intentRequest(), policyContext());
        Map<String, Object> arguments = new LinkedHashMap<>(prepared.arguments());
        arguments.remove("idempotencyKey");

        ToolCallResult result = fixture.handler.resolve(ToolExecutionRecord.running("run-1",
                new ToolCallRequest(prepared.toolName(), prepared.requestId(), arguments)));

        assertFalse(result.success());
        assertEquals(0, fixture.gateway.createCount.get());
        assertEquals(0, fixture.gateway.findCount.get());
        assertUnresolvedMetadata(result);
    }

    @Test
    void realDefaultRuntimeWaitsForApprovalThenResumesSameRunAndDoesNotRetryCommittedRfq() throws Exception {
        RuntimeFixture fixture = runtimeFixture();
        AgentRuntimeResult waiting = fixture.runtime.run(new AgentRequest(
                "conversation", "buyer", "完成推荐后，帮我发起 RFQ。",
                Map.of("tenantId", "tenant", "authenticatedRoles", Set.of("USER")),
                fixture.profile.name()), fixture.profile, AgentEventListener.NOOP);

        assertEquals(AgentRunState.WAITING_APPROVAL, waiting.state(), waiting.answer() + " / " + fixture.executions.records);
        assertFalse(fixture.gateway.createCount.get() > 0);
        ApprovalRecord approval = fixture.approvalService.lastApproval;
        assertTrue(approval != null);
        assertEquals(ProcurementToolCatalog.CREATE_RFQ, approval.toolCallRequest().toolName());
        assertCanonicalPreparedRequest(approval.toolCallRequest());
        assertEquals("supplier-d", approval.toolCallRequest().arguments().get("supplierId"));
        assertEquals(50, approval.toolCallRequest().arguments().get("quantity"));
        assertEquals("rfq:" + approval.approvalId(), approval.toolCallRequest().arguments().get("idempotencyKey"));
        assertEquals(AgentRunState.WAITING_APPROVAL,
                fixture.runStore.values.get(waiting.runId()).state());

        fixture.gateway.commitThenThrow = true;
        fixture.approvalService.decide(approval.approvalId(), true, "reviewer", "approved");
        AgentRuntimeResult completed = fixture.runtime.resume(waiting.runId(), AgentEventListener.NOOP);

        assertEquals(AgentRunState.COMPLETED, completed.state(), completed.answer());
        assertEquals(waiting.runId(), completed.runId());
        assertEquals(1, fixture.gateway.createCount.get());
        assertEquals(1, fixture.gateway.findCount.get());
        ToolExecutionRecord rfqExecution = fixture.executions.records.values().stream()
                .filter(record -> ProcurementToolCatalog.CREATE_RFQ.equals(record.toolName()))
                .findFirst().orElseThrow();
        assertEquals(approval.toolCallRequest(), rfqExecution.request());
        assertEquals(1, rfqExecution.result().metadata().get("attempts"));
        assertTrue(completed.answer().contains("RFQ"));
        AgentRuntimeResult repeated = fixture.runtime.resume(waiting.runId(), AgentEventListener.NOOP);
        assertEquals(AgentRunState.COMPLETED, repeated.state());
        assertEquals(1, fixture.gateway.createCount.get());
    }

    @Test
    void uncertainRfqIsNotRetriedWhenRuntimeAllowsMultipleAttempts() {
        RuntimeFixture fixture = runtimeFixture();
        AgentRuntimeResult waiting = fixture.runtime.run(new AgentRequest(
                "conversation", "buyer", "完成推荐后，帮我发起 RFQ。",
                Map.of("tenantId", "tenant", "authenticatedRoles", Set.of("USER")),
                fixture.profile.name()), fixture.profile, AgentEventListener.NOOP);

        assertEquals(AgentRunState.WAITING_APPROVAL, waiting.state());
        ApprovalRecord approval = fixture.approvalService.lastApproval;
        assertTrue(approval != null);
        fixture.gateway.throwOnCreate = true;
        fixture.approvalService.decide(approval.approvalId(), true, "reviewer", "approved");

        AgentRuntimeResult reviewed = fixture.runtime.resume(waiting.runId(), AgentEventListener.NOOP);

        assertEquals(AgentRunState.MANUAL_REVIEW, reviewed.state(), reviewed.answer());
        assertEquals(1, fixture.gateway.createCount.get());
        assertEquals(1, fixture.gateway.findCount.get());
        ToolExecutionRecord rfqExecution = fixture.executions.records.values().stream()
                .filter(record -> ProcurementToolCatalog.CREATE_RFQ.equals(record.toolName()))
                .findFirst().orElseThrow();
        assertEquals(approval.toolCallRequest().requestId(), rfqExecution.toolCallId());
        assertEquals(ToolExecutionState.MANUAL_REVIEW, rfqExecution.state());
        assertEquals(true, rfqExecution.result().metadata().get("manualReview"));
    }

    @Test
    void deniedApprovalDoesNotInvokeGateway() {
        RuntimeFixture fixture = runtimeFixture();
        AgentRuntimeResult waiting = fixture.runtime.run(new AgentRequest(
                "conversation", "buyer", "完成推荐后，帮我发起 RFQ。",
                Map.of("tenantId", "tenant", "authenticatedRoles", Set.of("USER")),
                fixture.profile.name()), fixture.profile, AgentEventListener.NOOP);
        assertEquals(AgentRunState.WAITING_APPROVAL, waiting.state());

        fixture.approvalService.decide(fixture.approvalService.lastApproval.approvalId(), false,
                "reviewer", "not approved");
        AgentRuntimeResult rejected = fixture.runtime.resume(waiting.runId(), AgentEventListener.NOOP);

        assertEquals(AgentRunState.COMPLETED, rejected.state());
        assertEquals(0, fixture.gateway.createCount.get());
    }

    private void assertCanonicalPreparedRequest(ToolCallRequest request) {
        assertEquals(11, request.arguments().size());
        assertEquals(EXPECTED_RFQ_PREPARED_ARGUMENTS, request.arguments().keySet());
        FORBIDDEN_RFQ_PREPARED_ARGUMENTS.forEach(key -> assertFalse(request.arguments().containsKey(key), key));
    }

    private void assertUnresolvedMetadata(ToolCallResult result) {
        assertEquals(true, result.metadata().get("manualReview"));
        assertEquals(false, result.metadata().get("retryable"));
        assertEquals(true, result.metadata().get("uncertainExternalState"));
    }

    private void replaceFinalizeExecution(Fixture fixture,
                                          String toolName,
                                          ToolExecutionState state,
                                          boolean success) {
        ToolCallRequest request = new ToolCallRequest(toolName, "finalize-1", Map.of());
        ToolCallResult result = new ToolCallResult(toolName, success,
                success ? finalizeContent("case-1", 1, "supplier-d") : "",
                success ? "" : "finalize failed",
                Map.of("provider", "procurement", "readOnly", true, "sideEffect", false));
        fixture.executions.records.put("finalize-1",
                ToolExecutionRecord.running("run-1", request).withResult(state, result, result.errorMessage()));
    }

    private ToolDefinition rfqDefinition() {
        return new ProcurementToolCatalog().definitions().stream()
                .filter(definition -> ProcurementToolCatalog.CREATE_RFQ.equals(definition.name()))
                .findFirst()
                .orElseThrow();
    }

    private Fixture fixture() {
        MemoryCaseStore caseStore = new MemoryCaseStore();
        caseStore.put(caseValue(1));
        InMemoryToolExecutionStore executions = new InMemoryToolExecutionStore();
        executions.add(finalizeExecution());
        CountingGateway gateway = new CountingGateway();
        ProcurementRfqApprovalPreparer preparer = new ProcurementRfqApprovalPreparer(
                executions, caseStore, mapper);
        ProcurementRfqToolHandler handler = new ProcurementRfqToolHandler(
                gateway, caseStore, executions, mapper);
        return new Fixture(caseStore, executions, gateway, preparer, handler);
    }

    private RuntimeFixture runtimeFixture() {
        MemoryCaseStore caseStore = new MemoryCaseStore();
        InMemoryToolExecutionStore executions = new InMemoryToolExecutionStore();
        CountingGateway gateway = new CountingGateway();
        com.agent.platform.procurement.application.ProcurementCasePatchMerger merger =
                new com.agent.platform.procurement.application.ProcurementCasePatchMerger();
        com.agent.platform.procurement.application.ProcurementDecisionEngine decisionEngine =
                new com.agent.platform.procurement.application.ProcurementDecisionEngine();
        com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider provider =
                new com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider(
                        mapper, new com.agent.platform.procurement.config.ProcurementDataProperties());
        com.agent.platform.procurement.application.ProcurementCaseService caseService =
                new com.agent.platform.procurement.application.ProcurementCaseService(caseStore, merger);
        com.agent.platform.procurement.tool.ProcurementToolHandler procurementHandler =
                new com.agent.platform.procurement.tool.ProcurementToolHandler(provider, mapper, caseStore,
                        caseService, new com.agent.platform.procurement.application.ProcurementRecommendationFinalizer(
                                caseStore, provider, decisionEngine), merger, decisionEngine);
        ApprovalMemoryService approvals = spy(new ApprovalMemoryService());
        ProcurementRfqToolHandler rfqHandler = new ProcurementRfqToolHandler(
                gateway, caseStore, executions, mapper);
        ObjectProvider<com.agent.platform.mcp.McpToolGateway> mcp = mock(ObjectProvider.class);
        when(mcp.getIfAvailable()).thenReturn(null);
        ToolRegistry registry = new LocalToolRegistry(mcp, contributorProvider(new ProcurementToolCatalog()));
        AgentCapabilityRegistry capabilities = new DefaultAgentCapabilityRegistry(registry);
        ObjectProvider<ToolHandler> handlers = mock(ObjectProvider.class);
        when(handlers.orderedStream()).thenAnswer(invocation -> Stream.of(procurementHandler, rfqHandler));
        LocalToolExecutor localExecutor = new LocalToolExecutor(registry,
                new JsonSchemaToolParameterValidator(mapper), mock(ToolRunRecorder.class), mock(TicketStore.class), mcp,
                handlers);
        AgentCapabilityExecutor capabilityExecutor = new DefaultAgentCapabilityExecutor(
                mock(RagService.class), localExecutor, mock(SkillRegistry.class));
        AgentProperties properties = new AgentProperties();
        properties.setMaxToolExecutionAttempts(3);
        properties.setToolRetryBackoffMillis(0);
        DefaultAgentToolRuntime toolRuntime = new DefaultAgentToolRuntime(
                approvalGuardrail(), approvals, executions, capabilityExecutor, properties,
                List.of(new ProcurementRfqApprovalPreparer(executions, caseStore, mapper)), List.of(rfqHandler));
        InMemoryRunStore runs = new InMemoryRunStore();
        MinimalTimeline timeline = new MinimalTimeline();
        AgentContextManager contexts = new TimelineContextManager(timeline);
        AgentExecutionProfile profile = new ProcurementSourcingExecutionProfileFactory().createProfile();
        List<String> evidenceRefs = provider.getSupplierEvidence("supplier-b", caseValue(1).state()).stream()
                .filter(value -> "OFFER".equals(value.evidenceType()))
                .map(com.agent.platform.procurement.model.SupplierEvidence::evidenceId)
                .toList();
        evidenceRefs = new ArrayList<>(evidenceRefs);
        evidenceRefs.addAll(provider.getSupplierEvidence("supplier-d", caseValue(1).state()).stream()
                .filter(value -> "OFFER".equals(value.evidenceType()))
                .map(com.agent.platform.procurement.model.SupplierEvidence::evidenceId)
                .toList());
        ScriptedRfqModel model = new ScriptedRfqModel(mapper, evidenceRefs);
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(properties, timeline, runs, executions, contexts,
                model, capabilities, toolRuntime, approvalGuardrail(), List.of(), approvals,
                new ConservativeTokenEstimator(), new NoopRunControlStore(), mock(MemoryService.class),
                new ConfiguredLlmCostCalculator(properties), new ToolResultProjector(properties));
        return new RuntimeFixture(runtime, toolRuntime, profile, gateway, approvals, runs, model, executions, caseStore);
    }

    private GuardrailService approvalGuardrail() {
        return new GuardrailService() {
            @Override public GuardrailDecision checkInput(String question) {
                return GuardrailDecision.allow(GuardrailStage.INPUT, "test");
            }
            @Override public GuardrailDecision checkToolCall(ToolDefinition definition, ToolCallRequest request) {
                return ProcurementToolCatalog.CREATE_RFQ.equals(definition.name())
                        ? GuardrailDecision.requireApproval(GuardrailStage.TOOL, "RFQ requires approval")
                        : GuardrailDecision.allow(GuardrailStage.TOOL, "test");
            }
            @Override public GuardrailDecision checkOutput(String answer) {
                return GuardrailDecision.allow(GuardrailStage.OUTPUT, "test");
            }
        };
    }

    private ToolCallRequest intentRequest() {
        return new ToolCallRequest(ProcurementToolCatalog.CREATE_RFQ, "model-rfq", Map.of());
    }

    private ToolPolicyContext policyContext() {
        return new ToolPolicyContext("run-1", "conversation", "buyer", "tenant", Set.of("USER"), Map.of());
    }

    private ToolExecutionContext executionContext() {
        return new ToolExecutionContext("run-1", "conversation", "buyer", "tenant", Set.of("USER"), Map.of());
    }

    private ToolExecutionRecord finalizeExecution() {
        ToolCallRequest request = new ToolCallRequest(ProcurementToolCatalog.RECOMMENDATION_FINALIZE,
                "finalize-1", Map.of());
        ToolCallResult result = new ToolCallResult(request.toolName(), true, finalizeContent("case-1", 1, "supplier-d"), "",
                Map.of("provider", "procurement", "readOnly", true, "sideEffect", false));
        return ToolExecutionRecord.running("run-1", request).withResult(ToolExecutionState.SUCCEEDED, result, "");
    }

    private String finalizeContent(String caseId, long version, String supplierId) {
        return mapper.writeValueAsString(Map.of(
                "caseId", caseId,
                "caseVersion", version,
                "recommendation", Map.of(
                        "recommendedSupplier", Map.of("supplierId", supplierId),
                        "selectedOffer", Map.of("supplierId", supplierId)),
                "source", "verified-provider-snapshot"));
    }

    private ProcurementCase caseValue(long version) {
        Instant now = Instant.now();
        return new ProcurementCase("case-1", "tenant", "conversation", "buyer", ProcurementCaseStatus.SOURCING,
                new ProcurementCaseState("计算工作站", "CUDA 开发工作站", 50, new java.math.BigDecimal("600000"),
                        "CNY", 21, Map.of("gpuMemoryMinGb", "24"), Map.of("deliveryPriority", "HIGH"),
                        Set.of("Supplier A"), List.of(), "SOURCING"), now, now, version, "patch-1");
    }

    private ProcurementRfqGateway.CreateRequest gatewayRequest(String key) {
        return new ProcurementRfqGateway.CreateRequest(key, "supplier-d", "计算工作站", "CUDA 开发工作站",
                50, "CNY", 21, Map.of("gpuMemoryMinGb", "24"));
    }

    private ObjectProvider<ToolCatalogContributor> contributorProvider(ToolCatalogContributor contributor) {
        return new ObjectProvider<>() {
            @Override public Stream<ToolCatalogContributor> orderedStream() { return Stream.of(contributor); }
        };
    }

    private record Fixture(MemoryCaseStore caseStore, InMemoryToolExecutionStore executions,
                           CountingGateway gateway,
                           ProcurementRfqApprovalPreparer preparer,
                           ProcurementRfqToolHandler handler) { }

    private record RuntimeFixture(DefaultAgentRuntime runtime, DefaultAgentToolRuntime toolRuntime,
                                  AgentExecutionProfile profile,
                                  CountingGateway gateway, ApprovalMemoryService approvalService,
                                  InMemoryRunStore runStore, ScriptedRfqModel model,
                                  InMemoryToolExecutionStore executions, MemoryCaseStore caseStore) { }

    private static final class CountingGateway implements ProcurementRfqGateway {
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger findCount = new AtomicInteger();
        private final Map<String, Receipt> receipts = new ConcurrentHashMap<>();
        private volatile boolean commitThenThrow;
        private volatile boolean throwOnCreate;
        private volatile boolean returnMismatchedReceipt;

        @Override public Receipt create(CreateRequest request) {
            createCount.incrementAndGet();
            if (throwOnCreate) throw new IllegalStateException("timeout before external commit");
            Receipt receipt = receipts.computeIfAbsent(request.idempotencyKey(), key -> new Receipt(
                    "rfq-test", key, request.supplierId(), "CREATED", Instant.now(), "test"));
            if (commitThenThrow) throw new IllegalStateException("timeout after create");
            if (returnMismatchedReceipt) {
                return new Receipt("rfq-wrong", receipt.idempotencyKey(), "supplier-other", "CREATED",
                        receipt.createdAt(), receipt.source());
            }
            return receipt;
        }

        @Override public Optional<Receipt> findByIdempotencyKey(String idempotencyKey) {
            findCount.incrementAndGet();
            return Optional.ofNullable(receipts.get(idempotencyKey));
        }

        private void seed(Receipt receipt) { receipts.put(receipt.idempotencyKey(), receipt); }
    }

    private static final class MemoryCaseStore implements ProcurementCaseStore {
        private final Map<String, ProcurementCase> values = new ConcurrentHashMap<>();
        @Override public Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId, String userId,
                                                                                       String conversationId) {
            return Optional.ofNullable(values.get(key(tenantId, userId, conversationId)));
        }
        @Override public boolean createIfAbsent(ProcurementCase value) {
            return values.putIfAbsent(key(value.tenantId(), value.userId(), value.conversationId()), value) == null;
        }
        @Override public boolean saveIfVersion(ProcurementCase value, long expectedVersion) {
            synchronized (values) {
                String key = key(value.tenantId(), value.userId(), value.conversationId());
                ProcurementCase current = values.get(key);
                if (current == null || current.version() != expectedVersion) return false;
                values.put(key, value);
                return true;
            }
        }
        private void put(ProcurementCase value) { values.put(key(value.tenantId(), value.userId(), value.conversationId()), value); }
        private String key(String tenantId, String userId, String conversationId) { return tenantId + "|" + userId + "|" + conversationId; }
    }

    private static final class InMemoryToolExecutionStore implements ToolExecutionStore {
        private final Map<String, ToolExecutionRecord> records = new ConcurrentHashMap<>();
        private void add(ToolExecutionRecord record) { records.put(record.toolCallId(), record); }
        @Override public ToolExecutionClaim claim(String runId, ToolCallRequest request) {
            ToolExecutionRecord existing = records.putIfAbsent(request.requestId(), ToolExecutionRecord.running(runId, request));
            return existing == null ? ToolExecutionClaim.acquired() : ToolExecutionClaim.existing(existing, "already exists");
        }
        @Override public void markSucceeded(String toolCallId, ToolCallResult result) {
            records.computeIfPresent(toolCallId, (key, value) -> value.withResult(ToolExecutionState.SUCCEEDED, result, ""));
        }
        @Override public void markFailed(String toolCallId, ToolCallResult result) {
            records.computeIfPresent(toolCallId, (key, value) -> value.withResult(ToolExecutionState.FAILED, result, result.errorMessage()));
        }
        @Override public void markManualReview(String toolCallId, String reason) {
            records.computeIfPresent(toolCallId, (key, value) -> value.withResult(ToolExecutionState.MANUAL_REVIEW,
                    new ToolCallResult(value.toolName(), false, "", reason, Map.of("manualReview", true)), reason));
        }
        @Override public Optional<ToolExecutionRecord> findToolExecution(String toolCallId) { return Optional.ofNullable(records.get(toolCallId)); }
        @Override public List<ToolExecutionRecord> findByRun(String runId) { return records.values().stream().filter(value -> runId.equals(value.runId())).toList(); }
    }

    private static final class ApprovalMemoryService implements ApprovalService {
        private final Map<String, ApprovalRecord> values = new ConcurrentHashMap<>();
        private volatile ApprovalRecord lastApproval;
        @Override public ApprovalDecision requestApproval(ApprovalRequest request) {
            ApprovalRecord record = new ApprovalRecord(request.approvalId(), request.runId(), request.conversationId(),
                    request.toolCallRequest(), request.reason(), ApprovalStatus.REQUESTED, "", "", request.createdAt(),
                    request.createdAt().plusSeconds(86_400), null);
            values.put(record.approvalId(), record);
            lastApproval = record;
            return decision(record);
        }
        @Override public ApprovalDecision decide(String approvalId, boolean approved, String reviewer, String reason) {
            ApprovalRecord current = find(approvalId).orElseThrow();
            ApprovalRecord next = new ApprovalRecord(current.approvalId(), current.runId(), current.conversationId(),
                    current.toolCallRequest(), current.reason(), approved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED,
                    reviewer, reason, current.createdAt(), current.expiresAt(), Instant.now());
            values.put(approvalId, next);
            return decision(next);
        }
        @Override public Optional<ApprovalRecord> find(String approvalId) { return Optional.ofNullable(values.get(approvalId)); }
        @Override public List<ApprovalRecord> recent(int limit) { return values.values().stream().limit(limit).toList(); }
        private ApprovalDecision decision(ApprovalRecord value) { return new ApprovalDecision(value.approvalId(), value.status(), value.reviewer(), value.decisionReason(), value.decidedAt()); }
    }

    private static final class InMemoryRunStore implements com.agent.platform.runtime.AgentRunStore {
        private final Map<String, AgentRunRecord> values = new ConcurrentHashMap<>();
        @Override public AgentRunRecord create(AgentRunRecord record) { values.put(record.runId(), record); return record; }
        @Override public Optional<AgentRunRecord> find(String runId) { return Optional.ofNullable(values.get(runId)); }
        @Override public List<AgentRunRecord> recent(int limit) { return values.values().stream().limit(limit).toList(); }
        @Override public AgentRunRecord update(String runId, java.util.function.UnaryOperator<AgentRunRecord> updater) { return values.compute(runId, (key, value) -> updater.apply(value)); }
        @Override public Optional<AgentRunRecord> claimForResume(String runId) {
            final AgentRunRecord[] claimed = new AgentRunRecord[1];
            values.computeIfPresent(runId, (key, current) -> {
                if (current.state() != AgentRunState.WAITING_APPROVAL) return current;
                claimed[0] = current.claimedForResume();
                return claimed[0];
            });
            return Optional.ofNullable(claimed[0]);
        }
        @Override public Optional<AgentRunRecord> claimPausedForResume(String runId) { return Optional.empty(); }
    }

    private static final class MinimalTimeline implements com.agent.platform.runtime.AgentTimelineStore {
        private final List<AgentMessage> messages = Collections.synchronizedList(new ArrayList<>());
        private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        private long messageSequence;
        private long eventSequence;
        @Override public AgentSession openSession(String sessionId, String userId) { return new AgentSession(sessionId, userId, messageSequence + 1, eventSequence + 1, 0, Instant.now(), Instant.now()); }
        @Override public Optional<AgentSession> findSession(String sessionId) { return Optional.of(new AgentSession(sessionId, "buyer", messageSequence + 1, eventSequence + 1, 0, Instant.now(), Instant.now())); }
        @Override public synchronized List<AgentMessage> appendMessages(String sessionId, String userId, String runId, List<AgentMessageDraft> drafts) {
            List<AgentMessage> result = new ArrayList<>();
            for (AgentMessageDraft draft : drafts) {
                AgentMessage value = new AgentMessage(UUID.randomUUID().toString(), sessionId, runId, ++messageSequence,
                        draft.type(), draft.content(), draft.toolCallId(), draft.toolName(), draft.arguments(), draft.metadata(), draft.estimatedTokens(), Instant.now());
                messages.add(value); result.add(value);
            }
            return List.copyOf(result);
        }
        @Override public List<AgentMessage> loadMessages(String sessionId, int limit) { synchronized (messages) { return messages.stream().filter(value -> sessionId.equals(value.sessionId())).sorted(Comparator.comparingLong(AgentMessage::sequence)).limit(limit).toList(); } }
        @Override public synchronized AgentEvent appendEvent(String sessionId, String userId, String runId, AgentEventDraft draft) { AgentEvent value = new AgentEvent(UUID.randomUUID().toString(), runId, sessionId, ++eventSequence, draft.type(), draft.content(), draft.payload(), Instant.now()); events.add(value); return value; }
        @Override public List<AgentEvent> loadEvents(String runId, int limit) { return events.stream().filter(value -> runId.equals(value.runId())).limit(limit).toList(); }
        @Override public List<AgentEvent> loadEventsAfter(String runId, long afterSequence, int limit) { return events.stream().filter(value -> runId.equals(value.runId()) && value.sequence() > afterSequence).limit(limit).toList(); }
    }

    private static final class TimelineContextManager implements AgentContextManager {
        private final MinimalTimeline timeline;
        private TimelineContextManager(MinimalTimeline timeline) { this.timeline = timeline; }
        @Override public AgentContextView project(String sessionId, String userId, String query, long maxTokens) {
            return new AgentContextView(timeline.loadMessages(sessionId, 1_000), 0, 0, false);
        }
        @Override public AgentContextView project(String sessionId, String userId, String tenantId, String query,
                                                  long maxTokens, AgentExecutionProfile profile) {
            return project(sessionId, userId, query, maxTokens);
        }
        @Override public AgentContextView compact(String sessionId, String userId, String runId, String query,
                                                  long maxTokens, String reason) {
            return project(sessionId, userId, query, maxTokens);
        }
        @Override public AgentContextView compact(String sessionId, String userId, String tenantId, String runId,
                                                  String query, long maxTokens, String reason,
                                                  AgentExecutionProfile profile) {
            return project(sessionId, userId, query, maxTokens);
        }
    }

    private static final class ScriptedRfqModel implements AgentModelGateway {
        private final ObjectMapper mapper;
        private final List<String> evidenceRefs;
        private final AtomicInteger turn = new AtomicInteger();
        private ScriptedRfqModel(ObjectMapper mapper, List<String> evidenceRefs) {
            this.mapper = mapper;
            this.evidenceRefs = List.copyOf(evidenceRefs);
        }
        @Override public AgentModelTurn nextTurn(AgentModelRequest request) {
            int current = turn.incrementAndGet();
            LlmUsage usage = new LlmUsage(10, 10, 20, 0, 0, "test", "test");
            if (current == 1) return new AgentModelTurn("", List.of(new AgentToolCall("patch-model", ProcurementToolCatalog.CASE_PATCH,
                    Map.of("productCategory", "计算工作站", "productDescription", "CUDA 开发工作站", "quantity", 50,
                            "budget", 600000, "currency", "CNY", "requiredDeliveryDays", 21,
                            "hardConstraintsUpsert", Map.of("gpuMemoryMinGb", "24")), "patch")), "patch", usage, "tool_calls");
            if (current == 2) return new AgentModelTurn("", List.of(new AgentToolCall("search-model", ProcurementToolCatalog.SUPPLIER_SEARCH, Map.of(), "search")), "search", usage, "tool_calls");
            if (current == 3) {
                return new AgentModelTurn("", List.of(new AgentToolCall("finalize-model", ProcurementToolCatalog.RECOMMENDATION_FINALIZE,
                        Map.of("evaluatedCaseVersion", 1, "selectedSupplierId", "supplier-d", "evidenceRefs", evidenceRefs,
                                "tradeoffDimensions", List.of("DELIVERY"), "confidence", 0.9), "finalize")), "finalize", usage, "tool_calls");
            }
            if (current == 4) return new AgentModelTurn("", List.of(new AgentToolCall("rfq-model", ProcurementToolCatalog.CREATE_RFQ, Map.of(), "用户明确要求发起 RFQ")), "rfq", usage, "tool_calls");
            return new AgentModelTurn("RFQ 已创建。", List.of(), "answer", usage, "stop");
        }
    }

    private static final class NoopRunControlStore implements AgentRunControlStore {
        @Override public void acquireSessionLease(String sessionId, String runId, String leaseOwnerId,
                                                  java.time.Duration leaseDuration) { }
        @Override public boolean renewSessionLease(String sessionId, String leaseOwnerId,
                                                   java.time.Duration leaseDuration) { return true; }
        @Override public void releaseSessionLease(String sessionId, String leaseOwnerId) { }
        @Override public boolean requestCancellation(String runId) { return false; }
        @Override public boolean requestPause(String runId) { return false; }
        @Override public boolean pauseRequested(String runId) { return false; }
        @Override public boolean clearPauseRequest(String runId) { return true; }
        @Override public boolean cancellationRequested(String runId) { return false; }
    }
}
