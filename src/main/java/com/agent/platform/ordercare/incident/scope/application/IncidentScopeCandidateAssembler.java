package com.agent.platform.ordercare.incident.scope.application;

import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.scope.client.FlowOrderOrderCandidates;
import com.agent.platform.ordercare.incident.scope.client.FlowOrderResourceEnrichment;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeAnomalyType;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCandidate;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeIdentifier;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeRelationQuality;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSourceReference;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

@Component
public class IncidentScopeCandidateAssembler {

    public static final String RESOLUTION_SOURCE = "SERVER_RESOLVED_FROM_SCOPE_DISCOVERY";

    private final IncidentScopeDigests digests;
    private final ZoneId flowOrderZone;

    public IncidentScopeCandidateAssembler(IncidentScopeDigests digests, OrderCareProperties properties) {
        this.digests = digests;
        this.flowOrderZone = ZoneId.of(properties.getIncidentScopeDefaultTimezone());
    }

    public IncidentScopeAssemblyResult assemble(IncidentScopeCriteria criteria,
                                                FlowOrderOrderCandidates orders,
                                                FlowOrderResourceEnrichment enrichment) {
        Map<String, FlowOrderOrderCandidates.Candidate> orderByRequest = new LinkedHashMap<>();
        for (FlowOrderOrderCandidates.Candidate order : orders.candidates()) {
            orderByRequest.putIfAbsent(blank(order.requestId()), order);
        }
        Map<String, FlowOrderResourceEnrichment.Item> resourceByRequest = new LinkedHashMap<>();
        Map<String, FlowOrderResourceEnrichment.Item> resourceByDeduct = new LinkedHashMap<>();
        for (FlowOrderResourceEnrichment.Item item : enrichment.items()) {
            if (!blank(item.requestId()).isEmpty()) resourceByRequest.putIfAbsent(item.requestId(), item);
            if (!blank(item.deductNo()).isEmpty()) resourceByDeduct.putIfAbsent(item.deductNo(), item);
        }

        TreeSet<String> keys = new TreeSet<>();
        orderByRequest.keySet().stream().filter(value -> !value.isEmpty()).map(value -> "R|" + value).forEach(keys::add);
        resourceByRequest.keySet().stream().filter(value -> !value.isEmpty()).map(value -> "R|" + value).forEach(keys::add);
        resourceByDeduct.keySet().stream().filter(value -> !value.isEmpty()).map(value -> "D|" + value).forEach(keys::add);

        List<IncidentScopeCandidate> candidates = new ArrayList<>();
        for (String key : keys) {
            boolean requestKey = key.startsWith("R|");
            String value = key.substring(2);
            FlowOrderOrderCandidates.Candidate order = requestKey ? orderByRequest.get(value) : null;
            FlowOrderResourceEnrichment.Item resource = requestKey
                    ? resourceByRequest.get(value) : resourceByDeduct.get(value);
            if (resource == null && order != null) {
                resource = resourceByDeduct.get(blank(order.deductNo()));
            }
            if (!included(criteria, order, resource)) continue;
            IncidentScopeCandidate candidate = candidate(criteria, order, resource);
            if (candidates.stream().noneMatch(existing -> same(existing, candidate))) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparing(IncidentScopeCandidate::requestId)
                .thenComparing(IncidentScopeCandidate::orderNo)
                .thenComparing(IncidentScopeCandidate::deductNo));
        List<IncidentScopeCandidate> immutable = List.copyOf(candidates);
        return new IncidentScopeAssemblyResult(
                immutable,
                enrichment.sourceHealth(),
                orders.truncated(),
                digests.candidateFingerprint(immutable));
    }

    private boolean included(IncidentScopeCriteria criteria,
                             FlowOrderOrderCandidates.Candidate order,
                             FlowOrderResourceEnrichment.Item resource) {
        boolean inventoryAnomaly = criteria.anomalyTypes().contains(
                IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED)
                || criteria.anomalyTypes().contains(
                IncidentScopeAnomalyType.ORDER_CANCELLED_INVENTORY_UNRELEASED);
        if (inventoryAnomaly && (resource == null || !"UNRELEASED".equals(resource.releaseState()))) {
            return false;
        }
        if (criteria.anomalyTypes().contains(IncidentScopeAnomalyType.DEAD_LETTER_PENDING)
                && (resource == null || resource.deadLetters() == null || resource.deadLetters().isEmpty())) {
            return false;
        }
        return order != null || resource != null;
    }

    private IncidentScopeCandidate candidate(IncidentScopeCriteria criteria,
                                             FlowOrderOrderCandidates.Candidate order,
                                             FlowOrderResourceEnrichment.Item resource) {
        String requestId = first(resource == null ? null : resource.requestId(), order == null ? null : order.requestId());
        String orderNo = first(resource == null ? null : resource.orderNo(), order == null ? null : order.orderNo());
        String deductNo = first(resource == null ? null : resource.deductNo(), order == null ? null : order.deductNo());
        List<FlowOrderResourceEnrichment.DeadLetter> deadLetters = resource == null || resource.deadLetters() == null
                ? List.of() : resource.deadLetters();
        List<String> deadLetterIds = deadLetters.stream().map(FlowOrderResourceEnrichment.DeadLetter::deadLetterId)
                .filter(Objects::nonNull).map(String::valueOf).sorted().toList();
        List<String> queues = deadLetters.stream().map(FlowOrderResourceEnrichment.DeadLetter::deadQueue)
                .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
        List<IncidentScopeSourceReference> provenance = new ArrayList<>();
        if (order != null && order.sourceReferences() != null) {
            order.sourceReferences().forEach(reference -> provenance.add(reference(reference)));
        }
        if (resource != null && resource.sourceReferences() != null) {
            resource.sourceReferences().forEach(reference -> provenance.add(reference(reference)));
        }
        deadLetters.forEach(dead -> {
            if (dead.sourceReferences() != null) {
                dead.sourceReferences().forEach(reference -> provenance.add(reference(reference)));
            }
        });
        List<IncidentScopeIdentifier> identifiers = identifiers(
                requestId, orderNo, deductNo, deadLetters, provenance);
        List<String> reasons = new ArrayList<>();
        if (order != null) reasons.add("ORDER_CANDIDATE_MATCHED");
        if (resource != null && "UNRELEASED".equals(resource.releaseState())) reasons.add("INVENTORY_UNRELEASED");
        if (!deadLetters.isEmpty()) reasons.add("PERSISTED_DEAD_LETTER_FOUND");
        return new IncidentScopeCandidate(
                requestId, orderNo, deductNo, deadLetterIds, queues,
                order == null ? null : order.orderStatus(),
                resource == null ? order == null ? null : order.reservationStatus() : resource.reservationStatus(),
                resource == null ? null : resource.deductStatus(),
                resource == null ? "UNKNOWN" : resource.releaseState(),
                criteria.anomalyTypes(), List.copyOf(reasons),
                relation(resource == null ? null : resource.relationQuality()),
                resource == null ? "MISSING_RESOURCE_FACT" : blank(resource.completeness()),
                identifiers, List.copyOf(provenance));
    }

    private List<IncidentScopeIdentifier> identifiers(
            String requestId, String orderNo, String deductNo,
            List<FlowOrderResourceEnrichment.DeadLetter> deadLetters,
            List<IncidentScopeSourceReference> provenance) {
        IncidentScopeSourceReference fallback = provenance.isEmpty() ? null : provenance.get(0);
        List<IncidentScopeIdentifier> result = new ArrayList<>();
        addIdentifier(result, "REQUEST_ID", requestId, fallback);
        addIdentifier(result, "ORDER_NO", orderNo, fallback);
        addIdentifier(result, "DEDUCT_NO", deductNo, fallback);
        for (FlowOrderResourceEnrichment.DeadLetter dead : deadLetters) {
            IncidentScopeSourceReference source = dead.sourceReferences() == null || dead.sourceReferences().isEmpty()
                    ? fallback : reference(dead.sourceReferences().get(0));
            addIdentifier(result, "DEAD_LETTER_ID",
                    dead.deadLetterId() == null ? "" : String.valueOf(dead.deadLetterId()), source);
            addIdentifier(result, "QUEUE_NAME", dead.deadQueue(), source);
        }
        result.sort(Comparator.comparing(IncidentScopeIdentifier::identifierType)
                .thenComparing(IncidentScopeIdentifier::value));
        return List.copyOf(result);
    }

    private void addIdentifier(List<IncidentScopeIdentifier> result,
                               String type,
                               String value,
                               IncidentScopeSourceReference source) {
        if (value == null || value.isBlank()) return;
        result.add(new IncidentScopeIdentifier(type, value.trim(),
                source == null ? "" : source.sourceSystem(),
                source == null ? "" : source.sourceType(),
                source == null ? "" : source.sourceId(),
                source == null ? null : source.observedAt(), RESOLUTION_SOURCE));
    }

    private IncidentScopeSourceReference reference(FlowOrderOrderCandidates.SourceReference value) {
        return new IncidentScopeSourceReference(value.sourceSystem(), value.sourceType(), value.sourceId(),
                instant(value.observedAt()));
    }

    private IncidentScopeSourceReference reference(FlowOrderResourceEnrichment.SourceReference value) {
        return new IncidentScopeSourceReference(value.sourceSystem(), value.sourceType(), value.sourceId(),
                instant(value.observedAt()));
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(flowOrderZone).toInstant();
    }

    private IncidentScopeRelationQuality relation(String value) {
        try {
            return IncidentScopeRelationQuality.valueOf(blank(value));
        } catch (RuntimeException ignored) {
            return IncidentScopeRelationQuality.MISSING;
        }
    }

    private boolean same(IncidentScopeCandidate left, IncidentScopeCandidate right) {
        return left.requestId().equals(right.requestId()) && left.deductNo().equals(right.deductNo());
    }

    private String first(String left, String right) {
        return left != null && !left.isBlank() ? left.trim() : blank(right);
    }

    private String blank(String value) { return value == null ? "" : value.trim(); }
}
