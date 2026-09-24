package com.agent.platform.web;

import com.agent.platform.agent.*;
import com.agent.platform.common.GlobalExceptionHandler;
import com.agent.platform.config.*;
import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.resilience.*;
import com.agent.platform.runtime.*;
import com.agent.platform.stream.DefaultStreamingAgentExecutor;
import com.agent.platform.workbench.application.*;
import com.agent.platform.workbench.dispatch.*;
import com.agent.platform.workbench.model.*;
import com.agent.platform.workbench.persistence.*;
import com.agent.platform.workbench.presentation.*;
import com.agent.platform.workbench.security.*;
import com.agent.platform.workbench.web.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP controllers, policy, dispatch adapter and executor; persistence/model boundaries are test doubles. */
class ConversationIsolationHttpTests {
    private final AuthenticatedPrincipal alice = new AuthenticatedPrincipal("t1", "alice", Set.of("USER"));
    private final AtomicReference<AuthenticatedPrincipal> identity = new AtomicReference<>(alice);
    private ConversationAccessPolicy access;
    private AgentTimelineStore timeline;
    private final Map<String, List<AgentMessage>> messages = new ConcurrentHashMap<>();
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final AgentRunStore runs = mock(AgentRunStore.class);
    private final ProcurementCaseStore cases = mock(ProcurementCaseStore.class);
    private final UnifiedWorkIntakeService intake = mock(UnifiedWorkIntakeService.class);
    private final UnifiedWorkLauncher launcher = mock(UnifiedWorkLauncher.class);
    private WebTestClient client;

    @org.junit.jupiter.api.BeforeEach
    void setup() { initialize(new InMemoryConversationOwners(), null); }

    static ConversationIsolationHttpTests withPersistence(ConversationOwnershipStore owners, AgentTimelineStore timeline) {
        var fixture = new ConversationIsolationHttpTests();
        fixture.initialize(owners, timeline);
        return fixture;
    }

    private void initialize(ConversationOwnershipStore owners, AgentTimelineStore persistedTimeline) {
        access = new ConversationAccessPolicy(owners);
        timeline = persistedTimeline == null ? mock(AgentTimelineStore.class) : persistedTimeline;
        var caseValues = new ConcurrentHashMap<String, ProcurementCase>();
        when(cases.findByTenantUserAndConversationId(anyString(), anyString(), anyString()))
                .thenAnswer(c -> Optional.ofNullable(caseValues.get(c.getArgument(0) + "/" + c.getArgument(1) + "/" + c.getArgument(2))));
        when(cases.createIfAbsent(any())).thenAnswer(c -> {
            ProcurementCase v = c.getArgument(0);
            return caseValues.putIfAbsent(v.tenantId()+"/"+v.userId()+"/"+v.conversationId(), v) == null;
        });
        if (persistedTimeline == null) when(timeline.loadMessages(anyString(), anyInt())).thenAnswer(c -> List.copyOf(messages.getOrDefault(c.getArgument(0), List.of())));
        when(runtime.run(any(), any(AgentExecutionProfile.class), any())).thenAnswer(c -> {
            AgentRequest request = c.getArgument(0);
            if (persistedTimeline != null) timeline.appendMessages(request.conversationId(), request.userId(), "run",
                    List.of(AgentMessageDraft.user(request.question(), 1)));
            messages.computeIfAbsent(request.conversationId(), key -> new ArrayList<>()).add(new AgentMessage(
                    UUID.randomUUID().toString(), request.conversationId(), "run", 1, AgentMessageType.USER,
                    request.question(), "", "", Map.of(), Map.of(), 1, Instant.now()));
            return new AgentRuntimeResult("run", request.conversationId(), AgentRunState.COMPLETED,
                    AgentStopReason.COMPLETED, "done", "", null, List.of());
        });
        var properties = new AgentProperties();
        var resolver = new AgentScenarioProfileResolver(List.of(new ProcurementSourcingExecutionProfileFactory()));
        var executor = new RuntimeAgentExecutor(runtime, resolver);
        var caseService = new ProcurementCaseService(cases, new ProcurementCasePatchMerger());
        var direct = new PublicProcurementRunPolicy(identity::get, caseService, resolver, access);
        var limits = mock(RateLimitService.class);
        when(limits.acquire(anyString())).thenReturn(new RateLimitResult(true, "key", 60, 59, 0));
        var api = new AgentController(executor, properties,
                new DefaultStreamingAgentExecutor(runtime, properties, resolver), limits, runs, runtime, timeline, direct);
        var adapter = new ProcurementSourcingExecutionAdapter(executor, runs, cases);
        var pending = new HashMap<String, UnifiedWorkInputRequest>();
        when(intake.accept(any(), any())).thenAnswer(c -> {
            UnifiedWorkInputRequest input = c.getArgument(1);
            pending.put(input.inputId(), input);
            var turn = mock(AgentConversationTurn.class); when(turn.inputId()).thenReturn(input.inputId());
            var work = mock(AgentWorkItem.class); when(work.workItemId()).thenReturn(input.inputId());
            when(work.routingRequestId()).thenReturn("route"); when(work.controlState()).thenReturn(WorkControlState.READY_TO_DISPATCH);
            var decision = mock(WorkCommandDecision.class); when(decision.commandType()).thenReturn(WorkCommandType.NORMAL_GOAL);
            return new UnifiedWorkIntakeResult(turn, decision, work, false);
        });
        doAnswer(c -> {
            AuthenticatedPrincipal p = c.getArgument(0);
            UnifiedWorkInputRequest input = pending.get(c.getArgument(1));
            caseService.ensureCase(p.tenantId(), input.conversationId(), p.principalId());
            var validated = mock(ValidatedExecutionInput.class);
            when(validated.inputDigest()).thenReturn("digest"); when(validated.typedPayload()).thenReturn(Map.of());
            adapter.dispatch(new DispatchRequest(input.inputId(), input.inputId(), input.conversationId(), input.content(),
                    "PROCUREMENT_SOURCING", p, validated, Instant.now()));
            return null;
        }).when(launcher).routeAndDispatch(any(), anyString(), anyString());
        var workbench = new UnifiedWorkController(identity::get, intake, launcher, mock(UnifiedWorkQueryService.class),
                mock(RouteConfirmationService.class), mock(ConversationFocusService.class), mock(WorkbenchStore.class),
                mock(RoutingStore.class), mock(UnifiedWorkEventStreamService.class), mock(UnifiedWorkExecutionTreeService.class),
                mock(WorkCommandHandler.class), mock(WorkItemBudgetQueryService.class), mock(PublicPresentationService.class),
                mock(PublicPresentationStreamService.class), access);
        client = WebTestClient.bindToController(api, workbench).controllerAdvice(new GlobalExceptionHandler()).build();
    }

    private WebTestClient.ResponseSpec submit(String id, Map<String, Object> metadata) {
        return client.post().uri("/api/agent/conversations/{id}/inputs", id).header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("content", "采购会话内容", "metadata", metadata)).exchange();
    }
    private WebTestClient.ResponseSpec direct(String id) {
        return client.post().uri("/api/agent/runs").bodyValue(new AgentRequest(id, "forged", "你好", Map.of())).exchange();
    }
    private WebTestClient.ResponseSpec read(String id) {
        return client.get().uri("/api/agent/conversations/{id}/messages", id).exchange();
    }

    @ParameterizedTest @CsvSource({"t1,bob", "t2,alice"})
    void foreignIdentityCannotContinueReadOrRemapExistingWorkbenchConversation(String tenant, String user) {
        submit("shared", Map.of()).expectStatus().isAccepted();
        identity.set(new AuthenticatedPrincipal(tenant, user, Set.of("USER")));
        submit("shared", Map.of()).expectStatus().isForbidden();
        read("shared").expectStatus().isForbidden();
        direct("shared").expectStatus().isForbidden();
        assertEquals(1, messages.get("shared").size());
        assertEquals(1, messages.size());
        identity.set(alice);
        submit("shared", Map.of()).expectStatus().isAccepted();
        read("shared").expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(2);
    }

    @Test
    void directConversationCannotBeReusedThroughWorkbenchOrByAnotherIdentity() {
        direct("alias").expectStatus().isOk();
        String id = messages.keySet().iterator().next();
        submit(id, Map.of()).expectStatus().isForbidden();
        direct(id).expectStatus().isOk();
        direct("alias").expectStatus().isOk();
        read(id).expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(3);
        identity.set(new AuthenticatedPrincipal("t2", "bob", Set.of("USER")));
        direct(id).expectStatus().isForbidden();
        submit(id, Map.of()).expectStatus().isForbidden();
        read(id).expectStatus().isForbidden();
        assertEquals(3, messages.get(id).size());
    }

    @Test
    void clientMetadataCannotClaimOwnershipAndUnknownReadsDoNotLoadTimeline() {
        submit("shared", Map.of("tenantId", "victim", "userId", "victim")).expectStatus().isBadRequest();
        verifyNoInteractions(intake, launcher, runtime);
        read("unknown").expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(0);
        verifyNoInteractions(timeline);
        submit("shared", Map.of()).expectStatus().isAccepted();
        identity.set(new AuthenticatedPrincipal("t2", "bob", Set.of("USER")));
        submit("shared", Map.of("tenantId", "t1", "userId", "alice")).expectStatus().isBadRequest();
        assertEquals(1, messages.get("shared").size());
    }
}
