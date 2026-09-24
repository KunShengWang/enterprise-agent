package com.agent.platform.web;

import com.agent.platform.agent.*;
import com.agent.platform.common.GlobalExceptionHandler;
import com.agent.platform.config.*;
import com.agent.platform.multiagent.MultiAgentOrchestrator;
import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.resilience.*;
import com.agent.platform.runtime.*;
import com.agent.platform.stream.DefaultStreamingAgentExecutor;
import com.agent.platform.workbench.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicProcurementRunTests {
    private static final String PROFILE = ProcurementSourcingExecutionProfileFactory.PROFILE_NAME;
    record Endpoint(String path, MediaType accept) { }
    static Stream<Endpoint> endpoints() {
        return Stream.of(new Endpoint("/api/agent/runs", MediaType.APPLICATION_JSON),
                new Endpoint("/api/agent/runs", MediaType.TEXT_EVENT_STREAM),
                new Endpoint("/api/agent/runs/events", MediaType.TEXT_EVENT_STREAM),
                new Endpoint("/api/agent/runs/stream", MediaType.TEXT_EVENT_STREAM));
    }

    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final AgentRunStore runs = mock(AgentRunStore.class);
    private final AgentTimelineStore timeline = mock(AgentTimelineStore.class);
    private final ProcurementCaseStore store = mock(ProcurementCaseStore.class);
    private final RateLimitService limits = mock(RateLimitService.class);
    private final WorkbenchPrincipalProvider principals = mock(WorkbenchPrincipalProvider.class);
    private final Map<String, ProcurementCase> saved = new ConcurrentHashMap<>();
    private final AgentScenarioProfileResolver resolver = new AgentScenarioProfileResolver(List.of(
            new ProcurementSourcingExecutionProfileFactory()));
    private final PublicProcurementRunPolicy policy = new PublicProcurementRunPolicy(principals,
            new ProcurementCaseService(store, new ProcurementCasePatchMerger()), resolver,
            new ConversationAccessPolicy(new InMemoryConversationOwners()));
    private final WebTestClient client;

    PublicProcurementRunTests() {
        when(principals.current()).thenReturn(new AuthenticatedPrincipal("tenant", "trusted-user", Set.of("USER")));
        when(limits.acquire(anyString())).thenReturn(new RateLimitResult(true, "trusted", 60, 59, 0));
        when(store.findByTenantUserAndConversationId(anyString(), anyString(), anyString()))
                .thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(2))));
        when(store.createIfAbsent(any())).thenAnswer(call -> {
            ProcurementCase value = call.getArgument(0);
            return saved.putIfAbsent(value.conversationId(), value) == null;
        });
        when(runtime.run(any(), any(AgentExecutionProfile.class), any())).thenAnswer(call -> {
            AgentRequest request = call.getArgument(0);
            AgentEventListener listener = call.getArgument(2);
            listener.onEvent(new AgentEvent("event", "run", request.conversationId(), 1,
                    AgentEventType.MODEL_DELTA, "采购回答", Map.of(), Instant.now()));
            return new AgentRuntimeResult("run", request.conversationId(), AgentRunState.COMPLETED,
                    AgentStopReason.COMPLETED, "采购回答", "", null, List.of());
        });
        var properties = new AgentProperties();
        client = WebTestClient.bindToController(new AgentController(new RuntimeAgentExecutor(runtime, resolver),
                properties, new DefaultStreamingAgentExecutor(runtime, properties, resolver), limits,
                runs, runtime, timeline, policy))
                .controllerAdvice(new GlobalExceptionHandler()).build();
    }

    private WebTestClient.ResponseSpec post(Endpoint endpoint, String scenario, String question, Map<String, Object> metadata) {
        return client.post().uri(endpoint.path()).contentType(MediaType.APPLICATION_JSON).accept(endpoint.accept())
                .bodyValue(new AgentRequest("client-session", "forged-user", question, metadata, scenario)).exchange();
    }

    @ParameterizedTest @MethodSource("endpoints")
    void blankAndExplicitProcurementUseRealAdaptersAndTrustedCase(Endpoint endpoint) {
        for (String scenario : List.of("", PROFILE)) {
            post(endpoint, scenario, "你好", Map.of("executionTarget", "main-agent", "scenarioId", "general-agent-v1",
                    "tenantId", "evil", "authenticatedRoles", List.of("ADMIN"), "procurementCaseId", "victim-case",
                    "procurementCaseVersion", 999, "dispatchRequestId", "victim-dispatch", "userId", "victim"))
                    .expectStatus().isOk().expectBody().consumeWith(result -> assertNotNull(result.getResponseBody()));
        }
        verify(runtime, times(2)).run(argThat(request -> {
            assertEquals(PROFILE, request.scenarioId());
            assertEquals("trusted-user", request.userId());
            assertEquals("tenant", request.metadata().get("tenantId"));
            assertEquals(Set.of("USER"), request.metadata().get("authenticatedRoles"));
            assertEquals("PROCUREMENT_SOURCING", request.metadata().get("executionTarget"));
            assertEquals(5, request.metadata().size());
            ProcurementCase value = saved.get(request.conversationId());
            assertNotNull(value);
            assertEquals(value.caseId(), request.metadata().get("procurementCaseId"));
            assertEquals(0L, request.metadata().get("procurementCaseVersion"));
            assertEquals("trusted-user", value.userId());
            return true;
        }), argThat(profile -> PROFILE.equals(profile.name())), any());
        verify(runtime, never()).run(any(AgentRequest.class));
        verify(runtime, never()).run(any(AgentRequest.class), any(AgentEventListener.class));
        assertEquals(1, saved.size());
        verify(limits, times(2)).acquire("procurement-api:6:tenanttrusted-user");
    }

    @ParameterizedTest @MethodSource("endpoints")
    void generalUnknownAndRetiredRequestsRejectBeforeSideEffects(Endpoint endpoint) {
        for (String scenario : List.of("general-agent-v1", "main-agent", "unknown-profile")) {
            post(endpoint, scenario, "你好", Map.of()).expectStatus().isBadRequest();
        }
        post(endpoint, "ordercare-floworder-v1", "你好", Map.of()).expectStatus().isEqualTo(410);
        post(endpoint, "", "调查 FlowOrder 订单异常", Map.of()).expectStatus().isEqualTo(410);
        post(endpoint, "", "你好", Map.of("executionTarget", "ORDERCARE_CASE")).expectStatus().isEqualTo(410);
        post(endpoint, "", "你好", Map.of("executionTarget", "GENERAL_AGENT")).expectStatus().isEqualTo(410);
        verifyNoInteractions(runtime, store, limits);
    }

    @ParameterizedTest @MethodSource("endpoints")
    void caseFailureNeverFallsBackOrStartsRuntime(Endpoint endpoint) {
        when(store.findByTenantUserAndConversationId(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("case storage unavailable"));
        post(endpoint, PROFILE, "采购电脑", Map.of()).expectStatus().is5xxServerError();
        verifyNoInteractions(runtime);
    }

    @ParameterizedTest @MethodSource("endpoints")
    void deniedRateLimitCannotCreateCaseOrRun(Endpoint endpoint) {
        when(limits.acquire(anyString())).thenReturn(new RateLimitResult(false, "trusted", 60, 0, 0));
        post(endpoint, "", "你好", Map.of()).expectStatus().isOk().expectBody()
                .consumeWith(result -> assertNotNull(result.getResponseBody()));
        verifyNoInteractions(store, runtime);
    }

    @Test
    void unavailableProfileFailsClosedBeforeCaseCreation() {
        var unavailable = new PublicProcurementRunPolicy(principals,
                new ProcurementCaseService(store, new ProcurementCasePatchMerger()),
                new AgentScenarioProfileResolver(List.of()), new ConversationAccessPolicy(new InMemoryConversationOwners()));
        assertThrows(IllegalArgumentException.class, () -> unavailable.authorize(
                new AgentRequest("c", "u", "你好", Map.of())));
        verifyNoInteractions(store, runtime);
    }

    @Test
    void directSessionsAreOwnedAndReturnedIdIsReusable() {
        var input = new AgentRequest("legacy-conversation", "forged", "你好", Map.of());
        var first = policy.authorize(input);
        assertNotEquals("legacy-conversation", first.conversationId());
        assertEquals(first.conversationId(), policy.authorize(input).conversationId());
        assertEquals(first.conversationId(), policy.authorize(new AgentRequest(
                first.conversationId(), "forged", "继续", Map.of())).conversationId());
        when(principals.current()).thenReturn(new AuthenticatedPrincipal("other-tenant", "trusted-user", Set.of("USER")));
        assertNotEquals(first.conversationId(), policy.authorize(input).conversationId());
        assertThrows(com.agent.platform.workbench.persistence.WorkbenchAccessDeniedException.class, () -> policy.authorize(new AgentRequest(
                first.conversationId(), "forged", "继续", Map.of())));
    }

    @ParameterizedTest @MethodSource("endpoints")
    void runtimeFailurePropagatesWithoutAnotherProfile(Endpoint endpoint) {
        when(runtime.run(any(), any(AgentExecutionProfile.class), any()))
                .thenThrow(new IllegalStateException("procurement execution failed"));
        var response = post(endpoint, "", "采购电脑", Map.of());
        if (endpoint.accept().equals(MediaType.APPLICATION_JSON)) response.expectStatus().is5xxServerError();
        else response.expectStatus().isOk().expectBody().consumeWith(result ->
                assertTrue(new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains("transport_error")));
        verify(runtime).run(any(), argThat(profile -> PROFILE.equals(profile.name())), any());
        verifyNoMoreInteractions(runtime);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"general-agent-v1", "main-agent", ""})
    void historicalRunsRemainReadableButBothResumeEndpointsReject(String profileName) {
        var profile = profileName.isEmpty() ? null : new AgentExecutionProfile(profileName, "legacy",
                Set.of(), new ProcurementSourcingExecutionProfileFactory().createProfile().limits(), false);
        var record = AgentRunRecord.create("old-run", "trace", "legacy-session",
                new AgentRequest("legacy-session", "trusted-user", "old", Map.of(), profileName), profile, null);
        when(runs.find("old-run")).thenReturn(Optional.of(record));
        when(runs.recent(20)).thenReturn(List.of(record));
        when(timeline.loadEventsAfter("old-run", -1, 500)).thenReturn(List.of());
        client.get().uri("/api/agent/runs").exchange().expectStatus().isOk();
        client.get().uri("/api/agent/runs/old-run").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.runId").isEqualTo("old-run");
        client.get().uri("/api/agent/runs/old-run/events").exchange().expectStatus().isOk();
        client.post().uri("/api/agent/runs/old-run/resume").exchange().expectStatus().isEqualTo(410);
        client.post().uri("/api/agent/runs/old-run/resume/events").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isEqualTo(410);
        assertSame(profile, record.executionProfile());
        verifyNoInteractions(runtime, store, limits);
    }

    @Test
    void ownedProcurementResumeKeepsProfileAndRejectsForeignIdentity() {
        var request = policy.initializeCase(policy.authorize(new AgentRequest("resume-session", "spoof", "采购", Map.of())));
        var record = AgentRunRecord.create("p-run", "trace", request.conversationId(), request,
                new ProcurementSourcingExecutionProfileFactory().createProfile(), null);
        when(runs.find("p-run")).thenReturn(Optional.of(record));
        var result = new AgentRuntimeResult("p-run", request.conversationId(), AgentRunState.COMPLETED,
                AgentStopReason.COMPLETED, "采购回答", "", null, List.of());
        when(runtime.resume("p-run")).thenReturn(result);
        when(runtime.resume(eq("p-run"), any())).thenReturn(result);
        client.post().uri("/api/agent/runs/p-run/resume").exchange().expectStatus().isOk();
        client.post().uri("/api/agent/runs/p-run/resume/events").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk().expectBody().returnResult();
        for (var foreign : List.of(new AuthenticatedPrincipal("tenant", "other-user", Set.of("USER")),
                new AuthenticatedPrincipal("other-tenant", "trusted-user", Set.of("USER")))) {
            when(principals.current()).thenReturn(foreign);
            client.post().uri("/api/agent/runs/p-run/resume").exchange().expectStatus().isForbidden();
            client.post().uri("/api/agent/runs/p-run/resume/events").accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange().expectStatus().isForbidden();
        }
        verify(runtime).resume("p-run");
        verify(runtime).resume(eq("p-run"), any());
        verifyNoMoreInteractions(runtime);
    }

    @Test
    void publicMultiAgentCannotStartGenericOrchestrator() {
        var orchestrator = mock(MultiAgentOrchestrator.class);
        WebTestClient.bindToController(new MultiAgentController(orchestrator))
                .controllerAdvice(new GlobalExceptionHandler()).build().post().uri("/api/agent/multi-agent/runs")
                .bodyValue(new AgentRequest("c", "u", "你好", Map.of(), PROFILE)).exchange()
                .expectStatus().isBadRequest();
        verifyNoInteractions(orchestrator);
    }
}
