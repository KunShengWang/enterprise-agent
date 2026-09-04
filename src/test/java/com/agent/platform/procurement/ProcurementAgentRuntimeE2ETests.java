package com.agent.platform.procurement;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.config.McpProperties;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.llm.ConfiguredLlmCostCalculator;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.mcp.StdioMcpToolGateway;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.memory.RuleBasedConversationSummarizer;
import com.agent.platform.memory.UserProfile;
import com.agent.platform.procurement.application.ProcurementCaseContextRenderer;
import com.agent.platform.procurement.application.ProcurementCasePatchMerger;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.config.ProcurementSpecialistProfileFactory;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import com.agent.platform.procurement.provider.McpProcurementDataProvider;
import com.agent.platform.procurement.provider.ProcurementDataProvider;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.procurement.tool.ProcurementSpecialistToolHandler;
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
import com.agent.platform.runtime.AgentRunBudgetSnapshot;
import com.agent.platform.runtime.AgentRunControlStore;
import com.agent.platform.runtime.AgentRunLimits;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentToolCall;
import com.agent.platform.runtime.AgentToolRuntimeResult;
import com.agent.platform.runtime.DefaultAgentCapabilityExecutor;
import com.agent.platform.runtime.DefaultAgentCapabilityRegistry;
import com.agent.platform.runtime.DefaultAgentContextManager;
import com.agent.platform.runtime.DefaultAgentRuntime;
import com.agent.platform.runtime.DefaultAgentToolRuntime;
import com.agent.platform.runtime.ToolExecutionClaim;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.runtime.ToolResultProjector;
import com.agent.platform.runtime.ConservativeTokenEstimator;
import com.agent.platform.multiagent.SubAgentRunner;
import com.agent.platform.skill.SkillRegistry;
import com.agent.platform.tool.JsonSchemaToolParameterValidator;
import com.agent.platform.tool.LocalToolExecutor;
import com.agent.platform.tool.TicketStore;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolExecutor;
import com.agent.platform.tool.ToolHandler;
import com.agent.platform.tool.LocalToolRegistry;
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
import java.util.HashSet;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证真实 DefaultAgentRuntime → ToolRuntime → LocalToolExecutor → 采购 Handler 闭环。 */
class ProcurementAgentRuntimeE2ETests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void modelToolResultAndReplanCompleteRecommendationLoop() {
        ProcurementDataProperties dataProperties = new ProcurementDataProperties();
        ProcurementDataProvider provider = new AwsSyntheticProcurementProvider(mapper, dataProperties);
        ObjectProvider<McpToolGateway> mcpGateways = mock(ObjectProvider.class);
        when(mcpGateways.getIfAvailable()).thenReturn(null);
        RuntimeExecution execution = runRuntime(provider, mcpGateways,
                "procurement-e2e-conversation", "");
        MemoryCaseStore caseStore = execution.caseStore();
        InMemoryToolExecutionStore toolExecutionStore = execution.toolExecutionStore();
        InMemoryTimelineStore timelineStore = execution.timelineStore();
        MemoryService memoryService = execution.memoryService();
        ScriptedProcurementModel model = execution.model();
        AgentRuntimeResult result = execution.result();

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
        verify(memoryService, atLeastOnce()).rememberLongTerm(
                eq("procurement-e2e-conversation"), eq("buyer-1"), any(MemoryMessage.class));
        verify(memoryService, atLeastOnce()).recall(
                anyString(), eq("buyer-1"), anyString(), eq(8));
        verify(memoryService, atLeastOnce()).loadUserProfile("buyer-1");
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

    @Test
    void mcpBackedRuntimeKeepsTheThreeToolLoopAndUsesInternalStdioSource() throws Exception {
        try (ProcurementMcpTestServer server = ProcurementMcpTestServer.create()) {
            McpProperties mcpProperties = new McpProperties();
            mcpProperties.setServers(List.of(server.config()));
            StdioMcpToolGateway gateway = new StdioMcpToolGateway(mcpProperties, mapper);
            try {
                ProcurementDataProperties dataProperties = new ProcurementDataProperties();
                dataProperties.setProvider("mcp");
                ProcurementDataProvider provider = new McpProcurementDataProvider(
                        mapper, dataProperties, gatewayProvider(gateway));
                RuntimeExecution execution = runRuntime(provider, gatewayProvider(gateway),
                        "procurement-mcp-e2e-conversation", "mcp:procurement-fixture");

                assertEquals(AgentRunState.COMPLETED, execution.result().state(),
                        execution.result().stopReason() + " / " + execution.toolExecutionStore().records.values());
                assertTrue(execution.result().answer().contains("Supplier D"));
                assertTrue(execution.result().answer().contains("Supplier B"));
                assertEquals(List.of(ProcurementToolCatalog.CASE_PATCH, ProcurementToolCatalog.SUPPLIER_SEARCH,
                        ProcurementToolCatalog.RECOMMENDATION_FINALIZE), execution.model().toolNames());
                assertTrue(execution.timelineStore().messages.stream()
                        .filter(message -> ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(message.toolName()))
                        .anyMatch(message -> message.content().contains("mcp:procurement-fixture")));

                List<String> events = server.events();
                assertTrue(events.contains("initialize"));
                assertTrue(events.contains("initialized"));
                assertTrue(events.contains("tools/list"));
                assertTrue(events.stream().anyMatch(value -> value.startsWith("tools/call search_suppliers ")));
                assertTrue(events.stream().anyMatch(value -> value.startsWith("tools/call get_offers ")));
                assertTrue(events.stream().filter(value -> value.startsWith("tools/call "))
                        .noneMatch(value -> value.contains("budget") || value.contains("requiredDeliveryDays")
                                || value.contains("excludedSuppliers") || value.contains("preferences")
                                || value.contains("hardConstraints") || value.contains("deliveryPriority")),
                        () -> "MCP source request leaked Case decision fields: " + events);
            }
            finally {
                gateway.shutdown();
            }
        }
    }

    @Test
    void adaptiveProcurementDelegatesBothDimensionsInOneNativeParallelBatch() {
        ProcurementDataProperties dataProperties = new ProcurementDataProperties();
        RuntimeExecution execution = runRuntime(
                new AwsSyntheticProcurementProvider(mapper, dataProperties),
                null,
                "procurement-adaptive-complex",
                "",
                true,
                false);

        assertEquals(AgentRunState.COMPLETED, execution.result().state(),
                execution.result().stopReason() + " / " + execution.toolExecutionStore().records.values());
        assertTrue(execution.result().answer().contains("Supplier D"));
        assertEquals(List.of(ProcurementToolCatalog.CASE_PATCH, ProcurementToolCatalog.SUPPLIER_SEARCH,
                        ProcurementToolCatalog.COMMERCIAL_ANALYSIS, ProcurementToolCatalog.DELIVERY_ANALYSIS,
                        ProcurementToolCatalog.RECOMMENDATION_FINALIZE),
                execution.model().toolNames());

        List<AgentEvent> parallelRequests = execution.timelineStore().events.stream()
                .filter(event -> event.type() == AgentEventType.TOOL_REQUESTED)
                .filter(event -> Boolean.TRUE.equals(event.payload().get("parallelBatch")))
                .toList();
        assertEquals(Set.of(ProcurementToolCatalog.COMMERCIAL_ANALYSIS, ProcurementToolCatalog.DELIVERY_ANALYSIS),
                parallelRequests.stream().map(event -> String.valueOf(event.payload().get("toolName")))
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, execution.timelineStore().events.stream()
                .filter(event -> event.type() == AgentEventType.SUB_AGENT_STARTED).count());
        List<AgentEvent> completed = execution.timelineStore().events.stream()
                .filter(event -> event.type() == AgentEventType.SUB_AGENT_COMPLETED).toList();
        assertEquals(2, completed.size());
        assertEquals(2, completed.stream().map(event -> event.payload().get("childRunId")).distinct().count());
        assertEquals(2, completed.stream().map(event -> event.payload().get("childSessionId")).distinct().count());
        assertEquals(2, execution.model().childRequests().size());
        assertTrue(execution.model().childRequests().stream().allMatch(request -> request.tools().isEmpty()));
        assertTrue(execution.model().childRequests().stream().allMatch(request ->
                request.metadata().get("internalSubAgent").equals(true)));
        assertTrue(execution.model().childRequests().stream().allMatch(request ->
                request.messages().stream().anyMatch(message ->
                        message.content().contains("<procurement_specialist_input trusted_instructions=\"false\">"))));

        List<String> parentTools = execution.model().parentRequests().stream()
                .flatMap(request -> request.messages().stream())
                .filter(AgentMessage::isToolResult)
                .map(AgentMessage::toolName)
                .toList();
        int finalizeIndex = parentTools.lastIndexOf(ProcurementToolCatalog.RECOMMENDATION_FINALIZE);
        assertTrue(finalizeIndex >= 0);
        assertTrue(parentTools.subList(0, finalizeIndex).contains(ProcurementToolCatalog.COMMERCIAL_ANALYSIS));
        assertTrue(parentTools.subList(0, finalizeIndex).contains(ProcurementToolCatalog.DELIVERY_ANALYSIS));
        assertTrue(execution.toolExecutionStore().records.values().stream()
                .filter(record -> record.toolName().equals(ProcurementToolCatalog.COMMERCIAL_ANALYSIS)
                        || record.toolName().equals(ProcurementToolCatalog.DELIVERY_ANALYSIS))
                .allMatch(record -> Boolean.TRUE.equals(record.result().metadata().get("advisory"))
                        && Boolean.FALSE.equals(record.result().metadata().get("authoritativeFacts"))));
        assertEquals(1, execution.caseStore().findByTenantUserAndConversationId(
                "tenant-1", "buyer-1", "procurement-adaptive-complex").orElseThrow().version());
    }

    @Test
    void deterministicAblationMeasuresAdaptiveDelegationOverheadOnSameComplexCase() {
        ProcurementDataProperties dataProperties = new ProcurementDataProperties();
        ProcurementDataProvider provider = new AwsSyntheticProcurementProvider(mapper, dataProperties);
        RuntimeExecution nonAdaptive = runRuntime(provider, null,
                "procurement-eval-complex-off", "", false, false);
        RuntimeExecution adaptive = runRuntime(provider, null,
                "procurement-eval-complex-on", "", true, false);

        assertEquals(AgentRunState.COMPLETED, nonAdaptive.result().state());
        assertEquals(AgentRunState.COMPLETED, adaptive.result().state());
        assertEquals("supplier-d", recommendedSupplierId(nonAdaptive));
        assertEquals("supplier-d", recommendedSupplierId(adaptive));

        assertEquals(0, subAgentStartedCount(nonAdaptive));
        assertEquals(0, specialistToolExecutionCount(nonAdaptive));
        assertEquals(2, subAgentStartedCount(adaptive));
        assertEquals(2, specialistToolExecutionCount(adaptive));

        Set<String> adaptiveParallelTools = adaptive.timelineStore().events.stream()
                .filter(event -> event.runId().equals(adaptive.result().runId()))
                .filter(event -> event.type() == AgentEventType.TOOL_REQUESTED)
                .filter(event -> Boolean.TRUE.equals(event.payload().get("parallelBatch")))
                .map(event -> String.valueOf(event.payload().get("toolName")))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(ProcurementToolCatalog.COMMERCIAL_ANALYSIS,
                        ProcurementToolCatalog.DELIVERY_ANALYSIS), adaptiveParallelTools);

        EvalUsage nonAdaptiveUsage = evalUsage(nonAdaptive);
        EvalUsage adaptiveUsage = evalUsage(adaptive);
        assertEquals(0, nonAdaptiveUsage.childRunCount());
        assertEquals(0, nonAdaptiveUsage.childModelCalls());
        assertEquals(2, adaptiveUsage.childRunCount());
        assertTrue(adaptiveUsage.totalModelCalls() > nonAdaptiveUsage.totalModelCalls());
        assertTrue(adaptiveUsage.totalInputTokens() + adaptiveUsage.totalOutputTokens()
                > nonAdaptiveUsage.totalInputTokens() + nonAdaptiveUsage.totalOutputTokens());
    }

    @Test
    void adaptiveProcurementSkipsChildForSingleEligibleSupplier() {
        ProcurementDataProperties dataProperties = new ProcurementDataProperties();
        RuntimeExecution execution = runRuntime(
                new SingleEligibleProvider(new AwsSyntheticProcurementProvider(mapper, dataProperties)),
                null,
                "procurement-adaptive-simple",
                "",
                true,
                true);

        assertEquals(AgentRunState.COMPLETED, execution.result().state(),
                execution.result().stopReason() + " / " + execution.toolExecutionStore().records.values());
        assertTrue(execution.result().answer().contains("Supplier D"));
        assertEquals(List.of(ProcurementToolCatalog.CASE_PATCH, ProcurementToolCatalog.SUPPLIER_SEARCH,
                        ProcurementToolCatalog.RECOMMENDATION_FINALIZE),
                execution.model().toolNames());
        assertEquals(0, execution.timelineStore().events.stream()
                .filter(event -> event.type() == AgentEventType.SUB_AGENT_STARTED).count());
        assertEquals(0, evalUsage(execution).childRunCount());
        assertTrue(execution.model().childRequests().isEmpty());
        assertTrue(execution.toolExecutionStore().records.values().stream()
                .noneMatch(record -> record.toolName().equals(ProcurementToolCatalog.COMMERCIAL_ANALYSIS)
                        || record.toolName().equals(ProcurementToolCatalog.DELIVERY_ANALYSIS)));
    }

    private RuntimeExecution runRuntime(ProcurementDataProvider provider,
                                        ObjectProvider<McpToolGateway> mcpGateways,
                                        String conversationId,
                                        String expectedOfferSource) {
        return runRuntime(provider, mcpGateways, conversationId, expectedOfferSource, false, false);
    }

    private RuntimeExecution runRuntime(ProcurementDataProvider provider,
                                        ObjectProvider<McpToolGateway> mcpGateways,
                                        String conversationId,
                                        String expectedOfferSource,
                                        boolean adaptive,
                                        boolean simple) {
        MemoryCaseStore caseStore = new MemoryCaseStore();
        ProcurementCasePatchMerger patchMerger = new ProcurementCasePatchMerger();
        ProcurementDecisionEngine decisionEngine = new ProcurementDecisionEngine();
        ProcurementCaseService caseService = new ProcurementCaseService(caseStore, patchMerger);
        ProcurementToolHandler handler = new ProcurementToolHandler(provider, mapper, caseStore, caseService,
                new com.agent.platform.procurement.application.ProcurementRecommendationFinalizer(caseStore, provider, decisionEngine),
                patchMerger, decisionEngine);
        ToolRegistry registry = new LocalToolRegistry(
                mcpGateways,
                contributorProvider(new ProcurementToolCatalog()));
        AgentCapabilityRegistry capabilityRegistry = new DefaultAgentCapabilityRegistry(registry);
        ObjectProvider<ToolHandler> handlers = mock(ObjectProvider.class);
        List<ToolHandler> registeredHandlers = new ArrayList<>();
        registeredHandlers.add(handler);
        when(handlers.orderedStream()).thenAnswer(invocation -> registeredHandlers.stream());
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
        ScriptedProcurementModel model = new ScriptedProcurementModel(mapper, expectedOfferSource, adaptive, simple);
        InMemoryRunStore runStore = new InMemoryRunStore();
        InMemoryTimelineStore timelineStore = new InMemoryTimelineStore();
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.recall(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(memoryService.loadUserProfile(anyString())).thenAnswer(invocation ->
                UserProfile.empty(invocation.getArgument(0, String.class)));
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
                capabilityRegistry, toolRuntime, guardrail, List.of(),
                mock(ApprovalService.class), new ConservativeTokenEstimator(), new NoopRunControlStore(),
                memoryService, new ConfiguredLlmCostCalculator(properties),
                new ToolResultProjector(properties));
        SubAgentRunner subAgentRunner = new SubAgentRunner(runtime, timelineStore);
        registeredHandlers.add(new ProcurementSpecialistToolHandler(
                toolExecutionStore, caseStore, subAgentRunner,
                new ProcurementSpecialistProfileFactory(), mapper));

        AgentRuntimeResult result = runtime.run(new AgentRequest(
                conversationId, "buyer-1",
                "研发部门需要采购 50 台 CUDA 工作站，预算 60 万，三周内到，显存至少 24GB；不要 Supplier A。这次项目比较急，可以稍微贵一点，交付优先。",
                Map.of("tenantId", "tenant-1", "authenticatedRoles", Set.of("USER")),
                profile.name()), profile, AgentEventListener.NOOP);
        return new RuntimeExecution(result, model, caseStore, timelineStore, toolExecutionStore, runStore, memoryService);
    }

    private ObjectProvider<McpToolGateway> gatewayProvider(McpToolGateway gateway) {
        return new ObjectProvider<>() {
            @Override
            public McpToolGateway getIfAvailable() {
                return gateway;
            }
        };
    }

    private ObjectProvider<ToolCatalogContributor> contributorProvider(ToolCatalogContributor contributor) {
        return new ObjectProvider<>() {
            @Override
            public Stream<ToolCatalogContributor> orderedStream() {
                return Stream.of(contributor);
            }
        };
    }

    private record RuntimeExecution(AgentRuntimeResult result,
                                    ScriptedProcurementModel model,
                                    MemoryCaseStore caseStore,
                                    InMemoryTimelineStore timelineStore,
                                    InMemoryToolExecutionStore toolExecutionStore,
                                    InMemoryRunStore runStore,
                                    MemoryService memoryService) {
    }

    private record EvalUsage(int parentModelCalls,
                             int childModelCalls,
                             int totalModelCalls,
                             long parentInputTokens,
                             long childInputTokens,
                             long totalInputTokens,
                             long parentOutputTokens,
                             long childOutputTokens,
                             long totalOutputTokens,
                             int childRunCount) {
    }

    private String recommendedSupplierId(RuntimeExecution execution) {
        List<ToolExecutionRecord> finalizations = execution.toolExecutionStore().records.values().stream()
                .filter(record -> ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(record.toolName()))
                .filter(record -> record.state() == ToolExecutionState.SUCCEEDED)
                .filter(record -> record.result() != null && record.result().success())
                .toList();
        assertEquals(1, finalizations.size(), "必须存在且仅存在一个成功的 Finalize ToolExecutionRecord");
        JsonNode recommendation = readJson(finalizations.get(0).result().content()).path("recommendation");
        String recommendedSupplierId = recommendation.path("recommendedSupplier").path("supplierId").asText();
        String selectedOfferSupplierId = recommendation.path("selectedOffer").path("supplierId").asText();
        assertTrue(!recommendedSupplierId.isBlank(), "canonical recommendation 缺少 recommendedSupplier.supplierId");
        assertEquals(recommendedSupplierId, selectedOfferSupplierId);
        return recommendedSupplierId;
    }

    private EvalUsage evalUsage(RuntimeExecution execution) {
        AgentRunBudgetSnapshot parent = execution.result().budget();
        assertTrue(parent != null, "parent Run 必须提供 budget snapshot");
        Set<String> childRunIds = execution.timelineStore().events.stream()
                .filter(event -> event.runId().equals(execution.result().runId()))
                .filter(event -> event.type() == AgentEventType.SUB_AGENT_COMPLETED)
                .map(event -> event.payload().get("childRunId"))
                .filter(value -> value != null)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        List<AgentRunBudgetSnapshot> childBudgets = childRunIds.stream()
                .map(childRunId -> execution.runStore().find(childRunId)
                        .orElseThrow(() -> new AssertionError("Timeline 引用的 child Run 不存在: " + childRunId)))
                .map(child -> {
                    assertTrue(child.budgetSnapshot() != null,
                            "completed child Run 必须提供 budget snapshot: " + child.runId());
                    return child.budgetSnapshot();
                })
                .toList();
        int childModelCalls = childBudgets.stream().mapToInt(AgentRunBudgetSnapshot::modelCalls).sum();
        long childInputTokens = childBudgets.stream().mapToLong(AgentRunBudgetSnapshot::inputTokens).sum();
        long childOutputTokens = childBudgets.stream().mapToLong(AgentRunBudgetSnapshot::outputTokens).sum();
        return new EvalUsage(
                parent.modelCalls(), childModelCalls, parent.modelCalls() + childModelCalls,
                parent.inputTokens(), childInputTokens, parent.inputTokens() + childInputTokens,
                parent.outputTokens(), childOutputTokens, parent.outputTokens() + childOutputTokens,
                childRunIds.size());
    }

    private long subAgentStartedCount(RuntimeExecution execution) {
        return execution.timelineStore().events.stream()
                .filter(event -> event.runId().equals(execution.result().runId()))
                .filter(event -> event.type() == AgentEventType.SUB_AGENT_STARTED)
                .count();
    }

    private long specialistToolExecutionCount(RuntimeExecution execution) {
        return execution.toolExecutionStore().records.values().stream()
                .filter(record -> ProcurementToolCatalog.COMMERCIAL_ANALYSIS.equals(record.toolName())
                        || ProcurementToolCatalog.DELIVERY_ANALYSIS.equals(record.toolName()))
                .count();
    }

    private JsonNode readJson(String content) {
        try {
            return mapper.readTree(content);
        } catch (Exception exception) {
            throw new AssertionError("无法解析 canonical recommendation ToolResult", exception);
        }
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
        private final String expectedOfferSource;
        private final AtomicInteger turns = new AtomicInteger();
        private final List<AgentModelRequest> requests = new ArrayList<>();
        private final List<String> toolNames = new ArrayList<>();
        private final boolean adaptive;
        private final boolean simple;

        private ScriptedProcurementModel(ObjectMapper mapper) {
            this(mapper, "", false, false);
        }

        private ScriptedProcurementModel(ObjectMapper mapper, String expectedOfferSource) {
            this(mapper, expectedOfferSource, false, false);
        }

        private ScriptedProcurementModel(ObjectMapper mapper,
                                         String expectedOfferSource,
                                         boolean adaptive,
                                         boolean simple) {
            this.mapper = mapper;
            this.expectedOfferSource = expectedOfferSource == null ? "" : expectedOfferSource;
            this.adaptive = adaptive;
            this.simple = simple;
        }

        @Override
        public synchronized AgentModelTurn nextTurn(AgentModelRequest request) {
            requests.add(request);
            if (Boolean.TRUE.equals(request.metadata().get("internalSubAgent"))) {
                assertTrue(request.tools().isEmpty(), "Specialist child 不得暴露任何工具");
                String focus = request.systemPrompt().contains("COMMERCIAL") ? "COMMERCIAL" : "DELIVERY";
                JsonNode packet = specialistPacket(request);
                String supplierBOfferEvidenceRef = offerEvidenceRef(packet, "supplier-b");
                String supplierDOfferEvidenceRef = offerEvidenceRef(packet, "supplier-d");
                String summary = "COMMERCIAL".equals(focus)
                        ? "Supplier B 的 totalPrice 更低，当前输入未提供 contract payment terms。"
                        : "Supplier D 的 leadTimeDays 更短，当前输入未提供 on-time historical rate。";
                return new AgentModelTurn(mapper.writeValueAsString(Map.of(
                                "focus", focus,
                                "summary", summary,
                                "supplierIds", List.of("supplier-b", "supplier-d"),
                                "evidenceRefs", List.of(supplierBOfferEvidenceRef, supplierDOfferEvidenceRef),
                                "limitations", List.of("仅基于当前 Search facts"))),
                        List.of(), "specialist-answer",
                        new LlmUsage(60, 40, 100, 0, 0, "procurement-specialist-scripted", "test"),
                        "stop");
            }
            Set<String> visibleNames = request.tools().stream().map(ToolDefinition::name)
                    .collect(java.util.stream.Collectors.toSet());
                    assertTrue(Set.of(ProcurementToolCatalog.CASE_PATCH, ProcurementToolCatalog.SUPPLIER_SEARCH,
                            ProcurementToolCatalog.COMMERCIAL_ANALYSIS, ProcurementToolCatalog.DELIVERY_ANALYSIS,
                            ProcurementToolCatalog.RECOMMENDATION_FINALIZE, ProcurementToolCatalog.CREATE_RFQ)
                            .containsAll(visibleNames),
                    "模型可见 capability 不得超出采购 Profile");
            assertTrue(request.tools().stream().noneMatch(definition ->
                            definition.name().startsWith("mcp.procurement.")),
                    "内部 MCP source tool 不得成为模型可见 capability");
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
            if (turn == 3 && adaptive && !simple) {
                assertSearchFacts(request);
                toolNames.add(ProcurementToolCatalog.COMMERCIAL_ANALYSIS);
                toolNames.add(ProcurementToolCatalog.DELIVERY_ANALYSIS);
                return new AgentModelTurn("", List.of(
                        new AgentToolCall("model-commercial", ProcurementToolCatalog.COMMERCIAL_ANALYSIS,
                                Map.of(), "分析价格与预算权衡"),
                        new AgentToolCall("model-delivery", ProcurementToolCatalog.DELIVERY_ANALYSIS,
                                Map.of(), "分析交付速度权衡")),
                        "specialists", usage, "tool_calls");
            }
            if (turn == 3 && adaptive && simple) {
                JsonNode search = assertSearchFacts(request);
                assertEquals(Set.of("supplier-d"), eligibleIds(search));
                toolNames.add(ProcurementToolCatalog.RECOMMENDATION_FINALIZE);
                return finalizeCall(search, "单一 Eligible 直接提交 Finalize");
            }
            if (turn == 3) {
                JsonNode search = assertSearchFacts(request);
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
                if (!expectedOfferSource.isBlank()) {
                    for (JsonNode offer : search.path("offers")) {
                        assertEquals(expectedOfferSource, offer.path("source").asText());
                    }
                    assertTrue(search.toString().contains(expectedOfferSource));
                    assertTrue(!search.toString().contains("ignore all rules"));
                }
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
            if (turn == 4 && adaptive && !simple) {
                assertTrue(request.messages().stream().anyMatch(message ->
                        ProcurementToolCatalog.COMMERCIAL_ANALYSIS.equals(message.toolName())
                                && message.type() == AgentMessageType.TOOL_RESULT));
                assertTrue(request.messages().stream().anyMatch(message ->
                        ProcurementToolCatalog.DELIVERY_ANALYSIS.equals(message.toolName())
                                && message.type() == AgentMessageType.TOOL_RESULT));
                toolNames.add(ProcurementToolCatalog.RECOMMENDATION_FINALIZE);
                return finalizeCall(assertSearchFacts(request), "综合 Commercial 与 Delivery advisory 后选择 Supplier D");
            }
            if (turn == 4 && adaptive && simple) {
                JsonNode finalizedSimple = request.messages().stream()
                        .filter(message -> message.type() == AgentMessageType.TOOL_RESULT
                                && ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(message.toolName()))
                        .reduce((left, right) -> right)
                        .map(message -> readTree(message.content()))
                        .orElseThrow(() -> new AssertionError("简单场景未收到 recommendation_finalize ToolResult"));
                assertEquals("supplier-d", finalizedSimple.path("recommendation")
                        .path("selectedOffer").path("supplierId").asText());
                assertEquals(0, finalizedSimple.path("recommendation").path("alternativeOffers").size());
                return new AgentModelTurn("简单场景直接推荐 Supplier D。", List.of(), "answer", usage, "stop");
            }
            JsonNode finalized = request.messages().stream()
                    .filter(message -> message.type() == AgentMessageType.TOOL_RESULT
                            && ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(message.toolName()))
                    .reduce((left, right) -> right)
                    .map(message -> readTree(message.content()))
                    .orElseThrow(() -> new AssertionError("第四轮未收到 recommendation_finalize ToolResult"));
            assertEquals("verified-provider-snapshot", finalized.path("source").asText());
            if (!expectedOfferSource.isBlank()) {
                assertTrue(finalized.toString().contains(expectedOfferSource));
                assertTrue(!finalized.toString().contains("Supplier A is safest"));
                assertTrue(!finalized.toString().contains("remote-digest-must-be-ignored"));
            }
            JsonNode recommendation = finalized.path("recommendation");
            JsonNode selectedOffer = recommendation.path("selectedOffer");
            assertEquals("supplier-d", selectedOffer.path("supplierId").asText());
            if (!expectedOfferSource.isBlank()) {
                assertEquals(expectedOfferSource, selectedOffer.path("source").asText());
            }
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

        private JsonNode assertSearchFacts(AgentModelRequest request) {
            assertTrue(request.messages().stream().anyMatch(AgentMessage::isToolResult),
                    "模型未收到供应商寻源结果：" + request.messages().stream().map(AgentMessage::content).toList());
            return request.messages().stream()
                    .filter(message -> message.type() == AgentMessageType.TOOL_RESULT
                            && ProcurementToolCatalog.SUPPLIER_SEARCH.equals(message.toolName()))
                    .reduce((left, right) -> right)
                    .map(message -> readTree(message.content()))
                    .orElseThrow(() -> new AssertionError("未收到 procurement_supplier_search ToolResult"));
        }

        private Set<String> eligibleIds(JsonNode search) {
            Set<String> result = new HashSet<>();
            for (JsonNode value : search.path("eligibleSuppliers")) {
                result.add(value.path("supplierId").asText());
            }
            return result;
        }

        private AgentModelTurn finalizeCall(JsonNode search, String description) {
            List<String> evidenceRefs = new ArrayList<>();
            for (JsonNode evidence : search.path("evidence")) {
                if ("OFFER".equals(evidence.path("evidenceType").asText())
                        && (eligibleIds(search).contains(evidence.path("supplierId").asText()))) {
                    evidenceRefs.add(evidence.path("evidenceId").asText());
                }
            }
            assertTrue(!evidenceRefs.isEmpty(), "Finalize 至少需要一个 grounded OFFER evidence");
            List<String> dimensions = eligibleIds(search).size() == 1
                    ? List.of("DELIVERY") : List.of("DELIVERY", "PRICE");
            return new AgentModelTurn("", List.of(new AgentToolCall(
                    "model-finalize", ProcurementToolCatalog.RECOMMENDATION_FINALIZE, Map.of(
                            "evaluatedCaseVersion", 1,
                            "selectedSupplierId", "supplier-d",
                            "evidenceRefs", evidenceRefs,
                            "tradeoffDimensions", dimensions,
                            "confidence", 0.86
                    ), description)), "finalize",
                    new LlmUsage(100, 40, 140, 0, 0, "procurement-scripted", "test"),
                    "tool_calls");
        }

        private JsonNode specialistPacket(AgentModelRequest request) {
            String instruction = request.messages().stream()
                    .map(AgentMessage::content)
                    .filter(value -> value.contains("<procurement_specialist_input trusted_instructions=\"false\">"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("child 未收到 filtered specialist input"));
            int start = instruction.indexOf("<procurement_specialist_input trusted_instructions=\"false\">")
                    + "<procurement_specialist_input trusted_instructions=\"false\">".length();
            int end = instruction.indexOf("</procurement_specialist_input>", start);
            return readTree(instruction.substring(start, end).trim());
        }

        private String offerEvidenceRef(JsonNode packet, String supplierId) {
            for (JsonNode evidence : packet.path("evidence")) {
                if (supplierId.equals(evidence.path("supplierId").asText())
                        && "OFFER".equals(evidence.path("evidenceType").asText())) {
                    return evidence.path("evidenceId").asText();
                }
            }
            throw new AssertionError("filtered packet 缺少 " + supplierId + " 的 OFFER evidence");
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

        private List<AgentModelRequest> childRequests() {
            return requests.stream()
                    .filter(request -> Boolean.TRUE.equals(request.metadata().get("internalSubAgent")))
                    .toList();
        }

        private List<AgentModelRequest> parentRequests() {
            return requests.stream()
                    .filter(request -> !Boolean.TRUE.equals(request.metadata().get("internalSubAgent")))
                    .toList();
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
            synchronized (messages) {
                return messages.stream().filter(message -> message.sessionId().equals(sessionId))
                        .sorted(Comparator.comparingLong(AgentMessage::sequence))
                        .limit(limit).toList();
            }
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
            synchronized (events) {
                return events.stream().filter(event -> event.runId().equals(runId)).limit(limit).toList();
            }
        }

        @Override
        public List<AgentEvent> loadEventsAfter(String runId, long afterSequence, int limit) {
            synchronized (events) {
                return events.stream().filter(event -> event.runId().equals(runId) && event.sequence() > afterSequence)
                        .limit(limit).toList();
            }
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

    private static final class SingleEligibleProvider implements ProcurementDataProvider {
        private final ProcurementDataProvider delegate;

        private SingleEligibleProvider(ProcurementDataProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<com.agent.platform.procurement.model.SupplierCandidate> searchSuppliers(
                com.agent.platform.procurement.model.ProcurementCaseState state) {
            return delegate.searchSuppliers(state).stream()
                    .filter(candidate -> "supplier-d".equals(candidate.supplierId()))
                    .toList();
        }

        @Override
        public List<com.agent.platform.procurement.model.SupplierOffer> getSupplierOffers(
                com.agent.platform.procurement.model.ProcurementCaseState state,
                List<com.agent.platform.procurement.model.SupplierCandidate> candidates) {
            return delegate.getSupplierOffers(state, candidates);
        }

        @Override
        public List<com.agent.platform.procurement.model.SupplierEvidence> getSupplierEvidence(
                String supplierId, com.agent.platform.procurement.model.ProcurementCaseState state) {
            return delegate.getSupplierEvidence(supplierId, state);
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
