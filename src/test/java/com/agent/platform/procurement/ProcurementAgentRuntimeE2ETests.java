package com.agent.platform.procurement;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.llm.ConfiguredLlmCostCalculator;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.memory.RuleBasedConversationSummarizer;
import com.agent.platform.procurement.application.ProcurementCaseContextRenderer;
import com.agent.platform.procurement.application.ProcurementCasePatchMerger;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import com.agent.platform.procurement.provider.ProcurementDataProvider;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.procurement.tool.ProcurementToolHandler;
import com.agent.platform.rag.RagService;
import com.agent.platform.runtime.AgentCapabilityRegistry;
import com.agent.platform.runtime.AgentContextManager;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventDraft;
import com.agent.platform.runtime.AgentEventListener;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentMessage;
import com.agent.platform.runtime.AgentMessageDraft;
import com.agent.platform.runtime.AgentMessageType;
import com.agent.platform.runtime.AgentModelGateway;
import com.agent.platform.runtime.AgentModelRequest;
import com.agent.platform.runtime.AgentModelTurn;
import com.agent.platform.runtime.AgentRunControlStore;
import com.agent.platform.runtime.AgentRunLimits;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentToolCall;
import com.agent.platform.runtime.AgentToolRuntimeResult;
import com.agent.platform.runtime.DefaultAgentCapabilityExecutor;
import com.agent.platform.runtime.DefaultAgentContextManager;
import com.agent.platform.runtime.DefaultAgentRuntime;
import com.agent.platform.runtime.DefaultAgentToolRuntime;
import com.agent.platform.runtime.ToolExecutionClaim;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.runtime.ToolResultProjector;
import com.agent.platform.runtime.ConservativeTokenEstimator;
import com.agent.platform.skill.SkillRegistry;
import com.agent.platform.tool.JsonSchemaToolParameterValidator;
import com.agent.platform.tool.LocalToolExecutor;
import com.agent.platform.tool.TicketStore;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolExecutor;
import com.agent.platform.tool.ToolHandler;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolRunRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证真实 DefaultAgentRuntime → ToolRuntime → LocalToolExecutor → 采购 Handler 闭环。 */
class ProcurementAgentRuntimeE2ETests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void modelToolResultAndReplanCompleteRecommendationLoop() {
        MemoryCaseStore caseStore = new MemoryCaseStore();
        ProcurementDataProperties dataProperties = new ProcurementDataProperties();
        ProcurementDataProvider provider = new AwsSyntheticProcurementProvider(mapper, dataProperties);
        ProcurementCasePatchMerger patchMerger = new ProcurementCasePatchMerger();
        ProcurementDecisionEngine decisionEngine = new ProcurementDecisionEngine();
        ProcurementCaseService caseService = new ProcurementCaseService(caseStore, patchMerger);
        ProcurementToolHandler handler = new ProcurementToolHandler(provider, mapper, caseStore, caseService,
                new com.agent.platform.procurement.application.ProcurementRecommendationFinalizer(caseStore, provider, decisionEngine),
                patchMerger, decisionEngine);
        ProcurementToolCatalog catalog = new ProcurementToolCatalog();
        List<ToolDefinition> definitions = catalog.definitions();

        ToolRegistry registry = new MapToolRegistry(definitions);
        ObjectProvider<McpToolGateway> mcpGateways = mock(ObjectProvider.class);
        when(mcpGateways.getIfAvailable()).thenReturn(null);
        ObjectProvider<ToolHandler> handlers = mock(ObjectProvider.class);
        when(handlers.orderedStream()).thenAnswer(invocation -> Stream.of(handler));
        ToolRunRecorder recorder = mock(ToolRunRecorder.class);
        LocalToolExecutor localExecutor = new LocalToolExecutor(
                registry, new JsonSchemaToolParameterValidator(mapper), recorder,
                mock(TicketStore.class), mcpGateways, handlers);
        DefaultAgentCapabilityExecutor capabilityExecutor = new DefaultAgentCapabilityExecutor(
                mock(RagService.class), localExecutor, mock(SkillRegistry.class));

        AgentProperties properties = new AgentProperties();
        properties.setMaxToolExecutionAttempts(1);
        properties.setToolRetryBackoffMillis(0);
        InMemoryToolExecutionStore toolExecutionStore = new InMemoryToolExecutionStore();
        GuardrailService guardrail = allowAllGuardrail();
        DefaultAgentToolRuntime toolRuntime = new DefaultAgentToolRuntime(
                guardrail, mock(ApprovalService.class), toolExecutionStore,
                capabilityExecutor, properties, List.of(), List.of());
        ScriptedProcurementModel model = new ScriptedProcurementModel(mapper);
        InMemoryRunStore runStore = new InMemoryRunStore();
        InMemoryTimelineStore timelineStore = new InMemoryTimelineStore();
        MemoryService memoryService = mock(MemoryService.class);
        AgentContextManager contextManager = new DefaultAgentContextManager(
                timelineStore,
                new ConservativeTokenEstimator(),
                memoryService,
                new RuleBasedConversationSummarizer(),
                properties,
                List.of(new ProcurementCaseContextRenderer(caseStore, mapper)));
        AgentExecutionProfile profile = new ProcurementSourcingExecutionProfileFactory().createProfile();
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
                properties, timelineStore, runStore, toolExecutionStore, contextManager, model,
                new MapCapabilityRegistry(definitions), toolRuntime, guardrail, List.of(),
                mock(ApprovalService.class), new ConservativeTokenEstimator(), new NoopRunControlStore(),
                memoryService, new ConfiguredLlmCostCalculator(properties),
                new ToolResultProjector(properties));

        AgentRuntimeResult result = runtime.run(new AgentRequest(
                "procurement-e2e-conversation", "buyer-1",
                "研发部门需要采购 50 台 CUDA 工作站，预算 60 万，三周内到，显存至少 24GB；不要 Supplier A。这次项目比较急，可以稍微贵一点，交付优先。",
                Map.of("tenantId", "tenant-1", "authenticatedRoles", Set.of("USER")),
                profile.name()), profile, AgentEventListener.NOOP);

        assertEquals(AgentRunState.COMPLETED, result.state(),
                result.stopReason() + " / " + toolExecutionStore.records.values().stream()
                        .map(record -> record.toolName() + ":" + (record.result() == null ? "null" : record.result().errorMessage()))
                        .toList());
        assertTrue(result.answer().contains("Supplier D"));
        assertTrue(result.answer().contains("580000"));
        assertTrue(result.answer().contains("12"));
        assertTrue(result.answer().contains("Supplier B"));
        assertTrue(result.answer().contains("550000"));
        assertTrue(result.answer().contains("18"));
        assertEquals(List.of(
                        ProcurementToolCatalog.CASE_PATCH,
                        ProcurementToolCatalog.SUPPLIER_SEARCH,
                        ProcurementToolCatalog.RECOMMENDATION_FINALIZE),
                model.toolNames());
        assertEquals(4, model.requests.size());
        assertTrue(model.requests.get(1).messages().stream().anyMatch(this::isToolResult));
        assertTrue(model.requests.get(2).messages().stream().anyMatch(message ->
                isToolResult(message) && message.content().contains("eligibleSuppliers")));
        assertTrue(model.requests.get(3).messages().stream().anyMatch(message ->
                isToolResult(message) && message.content().contains("recommendation")));
        for (int index = 1; index < model.requests.size(); index++) {
            List<AgentMessage> canonicalContexts = model.requests.get(index).messages().stream()
                    .filter(message -> ProcurementCaseContextRenderer.SOURCE.equals(
                            message.metadata().get("source")))
                    .toList();
            assertEquals(1, canonicalContexts.size(), "每个采购模型轮次都必须有一个 fresh Case context");
            assertEquals(AgentMessageType.CANONICAL_CONTEXT, canonicalContexts.get(0).type());
            assertEquals(caseStore.findByTenantUserAndConversationId(
                    "tenant-1", "buyer-1", "procurement-e2e-conversation").orElseThrow().caseId(),
                    canonicalContexts.get(0).metadata().get("caseId"));
            assertEquals(1L, canonicalContexts.get(0).metadata().get("caseVersion"));
            assertTrue(canonicalContexts.get(0).content().contains("CUDA 开发工作站"));
        }
        assertTrue(model.requests.stream().flatMap(request -> request.messages().stream())
                .noneMatch(message -> message.content().contains("<memory_context>")));
        verifyNoInteractions(memoryService);
        assertTrue(timelineStore.events.stream().filter(event ->
                        event.type() == AgentEventType.CONTEXT_PREPARED)
                .allMatch(event -> "projection".equals(event.payload().get("reason"))));
        ProcurementCase current = caseStore.findByTenantUserAndConversationId(
                "tenant-1", "buyer-1", "procurement-e2e-conversation").orElseThrow();
        assertEquals(1, current.version());
        assertEquals(50, current.state().quantity());
        assertTrue(timelineStore.messages.stream().anyMatch(this::isToolResult));
        assertTrue(toolExecutionStore.records.values().stream().allMatch(record -> record.state() == ToolExecutionState.SUCCEEDED));
        assertEquals(Map.of("readOnly", false, "sideEffect", true), metadata(toolExecutionStore, ProcurementToolCatalog.CASE_PATCH));
        assertEquals(Map.of("readOnly", true, "sideEffect", false), metadata(toolExecutionStore, ProcurementToolCatalog.SUPPLIER_SEARCH));
        assertEquals(Map.of("readOnly", true, "sideEffect", false), metadata(toolExecutionStore, ProcurementToolCatalog.RECOMMENDATION_FINALIZE));
    }

    private boolean isToolResult(AgentMessage message) {
        return message.type() == AgentMessageType.TOOL_RESULT;
    }

    private Map<String, Object> metadata(InMemoryToolExecutionStore store, String toolName) {
        return store.records.values().stream().filter(record -> record.toolName().equals(toolName))
                .findFirst().orElseThrow().result().metadata().entrySet().stream()
                .filter(entry -> entry.getKey().equals("readOnly") || entry.getKey().equals("sideEffect"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private GuardrailService allowAllGuardrail() {
        return new GuardrailService() {
            @Override
            public GuardrailDecision checkInput(String userQuestion) {
                return GuardrailDecision.allow(GuardrailStage.INPUT, "test");
            }

            @Override
            public GuardrailDecision checkToolCall(ToolDefinition definition, ToolCallRequest request) {
                return GuardrailDecision.allow(GuardrailStage.TOOL, "read-only procurement test");
            }

            @Override
            public GuardrailDecision checkOutput(String answer) {
                return GuardrailDecision.allow(GuardrailStage.OUTPUT, "test");
            }

            @Override
            public GuardrailDecision previewOutput(String answer) {
                return GuardrailDecision.allow(GuardrailStage.OUTPUT, "test");
            }
        };
    }

    private static final class ScriptedProcurementModel implements AgentModelGateway {
        private final ObjectMapper mapper;
        private final AtomicInteger turns = new AtomicInteger();
        private final List<AgentModelRequest> requests = new ArrayList<>();
        private final List<String> toolNames = new ArrayList<>();

        private ScriptedProcurementModel(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public synchronized AgentModelTurn nextTurn(AgentModelRequest request) {
            requests.add(request);
            int turn = turns.incrementAndGet();
            LlmUsage usage = new LlmUsage(100, 40, 140, 0, 0, "procurement-scripted", "test");
            if (turn == 1) {
                toolNames.add(ProcurementToolCatalog.CASE_PATCH);
                return new AgentModelTurn("", List.of(new AgentToolCall(
                        "model-patch", ProcurementToolCatalog.CASE_PATCH, Map.of(
                                "productCategory", "计算工作站",
                                "productDescription", "CUDA 开发工作站",
                                "quantity", 50,
                                "budget", 600000,
                                "currency", "CNY",
                                "requiredDeliveryDays", 21,
                                "hardConstraintsUpsert", Map.of("gpuMemoryMinGb", "24"),
                                "preferencesUpsert", Map.of("deliveryPriority", "HIGH"),
                                "excludedSuppliersAdd", List.of("Supplier A")
                        ), "把自然语言采购约束写入当前 Case")), "patch", usage, "tool_calls");
            }
            if (turn == 2) {
                assertTrue(request.messages().stream().anyMatch(message ->
                        message.type() == AgentMessageType.TOOL_RESULT
                                && message.content().contains("authoritative-procurement-case-store")));
                toolNames.add(ProcurementToolCatalog.SUPPLIER_SEARCH);
                return new AgentModelTurn("", List.of(new AgentToolCall(
                        "model-search", ProcurementToolCatalog.SUPPLIER_SEARCH, Map.of(), "基于最新 Case 查询候选供应商")),
                        "search", usage, "tool_calls");
            }
            if (turn == 3) {
                assertTrue(request.messages().stream().anyMatch(message ->
                        message.type() == AgentMessageType.TOOL_RESULT),
                        "第三轮未收到工具结果：" + request.messages().stream().map(AgentMessage::content).toList());
                JsonNode search = request.messages().stream()
                        .filter(message -> message.type() == AgentMessageType.TOOL_RESULT
                                && message.content().contains("eligibleSuppliers"))
                        .reduce((left, right) -> right)
                        .map(message -> mapper.readTree(message.content()))
                        .orElseThrow(() -> new AssertionError("第三轮未收到供应商寻源结果："
                                + request.messages().stream().map(message -> message.type() + ":" + message.content()
                                + ":metadata=" + message.metadata()).toList()));
                Set<String> eligibleSupplierIds = new java.util.HashSet<>();
                for (JsonNode eligible : search.path("eligibleSuppliers")) {
                    eligibleSupplierIds.add(eligible.path("supplierId").asText());
                }
                assertEquals(Set.of("supplier-b", "supplier-d"), eligibleSupplierIds);
                JsonNode supplierBOffer = null;
                JsonNode supplierDOffer = null;
                for (JsonNode offer : search.path("offers")) {
                    if ("supplier-b".equals(offer.path("supplierId").asText())) supplierBOffer = offer;
                    if ("supplier-d".equals(offer.path("supplierId").asText())) supplierDOffer = offer;
                }
                assertTrue(supplierBOffer != null && supplierDOffer != null);
                assertTrue(supplierBOffer.path("totalPrice").asDouble() < supplierDOffer.path("totalPrice").asDouble(),
                        "Supplier B 应低于 Supplier D，才能证明 Agent 的交期权衡");
                String supplierBOfferEvidenceRef = "";
                String supplierDOfferEvidenceRef = "";
                for (JsonNode evidence : search.path("evidence")) {
                    if ("supplier-b".equals(evidence.path("supplierId").asText())
                            && "OFFER".equals(evidence.path("evidenceType").asText())) supplierBOfferEvidenceRef = evidence.path("evidenceId").asText();
                    if ("supplier-d".equals(evidence.path("supplierId").asText())
                            && "OFFER".equals(evidence.path("evidenceType").asText())) supplierDOfferEvidenceRef = evidence.path("evidenceId").asText();
                }
                assertTrue(!supplierBOfferEvidenceRef.isBlank() && !supplierDOfferEvidenceRef.isBlank());
                toolNames.add(ProcurementToolCatalog.RECOMMENDATION_FINALIZE);
                return new AgentModelTurn("", List.of(new AgentToolCall(
                        "model-finalize", ProcurementToolCatalog.RECOMMENDATION_FINALIZE, Map.of(
                                "evaluatedCaseVersion", 1,
                                "selectedSupplierId", "supplier-d",
                                "evidenceRefs", List.of(supplierBOfferEvidenceRef, supplierDOfferEvidenceRef),
                                "tradeoffDimensions", List.of("DELIVERY", "PRICE"),
                                "confidence", 0.86
                        ), "在多个 Eligible 中提交透明权衡后的选择")), "finalize", usage, "tool_calls");
            }
            JsonNode finalized = request.messages().stream()
                    .filter(message -> message.type() == AgentMessageType.TOOL_RESULT
                            && ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(message.toolName()))
                    .reduce((left, right) -> right)
                    .map(message -> readTree(message.content()))
                    .orElseThrow(() -> new AssertionError("第四轮未收到 recommendation_finalize ToolResult"));
            assertEquals("verified-provider-snapshot", finalized.path("source").asText());
            JsonNode recommendation = finalized.path("recommendation");
            JsonNode selectedOffer = recommendation.path("selectedOffer");
            assertEquals("supplier-d", selectedOffer.path("supplierId").asText());
            assertEquals(580000, selectedOffer.path("totalPrice").asInt());
            assertEquals(12, selectedOffer.path("leadTimeDays").asInt());
            JsonNode alternativeOffer = null;
            for (JsonNode offer : recommendation.path("alternativeOffers")) {
                if ("supplier-b".equals(offer.path("supplierId").asText())) alternativeOffer = offer;
            }
            assertTrue(alternativeOffer != null, "Finalize ToolResult 缺少 Supplier B alternative offer");
            assertEquals(550000, alternativeOffer.path("totalPrice").asInt());
            assertEquals(18, alternativeOffer.path("leadTimeDays").asInt());
            String selectedSupplierName = recommendation.path("recommendedSupplier").path("supplierName").asText();
            String alternativeSupplierName = "";
            for (JsonNode supplier : recommendation.path("eligibleAlternatives")) {
                if ("supplier-b".equals(supplier.path("supplierId").asText())) {
                    alternativeSupplierName = supplier.path("supplierName").asText();
                }
            }
            assertTrue(!alternativeSupplierName.isBlank(), "Finalize ToolResult 缺少 Supplier B alternative candidate");
            return new AgentModelTurn("推荐 " + selectedSupplierName + "：总价 "
                    + selectedOffer.path("totalPrice").asText() + "，交期 "
                    + selectedOffer.path("leadTimeDays").asInt() + " 天；备选 "
                    + alternativeSupplierName + "：总价 " + alternativeOffer.path("totalPrice").asText()
                    + "，交期 " + alternativeOffer.path("leadTimeDays").asInt() + " 天。本阶段仅完成只读推荐。",
                    List.of(), "answer", usage, "stop");
        }

        private JsonNode readTree(String content) {
            try {
                return mapper.readTree(content);
            } catch (Exception exception) {
                throw new AssertionError("无法解析 recommendation_finalize ToolResult", exception);
            }
        }

        private List<String> toolNames() {
            return List.copyOf(toolNames);
        }
    }

    private static final class MapCapabilityRegistry implements AgentCapabilityRegistry {
        private final Map<String, ToolDefinition> definitions;

        private MapCapabilityRegistry(List<ToolDefinition> values) {
            definitions = values.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ToolDefinition::name, value -> value));
        }

        @Override
        public List<ToolDefinition> listCapabilities() {
            return List.copyOf(definitions.values());
        }

        @Override
        public Optional<ToolDefinition> findCapability(String name) {
            return Optional.ofNullable(definitions.get(name));
        }
    }

    private static final class MapToolRegistry implements ToolRegistry {
        private final Map<String, ToolDefinition> definitions;

        private MapToolRegistry(List<ToolDefinition> values) {
            definitions = values.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ToolDefinition::name, value -> value));
        }

        @Override
        public List<ToolDefinition> listTools() {
            return List.copyOf(definitions.values());
        }

        @Override
        public Optional<ToolDefinition> findTool(String toolName) {
            return Optional.ofNullable(definitions.get(toolName));
        }
    }

    private static final class InMemoryTimelineStore implements com.agent.platform.runtime.AgentTimelineStore {
        private final List<AgentMessage> messages = Collections.synchronizedList(new ArrayList<>());
        private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        private long messageSequence;
        private long eventSequence;

        @Override
        public synchronized com.agent.platform.runtime.AgentSession openSession(String sessionId, String userId) {
            return new com.agent.platform.runtime.AgentSession(sessionId, userId, messageSequence + 1,
                    eventSequence + 1, 0, Instant.now(), Instant.now());
        }

        @Override
        public Optional<com.agent.platform.runtime.AgentSession> findSession(String sessionId) {
            return Optional.of(new com.agent.platform.runtime.AgentSession(sessionId, "buyer-1", messageSequence + 1,
                    eventSequence + 1, 0, Instant.now(), Instant.now()));
        }

        @Override
        public synchronized List<AgentMessage> appendMessages(String sessionId, String userId, String runId,
                                                               List<AgentMessageDraft> drafts) {
            List<AgentMessage> result = new ArrayList<>();
            for (AgentMessageDraft draft : drafts) {
                AgentMessage value = new AgentMessage(
                        UUID.randomUUID().toString(), sessionId, runId, ++messageSequence, draft.type(),
                        draft.content(), draft.toolCallId(), draft.toolName(), draft.arguments(), draft.metadata(),
                        draft.estimatedTokens(), Instant.now());
                messages.add(value);
                result.add(value);
            }
            return List.copyOf(result);
        }

        @Override
        public synchronized List<AgentMessage> loadMessages(String sessionId, int limit) {
            return messages.stream().filter(message -> message.sessionId().equals(sessionId))
                    .sorted(Comparator.comparingLong(AgentMessage::sequence))
                    .limit(limit).toList();
        }

        @Override
        public synchronized AgentEvent appendEvent(String sessionId, String userId, String runId,
                                                    AgentEventDraft draft) {
            AgentEvent value = new AgentEvent(UUID.randomUUID().toString(), runId, sessionId, ++eventSequence,
                    draft.type(), draft.content(), draft.payload(), Instant.now());
            events.add(value);
            return value;
        }

        @Override
        public List<AgentEvent> loadEvents(String runId, int limit) {
            return events.stream().filter(event -> event.runId().equals(runId)).limit(limit).toList();
        }

        @Override
        public List<AgentEvent> loadEventsAfter(String runId, long afterSequence, int limit) {
            return events.stream().filter(event -> event.runId().equals(runId) && event.sequence() > afterSequence)
                    .limit(limit).toList();
        }
    }

    private static final class InMemoryRunStore implements com.agent.platform.runtime.AgentRunStore {
        private final Map<String, AgentRunRecord> values = new ConcurrentHashMap<>();

        @Override
        public AgentRunRecord create(AgentRunRecord record) {
            values.put(record.runId(), record);
            return record;
        }

        @Override
        public Optional<AgentRunRecord> find(String runId) {
            return Optional.ofNullable(values.get(runId));
        }

        @Override
        public List<AgentRunRecord> recent(int limit) {
            return values.values().stream().limit(limit).toList();
        }

        @Override
        public AgentRunRecord update(String runId, java.util.function.UnaryOperator<AgentRunRecord> updater) {
            return values.compute(runId, (key, current) -> updater.apply(current));
        }

        @Override
        public Optional<AgentRunRecord> claimForResume(String runId) {
            return Optional.empty();
        }

        @Override
        public Optional<AgentRunRecord> claimPausedForResume(String runId) {
            return Optional.empty();
        }
    }

    private static final class InMemoryToolExecutionStore implements ToolExecutionStore {
        private final Map<String, ToolExecutionRecord> records = new ConcurrentHashMap<>();

        @Override
        public ToolExecutionClaim claim(String runId, ToolCallRequest request) {
            ToolExecutionRecord existing = records.putIfAbsent(request.requestId(), ToolExecutionRecord.running(runId, request));
            return existing == null ? ToolExecutionClaim.acquired()
                    : ToolExecutionClaim.existing(existing, "tool call already exists");
        }

        @Override
        public void markSucceeded(String toolCallId, com.agent.platform.tool.ToolCallResult result) {
            records.computeIfPresent(toolCallId, (key, value) -> value.withResult(ToolExecutionState.SUCCEEDED, result, ""));
        }

        @Override
        public void markFailed(String toolCallId, com.agent.platform.tool.ToolCallResult result) {
            records.computeIfPresent(toolCallId, (key, value) -> value.withResult(ToolExecutionState.FAILED, result, result.errorMessage()));
        }

        @Override
        public void markManualReview(String toolCallId, String reason) {
            records.computeIfPresent(toolCallId, (key, value) -> value.withResult(ToolExecutionState.MANUAL_REVIEW,
                    new com.agent.platform.tool.ToolCallResult(value.toolName(), false, "", reason, Map.of()), reason));
        }

        @Override
        public Optional<ToolExecutionRecord> findToolExecution(String toolCallId) {
            return Optional.ofNullable(records.get(toolCallId));
        }

        @Override
        public List<ToolExecutionRecord> findByRun(String runId) {
            return records.values().stream().filter(value -> value.runId().equals(runId)).toList();
        }
    }

    private static final class MemoryCaseStore implements ProcurementCaseStore {
        private final Map<String, ProcurementCase> values = new ConcurrentHashMap<>();

        @Override
        public Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId, String userId,
                                                                             String conversationId) {
            return Optional.ofNullable(values.get(key(tenantId, userId, conversationId)));
        }

        @Override
        public boolean createIfAbsent(ProcurementCase value) {
            return values.putIfAbsent(key(value.tenantId(), value.userId(), value.conversationId()), value) == null;
        }

        @Override
        public boolean saveIfVersion(ProcurementCase value, long expectedVersion) {
            synchronized (values) {
                String key = key(value.tenantId(), value.userId(), value.conversationId());
                ProcurementCase current = values.get(key);
                if (current == null || current.version() != expectedVersion) return false;
                values.put(key, value);
                return true;
            }
        }

        private String key(String tenantId, String userId, String conversationId) {
            return tenantId + "|" + userId + "|" + conversationId;
        }
    }

    private static final class NoopRunControlStore implements com.agent.platform.runtime.AgentRunControlStore {
        @Override public void acquireSessionLease(String sessionId, String runId, String leaseOwnerId, Duration leaseDuration) { }
        @Override public boolean renewSessionLease(String sessionId, String leaseOwnerId, Duration leaseDuration) { return true; }
        @Override public void releaseSessionLease(String sessionId, String leaseOwnerId) { }
        @Override public boolean requestCancellation(String runId) { return false; }
        @Override public boolean requestPause(String runId) { return false; }
        @Override public boolean pauseRequested(String runId) { return false; }
        @Override public boolean clearPauseRequest(String runId) { return true; }
        @Override public boolean cancellationRequested(String runId) { return false; }
    }
}
