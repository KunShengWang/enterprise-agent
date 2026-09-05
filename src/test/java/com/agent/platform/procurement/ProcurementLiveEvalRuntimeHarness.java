package com.agent.platform.procurement;

import com.agent.platform.EnterpriseAgentApplication;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.llm.ConfiguredLlmCostCalculator;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.memory.MemorySearchResult;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.memory.RuleBasedConversationSummarizer;
import com.agent.platform.memory.UserProfile;
import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.multiagent.SubAgentRunner;
import com.agent.platform.procurement.application.ProcurementCaseContextRenderer;
import com.agent.platform.procurement.application.ProcurementCasePatchMerger;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.application.ProcurementRecommendationFinalizer;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.config.ProcurementSpecialistProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.provider.AwsSyntheticProcurementProvider;
import com.agent.platform.procurement.provider.ProcurementDataProvider;
import com.agent.platform.procurement.tool.ProcurementSpecialistToolHandler;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.procurement.tool.ProcurementToolHandler;
import com.agent.platform.rag.RagService;
import com.agent.platform.runtime.AgentCapabilityExecutor;
import com.agent.platform.runtime.AgentCapabilityRegistry;
import com.agent.platform.runtime.AgentContextManager;
import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventDraft;
import com.agent.platform.runtime.AgentEventListener;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentMessage;
import com.agent.platform.runtime.AgentMessageDraft;
import com.agent.platform.runtime.AgentModelGateway;
import com.agent.platform.runtime.AgentRunControlStore;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentSession;
import com.agent.platform.runtime.DefaultAgentCapabilityExecutor;
import com.agent.platform.runtime.DefaultAgentCapabilityRegistry;
import com.agent.platform.runtime.DefaultAgentContextManager;
import com.agent.platform.runtime.DefaultAgentRuntime;
import com.agent.platform.runtime.DefaultAgentToolRuntime;
import com.agent.platform.runtime.ToolExecutionClaim;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.runtime.ConservativeTokenEstimator;
import com.agent.platform.runtime.ToolResultProjector;
import com.agent.platform.skill.SkillRegistry;
import com.agent.platform.tool.JsonSchemaToolParameterValidator;
import com.agent.platform.tool.LocalToolExecutor;
import com.agent.platform.tool.LocalToolRegistry;
import com.agent.platform.tool.TicketStore;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCatalogContributor;
import com.agent.platform.tool.ToolHandler;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolRunRecorder;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Phase 6C 的测试专用真实模型运行时；不扫描生产应用上下文，不连接生产 Store。 */
final class ProcurementLiveEvalRuntimeHarness implements AutoCloseable {
    static final String TENANT_ID = "tenant-1";
    static final String USER_ID = "buyer-1";
    static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigurableApplicationContext modelContext;
    private final AgentModelGateway modelGateway;
    private final ProcurementDataProvider provider;
    private final ObjectMapper mapper;
    private final String modelName;

    private ProcurementLiveEvalRuntimeHarness(ConfigurableApplicationContext modelContext,
                                              AgentModelGateway modelGateway,
                                              ProcurementDataProvider provider,
                                              ObjectMapper mapper,
                                              String modelName) {
        this.modelContext = modelContext;
        this.modelGateway = modelGateway;
        this.provider = provider;
        this.mapper = mapper;
        this.modelName = modelName;
    }

    static ProcurementLiveEvalRuntimeHarness start() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(EnterpriseAgentApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.lazy-initialization=true",
                        "enterprise-agent.mock-mode=false",
                        "enterprise-agent.model-tool-calling-mode=native",
                        "enterprise-agent.workbench.web.enabled=false")
                .run();
        try {
            AgentModelGateway gateway = context.getBean(AgentModelGateway.class);
            if (!(gateway instanceof com.agent.platform.runtime.NativeToolCallingAgentModelGateway)) {
                throw new IllegalStateException("Spring AgentModelGateway is not NativeToolCallingAgentModelGateway");
            }
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            ProcurementDataProperties dataProperties = new ProcurementDataProperties();
            ProcurementDataProvider provider = new AwsSyntheticProcurementProvider(mapper, dataProperties);
            String modelName = context.getBean(DeepSeekChatProperties.class).getModel();
            return new ProcurementLiveEvalRuntimeHarness(context, gateway, provider, mapper, modelName);
        }
        catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    CaseExecution run(String conversationId, String userMessage) {
        MemoryCaseStore caseStore = new MemoryCaseStore();
        InMemoryTimelineStore timelineStore = new InMemoryTimelineStore();
        InMemoryRunStore runStore = new InMemoryRunStore();
        InMemoryToolExecutionStore toolExecutionStore = new InMemoryToolExecutionStore();
        ProcurementCasePatchMerger patchMerger = new ProcurementCasePatchMerger();
        ProcurementCaseService caseService = new ProcurementCaseService(caseStore, patchMerger);
        ProcurementDecisionEngine decisionEngine = new ProcurementDecisionEngine();
        ProcurementRecommendationFinalizer finalizer = new ProcurementRecommendationFinalizer(
                caseStore, provider, decisionEngine);
        ProcurementToolHandler procurementHandler = new ProcurementToolHandler(
                provider, mapper, caseStore, caseService, finalizer, patchMerger, decisionEngine);

        ObjectProvider<McpToolGateway> mcpGateways = providerOf(null);
        ToolRegistry registry = new LocalToolRegistry(mcpGateways,
                contributorProvider(new ProcurementToolCatalog()));
        AgentCapabilityRegistry capabilityRegistry = new DefaultAgentCapabilityRegistry(registry);
        List<ToolHandler> handlers = new ArrayList<>();
        handlers.add(procurementHandler);
        ObjectProvider<ToolHandler> handlerProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(handlerProvider.orderedStream()).thenAnswer(invocation -> handlers.stream());
        LocalToolExecutor localExecutor = new LocalToolExecutor(
                registry, new JsonSchemaToolParameterValidator(mapper), noOpRecorder(),
                Mockito.mock(TicketStore.class), mcpGateways, handlerProvider);
        AgentCapabilityExecutor capabilityExecutor = new DefaultAgentCapabilityExecutor(
                Mockito.mock(RagService.class), localExecutor, Mockito.mock(SkillRegistry.class));

        AgentProperties properties = new AgentProperties();
        properties.setMaxToolExecutionAttempts(1);
        properties.setToolRetryBackoffMillis(0);
        GuardrailService guardrail = allowAllGuardrail();
        DefaultAgentToolRuntime toolRuntime = new DefaultAgentToolRuntime(
                guardrail, Mockito.mock(ApprovalService.class), toolExecutionStore,
                capabilityExecutor, properties, List.of(), List.of());
        EmptyMemoryService memory = new EmptyMemoryService();
        AgentContextManager contextManager = new DefaultAgentContextManager(
                timelineStore, new ConservativeTokenEstimator(), memory,
                new RuleBasedConversationSummarizer(), properties,
                List.of(new ProcurementCaseContextRenderer(caseStore, mapper)));
        AgentExecutionProfile profile = liveProfile();
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
                properties, timelineStore, runStore, toolExecutionStore, contextManager,
                modelGateway, capabilityRegistry, toolRuntime, guardrail, List.of(),
                Mockito.mock(ApprovalService.class), new ConservativeTokenEstimator(),
                new NoopRunControlStore(), memory, new ConfiguredLlmCostCalculator(properties),
                new ToolResultProjector(properties));
        SubAgentRunner subAgentRunner = new SubAgentRunner(runtime, timelineStore);
        handlers.add(new ProcurementSpecialistToolHandler(toolExecutionStore, caseStore,
                subAgentRunner, new ProcurementSpecialistProfileFactory(), mapper));

        AgentRuntimeResult result = runtime.run(new AgentRequest(
                conversationId, USER_ID, userMessage,
                Map.of("tenantId", TENANT_ID, "authenticatedRoles", Set.of("USER"))),
                profile, AgentEventListener.NOOP);
        return new CaseExecution(result, caseStore, timelineStore, runStore, toolExecutionStore, provider);
    }

    private AgentExecutionProfile liveProfile() {
        AgentExecutionProfile base = new ProcurementSourcingExecutionProfileFactory().createProfile();
        return new AgentExecutionProfile("procurement-benchmark-live-v1", base.systemPrompt(), Set.of(
                ProcurementToolCatalog.CASE_PATCH, ProcurementToolCatalog.SUPPLIER_SEARCH,
                ProcurementToolCatalog.COMMERCIAL_ANALYSIS, ProcurementToolCatalog.DELIVERY_ANALYSIS,
                ProcurementToolCatalog.RECOMMENDATION_FINALIZE), base.limits(), false);
    }

    @Override
    public void close() {
        modelContext.close();
    }

    String modelName() {
        return modelName;
    }

    private static GuardrailService allowAllGuardrail() {
        return new GuardrailService() {
            @Override public GuardrailDecision checkInput(String question) { return GuardrailDecision.allow(GuardrailStage.INPUT, "live eval"); }
            @Override public GuardrailDecision checkToolCall(com.agent.platform.tool.ToolDefinition definition,
                                                              com.agent.platform.tool.ToolCallRequest request) {
                return GuardrailDecision.allow(GuardrailStage.TOOL, "live eval");
            }
            @Override public GuardrailDecision checkOutput(String answer) { return GuardrailDecision.allow(GuardrailStage.OUTPUT, "live eval"); }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static ObjectProvider<ToolCatalogContributor> contributorProvider(ToolCatalogContributor contributor) {
        ObjectProvider<ToolCatalogContributor> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(contributor));
        return provider;
    }

    private static ToolRunRecorder noOpRecorder() {
        return new ToolRunRecorder() {
            @Override public void record(com.agent.platform.tool.ToolCallRecord record) { }
            @Override public List<com.agent.platform.tool.ToolCallRecord> recent(int limit) { return List.of(); }
            @Override public com.agent.platform.tool.ToolRunStats stats() {
                return new com.agent.platform.tool.ToolRunStats(0, 0, 0, 0, Map.of());
            }
        };
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    record CaseExecution(AgentRuntimeResult result,
                         MemoryCaseStore caseStore,
                         InMemoryTimelineStore timelineStore,
                         InMemoryRunStore runStore,
                         InMemoryToolExecutionStore toolExecutionStore,
                         ProcurementDataProvider provider) { }

    static final class InMemoryToolExecutionStore implements ToolExecutionStore {
        private final Map<String, ToolExecutionRecord> records = new ConcurrentHashMap<>();
        @Override public ToolExecutionClaim claim(String runId, ToolCallRequest request) {
            ToolExecutionRecord existing = records.putIfAbsent(request.requestId(), ToolExecutionRecord.running(runId, request));
            return existing == null ? ToolExecutionClaim.acquired() : ToolExecutionClaim.existing(existing, "tool call already exists");
        }
        @Override public void markSucceeded(String id, com.agent.platform.tool.ToolCallResult result) {
            records.computeIfPresent(id, (key, value) -> value.withResult(ToolExecutionState.SUCCEEDED, result, ""));
        }
        @Override public void markFailed(String id, com.agent.platform.tool.ToolCallResult result) {
            records.computeIfPresent(id, (key, value) -> value.withResult(ToolExecutionState.FAILED, result, result.errorMessage()));
        }
        @Override public void markManualReview(String id, String reason) {
            records.computeIfPresent(id, (key, value) -> value.withResult(ToolExecutionState.MANUAL_REVIEW,
                    new com.agent.platform.tool.ToolCallResult(value.toolName(), false, "", reason, Map.of()), reason));
        }
        @Override public Optional<ToolExecutionRecord> findToolExecution(String id) { return Optional.ofNullable(records.get(id)); }
        @Override public List<ToolExecutionRecord> findByRun(String runId) {
            return records.values().stream().filter(value -> value.runId().equals(runId)).toList();
        }
        List<ToolExecutionRecord> all() { return List.copyOf(records.values()); }
    }

    static final class MemoryCaseStore implements ProcurementCaseStore {
        private final Map<String, ProcurementCase> values = new ConcurrentHashMap<>();
        @Override public Optional<ProcurementCase> findByTenantUserAndConversationId(String tenant, String user, String conversation) {
            return Optional.ofNullable(values.get(key(tenant, user, conversation)));
        }
        @Override public boolean createIfAbsent(ProcurementCase value) { return values.putIfAbsent(key(value.tenantId(), value.userId(), value.conversationId()), value) == null; }
        @Override public boolean saveIfVersion(ProcurementCase value, long expectedVersion) {
            synchronized (values) {
                String key = key(value.tenantId(), value.userId(), value.conversationId());
                ProcurementCase current = values.get(key);
                if (current == null || current.version() != expectedVersion) return false;
                values.put(key, value);
                return true;
            }
        }
        private String key(String tenant, String user, String conversation) { return tenant + "|" + user + "|" + conversation; }
    }

    static final class InMemoryRunStore implements com.agent.platform.runtime.AgentRunStore {
        private final Map<String, AgentRunRecord> values = new ConcurrentHashMap<>();
        @Override public AgentRunRecord create(AgentRunRecord record) { values.put(record.runId(), record); return record; }
        @Override public Optional<AgentRunRecord> find(String runId) { return Optional.ofNullable(values.get(runId)); }
        @Override public List<AgentRunRecord> recent(int limit) { return values.values().stream().limit(limit).toList(); }
        @Override public AgentRunRecord update(String runId, java.util.function.UnaryOperator<AgentRunRecord> updater) { return values.compute(runId, (key, current) -> updater.apply(current)); }
        @Override public Optional<AgentRunRecord> claimForResume(String runId) { return Optional.empty(); }
        @Override public Optional<AgentRunRecord> claimPausedForResume(String runId) { return Optional.empty(); }
    }

    static final class InMemoryTimelineStore implements com.agent.platform.runtime.AgentTimelineStore {
        private final List<AgentMessage> messages = Collections.synchronizedList(new ArrayList<>());
        private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        private long messageSequence;
        private long eventSequence;
        @Override public synchronized AgentSession openSession(String sessionId, String userId) {
            return new AgentSession(sessionId, userId, messageSequence + 1, eventSequence + 1, 0, Instant.now(), Instant.now());
        }
        @Override public synchronized Optional<AgentSession> findSession(String sessionId) {
            return Optional.of(new AgentSession(sessionId, USER_ID, messageSequence + 1, eventSequence + 1, 0, Instant.now(), Instant.now()));
        }
        @Override public synchronized List<AgentMessage> appendMessages(String sessionId, String userId, String runId, List<AgentMessageDraft> drafts) {
            List<AgentMessage> result = new ArrayList<>();
            for (AgentMessageDraft draft : drafts) {
                AgentMessage value = new AgentMessage(UUID.randomUUID().toString(), sessionId, runId, ++messageSequence,
                        draft.type(), draft.content(), draft.toolCallId(), draft.toolName(), draft.arguments(), draft.metadata(),
                        draft.estimatedTokens(), Instant.now());
                messages.add(value);
                result.add(value);
            }
            return List.copyOf(result);
        }
        @Override public synchronized List<AgentMessage> loadMessages(String sessionId, int limit) {
            synchronized (messages) {
                return messages.stream().filter(value -> value.sessionId().equals(sessionId))
                        .sorted(Comparator.comparingLong(AgentMessage::sequence)).limit(limit).toList();
            }
        }
        @Override public synchronized AgentEvent appendEvent(String sessionId, String userId, String runId, AgentEventDraft draft) {
            AgentEvent value = new AgentEvent(UUID.randomUUID().toString(), runId, sessionId, ++eventSequence,
                    draft.type(), draft.content(), draft.payload(), Instant.now());
            events.add(value);
            return value;
        }
        @Override public List<AgentEvent> loadEvents(String runId, int limit) {
            synchronized (events) { return events.stream().filter(value -> value.runId().equals(runId)).limit(limit).toList(); }
        }
        @Override public List<AgentEvent> loadEventsAfter(String runId, long afterSequence, int limit) {
            synchronized (events) { return events.stream().filter(value -> value.runId().equals(runId) && value.sequence() > afterSequence).limit(limit).toList(); }
        }
    }

    static final class EmptyMemoryService implements MemoryService {
        @Override public void rememberLongTerm(String conversationId, String userId, MemoryMessage message) { }
        @Override public List<MemorySearchResult> recall(String conversationId, String userId, String query, int limit) { return List.of(); }
        @Override public UserProfile loadUserProfile(String userId) { return UserProfile.empty(userId); }
        @Override public void upsertUserProfile(String userId, String key, String value, String source, Instant updatedAt) { }
        @Override public void clearConversation(String conversationId) { }
        @Override public void clearUserMemory(String userId) { }
    }

    static final class NoopRunControlStore implements AgentRunControlStore {
        @Override public void acquireSessionLease(String sessionId, String runId, String owner, Duration duration) { }
        @Override public boolean renewSessionLease(String sessionId, String owner, Duration duration) { return true; }
        @Override public void releaseSessionLease(String sessionId, String owner) { }
        @Override public boolean requestCancellation(String runId) { return false; }
        @Override public boolean requestPause(String runId) { return false; }
        @Override public boolean pauseRequested(String runId) { return false; }
        @Override public boolean clearPauseRequest(String runId) { return true; }
        @Override public boolean cancellationRequested(String runId) { return false; }
    }
}
