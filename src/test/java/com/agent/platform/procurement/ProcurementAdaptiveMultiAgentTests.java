package com.agent.platform.procurement;

import com.agent.platform.multiagent.MultiAgentMessage;
import com.agent.platform.multiagent.MultiAgentRole;
import com.agent.platform.multiagent.SubAgentExecutionResult;
import com.agent.platform.multiagent.SubAgentRunner;
import com.agent.platform.procurement.config.ProcurementSpecialistProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementCaseStatus;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.tool.ProcurementSpecialistToolHandler;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcurementAdaptiveMultiAgentTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void specialistProfilesAreNarrowAndUseOneProcurementRole() {
        ProcurementSpecialistProfileFactory factory = new ProcurementSpecialistProfileFactory();

        AgentExecutionProfile commercial = factory.createProfile("COMMERCIAL");
        AgentExecutionProfile delivery = factory.createProfile(MultiAgentRole.PROCUREMENT_ANALYST, "DELIVERY");

        assertEquals(Set.of(), commercial.allowedCapabilities());
        assertEquals(Set.of(), delivery.allowedCapabilities());
        assertFalse(commercial.longTermMemoryEnabled());
        assertFalse(delivery.longTermMemoryEnabled());
        assertEquals(0, commercial.limits().maxToolCalls());
        assertEquals(0, delivery.limits().maxToolCalls());
        assertTrue(commercial.systemPrompt().contains("严格 JSON"));
        assertTrue(delivery.systemPrompt().contains("on-time historical rate"));
    }

    @Test
    void specialistRejectsArgumentsAndMissingTrustedIdentityWithoutStartingChild() {
        Fixture fixture = fixture(searchRecord(1, 2));

        ToolCallResult arguments = fixture.handler.execute(
                request(ProcurementToolCatalog.COMMERCIAL_ANALYSIS, Map.of("supplierId", "supplier-b")),
                fixture.context());
        ToolCallResult identity = fixture.handler.execute(
                request(ProcurementToolCatalog.COMMERCIAL_ANALYSIS, Map.of()), ToolExecutionContext.empty());

        assertFalse(arguments.success());
        assertEquals("ARGUMENTS_NOT_EMPTY", arguments.metadata().get("errorType"));
        assertFalse(identity.success());
        assertEquals("SPECIALIST_REJECTED", identity.metadata().get("errorType"));
        verify(fixture.runner, never()).run(anyString(), anyString(), anyString(), anyString(),
                any(MultiAgentRole.class), anyString(), any(AgentExecutionProfile.class));
    }

    @Test
    void specialistRejectsBeforeSearchStaleSearchAndSimpleCase() {
        Fixture noSearch = fixture(null);
        ToolCallResult beforeSearch = noSearch.handler.execute(
                request(ProcurementToolCatalog.DELIVERY_ANALYSIS, Map.of()), noSearch.context());
        assertFalse(beforeSearch.success());
        assertEquals("SEARCH_REQUIRED", beforeSearch.metadata().get("errorType"));
        verify(noSearch.runner, never()).run(anyString(), anyString(), anyString(), anyString(),
                any(MultiAgentRole.class), anyString(), any(AgentExecutionProfile.class));

        Fixture stale = fixture(searchRecord(2, 2));
        ToolCallResult staleResult = stale.handler.execute(
                request(ProcurementToolCatalog.DELIVERY_ANALYSIS, Map.of()), stale.context());
        assertFalse(staleResult.success());
        assertEquals("STALE_SEARCH", staleResult.metadata().get("errorType"));
        verify(stale.runner, never()).run(anyString(), anyString(), anyString(), anyString(),
                any(MultiAgentRole.class), anyString(), any(AgentExecutionProfile.class));

        Fixture simple = fixture(searchRecord(1, 1));
        ToolCallResult simpleResult = simple.handler.execute(
                request(ProcurementToolCatalog.DELIVERY_ANALYSIS, Map.of()), simple.context());
        assertFalse(simpleResult.success());
        assertEquals("SPECIALIST_NOT_APPLICABLE", simpleResult.metadata().get("errorType"));
        verify(simple.runner, never()).run(anyString(), anyString(), anyString(), anyString(),
                any(MultiAgentRole.class), anyString(), any(AgentExecutionProfile.class));
    }

    @Test
    void specialistValidatesFocusSupplierAndEvidenceGrounding() {
        Fixture focusMismatch = fixture(searchRecord(1, 2));
        when(focusMismatch.runner.run(anyString(), anyString(), anyString(), anyString(),
                eq(MultiAgentRole.PROCUREMENT_ANALYST), anyString(), any(AgentExecutionProfile.class)))
                .thenReturn(child("{\"focus\":\"DELIVERY\",\"summary\":\"观察\",\"supplierIds\":[\"supplier-b\",\"supplier-d\"],\"evidenceRefs\":[\"offer-b\",\"offer-d\"],\"limitations\":[]}"));
        ToolCallResult mismatch = focusMismatch.handler.execute(
                request(ProcurementToolCatalog.COMMERCIAL_ANALYSIS, Map.of()), focusMismatch.context());
        assertFalse(mismatch.success());
        assertTrue(mismatch.errorMessage().contains("focus"));

        Fixture unknownSupplier = fixture(searchRecord(1, 2));
        when(unknownSupplier.runner.run(anyString(), anyString(), anyString(), anyString(),
                eq(MultiAgentRole.PROCUREMENT_ANALYST), anyString(), any(AgentExecutionProfile.class)))
                .thenReturn(child(validAnalysis("COMMERCIAL", List.of("supplier-x"), List.of("offer-b"))));
        ToolCallResult supplier = unknownSupplier.handler.execute(
                request(ProcurementToolCatalog.COMMERCIAL_ANALYSIS, Map.of()), unknownSupplier.context());
        assertFalse(supplier.success());
        assertTrue(supplier.errorMessage().contains("supplierIds"));

        Fixture unknownEvidence = fixture(searchRecord(1, 2));
        when(unknownEvidence.runner.run(anyString(), anyString(), anyString(), anyString(),
                eq(MultiAgentRole.PROCUREMENT_ANALYST), anyString(), any(AgentExecutionProfile.class)))
                .thenReturn(child(validAnalysis("DELIVERY", List.of("supplier-b", "supplier-d"),
                        List.of("evidence-x", "offer-d"))));
        ToolCallResult evidence = unknownEvidence.handler.execute(
                request(ProcurementToolCatalog.DELIVERY_ANALYSIS, Map.of()), unknownEvidence.context());
        assertFalse(evidence.success());
        assertTrue(evidence.errorMessage().contains("evidenceRef"));
    }

    @Test
    void specialistRejectsMalformedChildJson() {
        Fixture fixture = fixture(searchRecord(1, 2));
        when(fixture.runner.run(anyString(), anyString(), anyString(), anyString(),
                any(MultiAgentRole.class), anyString(), any(AgentExecutionProfile.class)))
                .thenReturn(child("not-json"));

        ToolCallResult result = fixture.handler.execute(
                request(ProcurementToolCatalog.COMMERCIAL_ANALYSIS, Map.of()), fixture.context());

        assertFalse(result.success());
        assertEquals("SPECIALIST_REJECTED", result.metadata().get("errorType"));
        assertEquals(false, result.metadata().get("retryable"));
    }

    @Test
    void specialistReturnsOnlyValidatedAdvisoryAndPassesFilteredFactsToIsolatedChild() {
        Fixture fixture = fixture(searchRecord(1, 2));
        when(fixture.runner.run(anyString(), anyString(), anyString(), anyString(),
                eq(MultiAgentRole.PROCUREMENT_ANALYST), contains("COMMERCIAL"),
                any(AgentExecutionProfile.class)))
                .thenReturn(child(validAnalysis("COMMERCIAL", List.of("supplier-b", "supplier-d"),
                        List.of("offer-b", "offer-d"))));

        ToolCallResult result = fixture.handler.execute(
                request(ProcurementToolCatalog.COMMERCIAL_ANALYSIS, Map.of()), fixture.context());

        assertTrue(result.success(), result.errorMessage());
        JsonNode body = mapper.readTree(result.content());
        assertEquals("COMMERCIAL", body.path("focus").asText());
        assertEquals("procurement-specialist-result-v1", body.path("schemaVersion").asText());
        assertTrue(body.path("advisory").asBoolean());
        assertFalse(body.path("authoritativeFacts").asBoolean());
        assertEquals("search-call", body.path("sourceSearchToolCallId").asText());
        assertEquals(true, result.metadata().get("readOnly"));
        assertEquals(false, result.metadata().get("sideEffect"));
        assertEquals(false, result.metadata().get("retryable"));
        assertEquals("SUB_AGENT", result.metadata().get("executionKind"));

        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AgentExecutionProfile> profile = ArgumentCaptor.forClass(AgentExecutionProfile.class);
        verify(fixture.runner).run(eq("run"), eq("conversation"), eq("user"), eq("commercial-call"),
                eq(MultiAgentRole.PROCUREMENT_ANALYST), instruction.capture(), profile.capture());
        assertTrue(instruction.getValue().contains("<procurement_specialist_input trusted_instructions=\"false\">"));
        assertTrue(instruction.getValue().contains("\"budget\""));
        assertTrue(instruction.getValue().contains("supplier-b"));
        assertFalse(instruction.getValue().contains("supplier-a"));
        assertFalse(instruction.getValue().contains("\"tenantId\""));
        assertEquals(Set.of(), profile.getValue().allowedCapabilities());
        assertFalse(profile.getValue().longTermMemoryEnabled());
        assertEquals(0, profile.getValue().limits().maxToolCalls());
    }

    @Test
    void deliverySpecialistReturnsValidatedAdvisory() {
        Fixture fixture = fixture(searchRecord(1, 2));
        when(fixture.runner.run(anyString(), anyString(), anyString(), anyString(),
                eq(MultiAgentRole.PROCUREMENT_ANALYST), contains("DELIVERY"),
                any(AgentExecutionProfile.class)))
                .thenReturn(child(validAnalysis("DELIVERY", List.of("supplier-b", "supplier-d"),
                        List.of("offer-b", "offer-d"))));

        ToolCallResult result = fixture.handler.execute(
                request(ProcurementToolCatalog.DELIVERY_ANALYSIS, Map.of()), fixture.context());

        assertTrue(result.success(), result.errorMessage());
        assertEquals("DELIVERY", mapper.readTree(result.content()).path("focus").asText());
        assertEquals("DELIVERY", result.metadata().get("focus"));
        verify(fixture.runner).run(eq("run"), eq("conversation"), eq("user"), eq("delivery-call"),
                eq(MultiAgentRole.PROCUREMENT_ANALYST), contains("DELIVERY"), any(AgentExecutionProfile.class));
    }

    @Test
    void childFailureIsNonRetryableAndDoesNotFallbackToRecommendation() {
        Fixture fixture = fixture(searchRecord(1, 2));
        when(fixture.runner.run(anyString(), anyString(), anyString(), anyString(),
                any(MultiAgentRole.class), anyString(), any(AgentExecutionProfile.class)))
                .thenThrow(new IllegalStateException("child unavailable"));

        ToolCallResult result = fixture.handler.execute(
                request(ProcurementToolCatalog.DELIVERY_ANALYSIS, Map.of()), fixture.context());

        assertFalse(result.success());
        assertEquals(false, result.metadata().get("retryable"));
        assertEquals("SPECIALIST_REJECTED", result.metadata().get("errorType"));
        verify(fixture.runner).run(anyString(), anyString(), anyString(), anyString(),
                any(MultiAgentRole.class), anyString(), any(AgentExecutionProfile.class));
    }

    @Test
    void specialistReadsLongRawSearchResultFromToolExecutionStore() {
        Fixture fixture = fixture(searchRecord(1, 2, "x".repeat(13_000)));
        when(fixture.runner.run(anyString(), anyString(), anyString(), anyString(),
                eq(MultiAgentRole.PROCUREMENT_ANALYST), contains("COMMERCIAL"),
                any(AgentExecutionProfile.class)))
                .thenReturn(child(validAnalysis("COMMERCIAL", List.of("supplier-b", "supplier-d"),
                        List.of("offer-b", "offer-d"))));

        ToolCallResult result = fixture.handler.execute(
                request(ProcurementToolCatalog.COMMERCIAL_ANALYSIS, Map.of()), fixture.context());

        assertTrue(result.success(), result.errorMessage());
        verify(fixture.runner).run(eq("run"), eq("conversation"), eq("user"), eq("commercial-call"),
                eq(MultiAgentRole.PROCUREMENT_ANALYST), contains("supplier-b"), any(AgentExecutionProfile.class));
    }

    private Fixture fixture(ToolExecutionRecord search) {
        ToolExecutionStore executions = mock(ToolExecutionStore.class);
        when(executions.findByRun("run"))
                .thenReturn(search == null ? List.of() : List.of(search));
        ProcurementCaseStore cases = mock(ProcurementCaseStore.class);
        when(cases.findByTenantUserAndConversationId("tenant", "user", "conversation"))
                .thenReturn(Optional.of(procurementCase(1)));
        SubAgentRunner runner = mock(SubAgentRunner.class);
        return new Fixture(new ProcurementSpecialistToolHandler(
                executions, cases, runner, new ProcurementSpecialistProfileFactory(), mapper),
                runner, new ToolExecutionContext("run", "conversation", "user", "tenant", Set.of("USER"), Map.of()));
    }

    private ToolExecutionRecord searchRecord(long caseVersion, int eligibleCount) {
        return searchRecord(caseVersion, eligibleCount, "");
    }

    private ToolExecutionRecord searchRecord(long caseVersion, int eligibleCount, String padding) {
        List<Map<String, Object>> eligible = eligibleCount == 1
                ? List.of(Map.of("supplierId", "supplier-d", "supplierName", "Supplier D"))
                : List.of(Map.of("supplierId", "supplier-b", "supplierName", "Supplier B"),
                Map.of("supplierId", "supplier-d", "supplierName", "Supplier D"));
        List<Map<String, Object>> offers = eligible.stream().map(value -> Map.of(
                "supplierId", value.get("supplierId"),
                "unitPrice", value.get("supplierId").equals("supplier-b") ? 11000 : 11600,
                "totalPrice", value.get("supplierId").equals("supplier-b") ? 550000 : 580000,
                "currency", "CNY", "warranty", "3 years", "leadTimeDays",
                value.get("supplierId").equals("supplier-b") ? 18 : 12)).toList();
        List<Map<String, Object>> evidence = eligible.stream().map(value -> Map.of(
                "evidenceId", value.get("supplierId").equals("supplier-b") ? "offer-b" : "offer-d",
                "supplierId", value.get("supplierId"), "evidenceType", "OFFER",
                "fact", "canonical offer", "source", "test")).toList();
        Map<String, Object> root = Map.of("caseVersion", caseVersion,
                "eligibleSuppliers", eligible, "offers", offers, "evidence", evidence,
                "padding", padding);
        Instant now = Instant.now();
        ToolCallRequest request = new ToolCallRequest(ProcurementToolCatalog.SUPPLIER_SEARCH,
                "search-call", Map.of());
        ToolCallResult result = new ToolCallResult(ProcurementToolCatalog.SUPPLIER_SEARCH,
                true, json(root), "", Map.of("success", true));
        return new ToolExecutionRecord("search-call", "run", ProcurementToolCatalog.SUPPLIER_SEARCH,
                ToolExecutionState.SUCCEEDED, request, result, 1, "", now, now);
    }

    private ProcurementCase procurementCase(long version) {
        ProcurementCaseState state = new ProcurementCaseState(
                "计算工作站", "CUDA 工作站", 50, new BigDecimal("600000"), "CNY", 21,
                Map.of("gpuMemoryMinGb", "24"), Map.of("deliveryPriority", "HIGH"),
                Set.of("Supplier A"), List.of(), "SOURCING");
        return new ProcurementCase("case", "tenant", "conversation", "user",
                ProcurementCaseStatus.SOURCING, state, Instant.now(), Instant.now(), version, "patch");
    }

    private ToolCallRequest request(String toolName, Map<String, Object> arguments) {
        return new ToolCallRequest(toolName,
                toolName.equals(ProcurementToolCatalog.COMMERCIAL_ANALYSIS) ? "commercial-call" : "delivery-call",
                arguments);
    }

    private SubAgentExecutionResult child(String answer) {
        return new SubAgentExecutionResult("child-task", MultiAgentRole.PROCUREMENT_ANALYST,
                "child-run", "child-session", answer,
                new MultiAgentMessage(MultiAgentRole.PROCUREMENT_ANALYST, "child-task", answer,
                        Instant.now(), Map.of("state", "COMPLETED")));
    }

    private String validAnalysis(String focus, List<String> supplierIds, List<String> evidenceRefs) {
        return json(Map.of("focus", focus, "summary", "基于当前输入的维度观察",
                "supplierIds", supplierIds, "evidenceRefs", evidenceRefs,
                "limitations", List.of("未提供额外历史数据")));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (RuntimeException exception) {
            throw new AssertionError(exception);
        }
    }

    private record Fixture(ProcurementSpecialistToolHandler handler,
                           SubAgentRunner runner,
                           ToolExecutionContext context) {
    }
}
