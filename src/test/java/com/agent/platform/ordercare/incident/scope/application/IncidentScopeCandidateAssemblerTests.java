package com.agent.platform.ordercare.incident.scope.application;

import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.scope.client.FlowOrderOrderCandidates;
import com.agent.platform.ordercare.incident.scope.client.FlowOrderResourceEnrichment;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeAnomalyType;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeRelationQuality;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentScopeCandidateAssemblerTests {

    private final IncidentScopeDigests digests = new IncidentScopeDigests(new ObjectMapper());
    private final IncidentScopeCandidateAssembler assembler =
            new IncidentScopeCandidateAssembler(digests, new OrderCareProperties());

    @Test
    void assemblesOnlyAuthoritativeUnreleasedCandidatesWithStrongIdentifiers() {
        IncidentScopeAssemblyResult result = assembler.assemble(criteria(), orders(), resources("STRONG"));

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.requestId()).isEqualTo("REQ-1");
            assertThat(candidate.releaseState()).isEqualTo("UNRELEASED");
            assertThat(candidate.queueNames()).containsExactly("floworder.order.state.dlq");
            assertThat(candidate.relationQuality()).isEqualTo(IncidentScopeRelationQuality.STRONG);
            assertThat(candidate.identifiers()).allSatisfy(identifier ->
                    assertThat(identifier.resolutionSource())
                            .isEqualTo("SERVER_RESOLVED_FROM_SCOPE_DISCOVERY"));
        });
    }

    @Test
    void fingerprintIsStableAcrossInputOrdering() {
        IncidentScopeAssemblyResult first = assembler.assemble(criteria(), orders(), resources("STRONG"));
        IncidentScopeAssemblyResult second = assembler.assemble(criteria(), orders(), resources("STRONG"));

        assertThat(second.candidateFingerprint()).isEqualTo(first.candidateFingerprint());
        assertThat(first.candidateFingerprint()).hasSize(64);
    }

    @Test
    void releasedInventoryIsExcludedFromInventoryAnomaly() {
        FlowOrderResourceEnrichment original = resources("MISSING");
        FlowOrderResourceEnrichment.Item item = original.items().get(0);
        FlowOrderResourceEnrichment released = new FlowOrderResourceEnrichment(
                original.discoveryRequestId(), original.observedAt(),
                List.of(new FlowOrderResourceEnrichment.Item(
                        item.requestId(), item.orderNo(), item.deductNo(), item.reservationStatus(),
                        30, "RELEASED", item.stockItemId(), item.stockAvailable(), item.stockLocked(),
                        item.anomalyTypes(), List.of(), "MISSING", "COMPLETE", item.sourceReferences())),
                List.of(), original.sourceHealth());

        assertThat(assembler.assemble(criteria(), orders(), released).candidates()).isEmpty();
    }

    private IncidentScopeCriteria criteria() {
        return new IncidentScopeCriteria("昨晚", Instant.parse("2026-07-20T10:00:00Z"),
                Instant.parse("2026-07-20T22:00:00Z"), "Asia/Shanghai", false,
                List.of(IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED),
                List.of(), List.of(), List.of());
    }

    private FlowOrderOrderCandidates orders() {
        LocalDateTime observed = LocalDateTime.of(2026, 7, 20, 23, 0);
        return new FlowOrderOrderCandidates("discovery-1", observed, List.of(
                new FlowOrderOrderCandidates.Candidate(
                        "REQ-1", "ORDER-1", "DEDUCT-1", 40, null, observed,
                        List.of(IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED),
                        List.of(new FlowOrderOrderCandidates.SourceReference(
                                "floworder-order-service", "fo_reservation_order", "1", observed)))),
                1, false, "");
    }

    private FlowOrderResourceEnrichment resources(String relation) {
        LocalDateTime observed = LocalDateTime.of(2026, 7, 20, 23, 1);
        FlowOrderResourceEnrichment.SourceReference source = new FlowOrderResourceEnrichment.SourceReference(
                "floworder-resource-service", "fo_stock_deduct_record", "2", observed);
        FlowOrderResourceEnrichment.DeadLetter dead = new FlowOrderResourceEnrichment.DeadLetter(
                3L, "MSG-1", "floworder.order.state.dlq", "floworder.order.state.exchange",
                "order.state.changed", "ORDER_TIMEOUT", 0, relation, observed, List.of(source));
        return new FlowOrderResourceEnrichment("discovery-1", observed, List.of(
                new FlowOrderResourceEnrichment.Item(
                        "REQ-1", "ORDER-1", "DEDUCT-1", 20, 20, "UNRELEASED",
                        10L, 9, 1,
                        List.of(IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED),
                        List.of(dead), relation, "COMPLETE", List.of(source))),
                List.of("floworder.order.state.dlq"), Map.of("inventory", "AVAILABLE"));
    }
}
