package com.agent.platform.ordercare.incident.scope.application;

import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.scope.client.FlowOrderOrderCandidates;
import com.agent.platform.ordercare.incident.scope.client.FlowOrderResourceEnrichment;
import com.agent.platform.ordercare.incident.scope.client.FlowOrderScopeDiscoveryClient;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshot;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshotStatus;
import com.agent.platform.ordercare.incident.scope.persistence.IncidentScopeClaim;
import com.agent.platform.ordercare.incident.scope.persistence.IncidentScopeDiscoveryStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class IncidentScopeDiscoveryCoordinator {

    private final IncidentTimeRangeResolver timeRangeResolver;
    private final IncidentScopePolicy policy;
    private final IncidentScopeDigests digests;
    private final IncidentScopeCandidateAssembler assembler;
    private final IncidentScopeDiscoveryStore store;
    private final FlowOrderScopeDiscoveryClient client;
    private final OrderCareProperties properties;

    public IncidentScopeDiscoveryCoordinator(IncidentTimeRangeResolver timeRangeResolver,
                                             IncidentScopePolicy policy,
                                             IncidentScopeDigests digests,
                                             IncidentScopeCandidateAssembler assembler,
                                             IncidentScopeDiscoveryStore store,
                                             FlowOrderScopeDiscoveryClient client,
                                             OrderCareProperties properties) {
        this.timeRangeResolver = timeRangeResolver;
        this.policy = policy;
        this.digests = digests;
        this.assembler = assembler;
        this.store = store;
        this.client = client;
        this.properties = properties;
    }

    public IncidentScopeSnapshot discover(AuthenticatedPrincipal principal,
                                          IncidentScopeDiscoveryCommand command) {
        require(principal, command);
        IncidentScopeCriteria criteria = criteria(command);
        policy.validateCriteria(criteria);
        Instant now = Instant.now();
        IncidentScopeSnapshot requested = new IncidentScopeSnapshot(
                "scope-" + UUID.randomUUID(), principal.tenantId(), principal.principalId(),
                command.conversationId(), command.workItemId(), command.sourceInputId(),
                command.discoveryRequestId(), criteria, digests.criteriaDigest(criteria),
                List.of(), Map.of(), "", 0, false, IncidentScopeSnapshotStatus.NEW,
                0, "", null, 0,
                now.plus(Duration.ofMinutes(properties.getIncidentScopeSnapshotTtlMinutes())),
                null, "", "", now, now);
        IncidentScopeSnapshot stored = store.createOrLoad(requested);
        IncidentScopeClaim claim = store.claim(principal, command.discoveryRequestId(),
                instanceId(), Duration.ofSeconds(properties.getIncidentScopeLeaseSeconds()));
        if (!claim.acquired()) {
            return claim.snapshot();
        }
        IncidentScopeSnapshot claimed = claim.snapshot();
        try {
            FlowOrderOrderCandidates orders = queryOrders(criteria, command);
            List<String> requestIds = orders.candidates().stream()
                    .map(FlowOrderOrderCandidates.Candidate::requestId)
                    .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
            TreeSet<String> deductNos = new TreeSet<>(criteria.deductNos());
            orders.candidates().stream().map(FlowOrderOrderCandidates.Candidate::deductNo)
                    .filter(value -> value != null && !value.isBlank()).forEach(deductNos::add);
            FlowOrderResourceEnrichment enrichment = requestIds.isEmpty() && deductNos.isEmpty()
                    ? new FlowOrderResourceEnrichment(command.discoveryRequestId(), null,
                    List.of(), List.of(), Map.of("resource", "NOT_QUERIED"))
                    : client.enrichResources(command.discoveryRequestId(), requestIds,
                    List.copyOf(deductNos), criteria, command.traceId());
            IncidentScopeAssemblyResult assembled = assembler.assemble(criteria, orders, enrichment);
            policy.validateCandidateCount(assembled.candidates().size(), assembled.truncated());
            return store.complete(principal, stored.snapshotId(), claimed.leaseOwner(),
                    claimed.fencingToken(), assembled.candidates(), assembled.sourceHealth(),
                    assembled.candidateFingerprint(), assembled.truncated());
        } catch (RuntimeException exception) {
            try {
                store.fail(principal, stored.snapshotId(), claimed.leaseOwner(), claimed.fencingToken(),
                        safeFailureCode(exception));
            } catch (RuntimeException ignored) {
                // A concurrent lease takeover is authoritative; preserve the original discovery failure.
            }
            throw exception;
        }
    }

    private FlowOrderOrderCandidates queryOrders(IncidentScopeCriteria criteria,
                                                  IncidentScopeDiscoveryCommand command) {
        if (criteria.startTime() == null && criteria.orderNos().isEmpty()) {
            return new FlowOrderOrderCandidates(command.discoveryRequestId(), null,
                    List.of(), 0, false, "");
        }
        return client.discoverOrders(command.discoveryRequestId(), criteria,
                properties.getIncidentScopeMaxCandidates(), "", command.traceId());
    }

    private IncidentScopeCriteria criteria(IncidentScopeDiscoveryCommand command) {
        ResolvedIncidentTimeRange range = null;
        if (command.timeExpression() != null && !command.timeExpression().isBlank()) {
            range = timeRangeResolver.resolve(command.timeExpression(), command.userTimezone());
        }
        String timezone;
        boolean defaultTimezoneUsed;
        if (range != null) {
            timezone = range.timezone();
            defaultTimezoneUsed = range.defaultTimezoneUsed();
        } else {
            try {
                timezone = ZoneId.of(command.userTimezone()).getId();
                defaultTimezoneUsed = false;
            } catch (RuntimeException exception) {
                timezone = properties.getIncidentScopeDefaultTimezone();
                defaultTimezoneUsed = true;
            }
        }
        return new IncidentScopeCriteria(
                command.timeExpression(), range == null ? null : range.startTime(),
                range == null ? null : range.endTime(), timezone, defaultTimezoneUsed,
                command.anomalyTypes().stream().distinct().sorted(Comparator.comparing(Enum::name)).toList(),
                normalize(command.orderNos()), normalize(command.deductNos()), normalize(command.deadLetterIds()));
    }

    private List<String> normalize(List<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            values.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).forEach(result::add);
        }
        return List.copyOf(result);
    }

    private String instanceId() {
        String configured = properties.getIncidentScopeInternalToken();
        return "scope-discovery-" + Integer.toHexString(System.identityHashCode(this))
                + (configured.isBlank() ? "-unconfigured" : "");
    }

    private String safeFailureCode(RuntimeException exception) {
        return exception instanceof IncidentScopeNarrowingRequiredException
                ? "SCOPE_TOO_BROAD" : "SCOPE_DISCOVERY_FAILED";
    }

    private void require(AuthenticatedPrincipal principal, IncidentScopeDiscoveryCommand command) {
        if (principal == null || command == null) {
            throw new IllegalArgumentException("principal and discovery command are required");
        }
        if (command.discoveryRequestId() == null || command.discoveryRequestId().isBlank()
                || command.workItemId() == null || command.workItemId().isBlank()
                || command.sourceInputId() == null || command.sourceInputId().isBlank()) {
            throw new IllegalArgumentException("discoveryRequestId, workItemId and sourceInputId are required");
        }
    }
}
