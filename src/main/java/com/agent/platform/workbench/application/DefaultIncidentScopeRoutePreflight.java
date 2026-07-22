package com.agent.platform.workbench.application;

import com.agent.platform.ordercare.incident.scope.application.IncidentScopeDiscoveryCommand;
import com.agent.platform.ordercare.incident.scope.application.IncidentScopeDiscoveryCoordinator;
import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeAnomalyType;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCandidate;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshot;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshotStatus;
import com.agent.platform.ordercare.incident.scope.persistence.IncidentScopeDiscoveryStore;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.model.IdentifierSource;
import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.model.RouteValidationResult;
import com.agent.platform.workbench.model.ValidatedExecutionInput;
import com.agent.platform.workbench.model.ValidatedIdentifier;
import com.agent.platform.workbench.model.WorkEventDraft;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DefaultIncidentScopeRoutePreflight implements IncidentScopeRoutePreflight {

    private static final Pattern RECENT_HOURS = Pattern.compile("(?:最近|过去)\\s*\\d{1,2}\\s*小时");
    private static final Pattern ISO_RANGE = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T[^\\s/]+\\s*/\\s*\\d{4}-\\d{2}-\\d{2}T[^\\s/]+");

    private final IncidentScopeDiscoveryCoordinator coordinator;
    private final IncidentScopeDiscoveryStore scopeStore;
    private final WorkbenchStore workbenchStore;
    private final ObjectMapper objectMapper;
    private final OrderCareProperties properties;

    public DefaultIncidentScopeRoutePreflight(IncidentScopeDiscoveryCoordinator coordinator,
                                              IncidentScopeDiscoveryStore scopeStore,
                                              WorkbenchStore workbenchStore,
                                              ObjectMapper objectMapper,
                                              OrderCareProperties properties) {
        this.coordinator = coordinator;
        this.scopeStore = scopeStore;
        this.workbenchStore = workbenchStore;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<RouteValidationResult> resolve(AuthenticatedPrincipal principal,
                                                   AgentWorkItem workItem,
                                                   ExecutionDecision decision,
                                                   ResolvedRouteContext context) {
        if (decision == null || !"INCIDENT_INVESTIGATION".equals(decision.targetId())) {
            return Optional.empty();
        }
        if (!values(decision.extractedInputs().get("requestIds")).isEmpty()) {
            return Optional.empty();
        }

        String goal = workItem.originalGoal();
        String timeExpression = timeExpression(goal);
        List<IncidentScopeAnomalyType> anomalies = anomalyTypes(goal);
        List<String> orderNos = explicitValues(decision, goal, "orderNo", "orderNos");
        List<String> deductNos = explicitValues(decision, goal, "deductNo", "deductNos");
        List<String> deadLetterIds = explicitValues(decision, goal, "deadLetterId", "deadLetterIds");
        boolean hasAnchor = !timeExpression.isBlank() || !orderNos.isEmpty()
                || !deductNos.isEmpty() || !deadLetterIds.isEmpty();
        if (!hasAnchor || anomalies.isEmpty()) {
            return Optional.of(new RouteValidationResult(
                    RouteDisposition.REQUIRE_CLARIFICATION, null,
                    List.of(clarificationReason(hasAnchor, anomalies)), ""));
        }

        String discoveryRequestId = "scope:" + workItem.routingRequestId();
        append(workItem, principal, discoveryRequestId, WorkEventType.SCOPE_DISCOVERY_STARTED,
                "SCOPE_DISCOVERY_STARTED", "Incident scope discovery started",
                Map.of("resolutionAction", IncidentScopeResolutionAction.DISCOVER_SCOPE.name(),
                        "timeExpression", timeExpression,
                        "anomalyTypes", anomalies.stream().map(Enum::name).toList()));
        try {
            IncidentScopeSnapshot snapshot = coordinator.discover(principal,
                    new IncidentScopeDiscoveryCommand(
                            discoveryRequestId, workItem.conversationId(), workItem.workItemId(),
                            workItem.sourceInputId(), timeExpression, properties.getIncidentScopeDefaultTimezone(), anomalies,
                            orderNos, deductNos, deadLetterIds, workItem.workItemId()));
            if (snapshot.candidateCount() == 0) {
                append(workItem, principal, discoveryRequestId, WorkEventType.SCOPE_DISCOVERY_COMPLETED,
                        "SCOPE_DISCOVERY_COMPLETED", "Incident scope discovery completed without candidates",
                        Map.of("snapshotId", snapshot.snapshotId(), "candidateCount", 0));
                return Optional.of(new RouteValidationResult(
                        RouteDisposition.REQUIRE_CLARIFICATION, null,
                        List.of("当前条件未发现候选，请缩小或调整时间与异常现象"), ""));
            }
            IncidentScopeSnapshot waiting = snapshot.status() == IncidentScopeSnapshotStatus.WAITING_CONFIRMATION
                    ? snapshot
                    : scopeStore.markWaitingConfirmation(principal, snapshot.snapshotId(), snapshot.version());
            boolean hasResolvedRequest = waiting.candidates().stream()
                    .anyMatch(candidate -> candidate.requestId() != null && !candidate.requestId().isBlank());
            if (!hasResolvedRequest) {
                appendDiscoveryEvents(workItem, principal, waiting);
                return Optional.of(new RouteValidationResult(
                        RouteDisposition.REQUIRE_CLARIFICATION, null,
                        List.of("已找到相关事实，但无法可靠关联 requestId，请补充时间或订单号"), ""));
            }
            appendDiscoveryEvents(workItem, principal, waiting);
            return Optional.of(validated(waiting));
        } catch (RuntimeException exception) {
            append(workItem, principal, discoveryRequestId, WorkEventType.SCOPE_DISCOVERY_FAILED,
                    "SCOPE_DISCOVERY_FAILED", "Incident scope discovery failed",
                    Map.of("safeErrorCode", safeFailureCode(exception)));
            throw exception;
        }
    }

    private RouteValidationResult validated(IncidentScopeSnapshot snapshot) {
        TreeSet<String> requestIds = new TreeSet<>();
        TreeSet<String> queueNames = new TreeSet<>();
        TreeSet<String> orderNos = new TreeSet<>();
        TreeSet<String> deductNos = new TreeSet<>();
        TreeSet<String> deadLetterIds = new TreeSet<>();
        for (IncidentScopeCandidate candidate : snapshot.candidates()) {
            add(requestIds, candidate.requestId());
            add(orderNos, candidate.orderNo());
            add(deductNos, candidate.deductNo());
            queueNames.addAll(candidate.queueNames());
            deadLetterIds.addAll(candidate.deadLetterIds());
        }
        Map<String, Object> payload = new TreeMap<>();
        payload.put("requestIds", List.copyOf(requestIds));
        if (!queueNames.isEmpty()) payload.put("queueNames", List.copyOf(queueNames));
        payload.put("scopeSnapshotId", snapshot.snapshotId());
        payload.put("scopeSnapshotVersion", snapshot.version());
        payload.put("candidateFingerprint", snapshot.candidateFingerprint());
        payload.put("criteriaDigest", snapshot.criteriaDigest());
        payload.put("candidateCount", snapshot.candidateCount());
        payload.put("truncated", snapshot.truncated());
        payload.put("timeStart", text(snapshot.criteria().startTime()));
        payload.put("timeEnd", text(snapshot.criteria().endTime()));
        payload.put("timezone", snapshot.criteria().timezone());
        payload.put("defaultTimezoneUsed", snapshot.criteria().defaultTimezoneUsed());
        payload.put("anomalyTypes", snapshot.criteria().anomalyTypes().stream().map(Enum::name).toList());
        payload.put("orderNos", List.copyOf(orderNos));
        payload.put("deductNos", List.copyOf(deductNos));
        payload.put("deadLetterIds", List.copyOf(deadLetterIds));
        payload.put("sourceHealth", snapshot.sourceHealth());
        payload.put("scopeCandidates", snapshot.candidates().stream().map(this::publicCandidate).toList());

        Map<String, ValidatedIdentifier> identifiers = new LinkedHashMap<>();
        putIdentifiers(identifiers, "requestId", requestIds);
        putIdentifiers(identifiers, "queueName", queueNames);
        putIdentifiers(identifiers, "orderNo", orderNos);
        putIdentifiers(identifiers, "deductNo", deductNos);
        putIdentifiers(identifiers, "deadLetterId", deadLetterIds);
        String digest = sha256("INCIDENT_INVESTIGATION|" + objectMapper.writeValueAsString(payload));
        return new RouteValidationResult(RouteDisposition.REQUIRE_CONFIRMATION,
                new ValidatedExecutionInput("INCIDENT_INVESTIGATION", identifiers, payload, digest),
                List.of("scope discovery produced an immutable preview requiring explicit confirmation"), "");
    }

    private void appendDiscoveryEvents(AgentWorkItem workItem,
                                       AuthenticatedPrincipal principal,
                                       IncidentScopeSnapshot snapshot) {
        int deadLetters = snapshot.candidates().stream().mapToInt(value -> value.deadLetterIds().size()).sum();
        int queues = (int) snapshot.candidates().stream().flatMap(value -> value.queueNames().stream()).distinct().count();
        Map<String, Object> common = Map.of(
                "snapshotId", snapshot.snapshotId(),
                "snapshotVersion", snapshot.version(),
                "candidateFingerprint", snapshot.candidateFingerprint());
        append(workItem, principal, snapshot.discoveryRequestId(), WorkEventType.ORDER_CANDIDATES_DISCOVERED,
                "ORDER_CANDIDATES_DISCOVERED", "Order candidates discovered",
                merge(common, Map.of("candidateCount", snapshot.candidateCount())));
        append(workItem, principal, snapshot.discoveryRequestId(), WorkEventType.RESOURCE_ENRICHMENT_COMPLETED,
                "RESOURCE_ENRICHMENT_COMPLETED", "Resource facts enriched",
                merge(common, Map.of("sourceHealth", snapshot.sourceHealth())));
        append(workItem, principal, snapshot.discoveryRequestId(), WorkEventType.DEAD_LETTERS_RESOLVED,
                "DEAD_LETTERS_RESOLVED", "Persisted dead letters resolved",
                merge(common, Map.of("deadLetterCount", deadLetters)));
        append(workItem, principal, snapshot.discoveryRequestId(), WorkEventType.QUEUES_RESOLVED,
                "QUEUES_RESOLVED", "Authoritative queues resolved",
                merge(common, Map.of("queueCount", queues)));
        append(workItem, principal, snapshot.discoveryRequestId(), WorkEventType.SCOPE_DISCOVERY_COMPLETED,
                "SCOPE_DISCOVERY_COMPLETED", "Candidate incident scope created",
                merge(common, Map.of("candidateCount", snapshot.candidateCount(), "truncated", snapshot.truncated())));
        append(workItem, principal, snapshot.discoveryRequestId(), WorkEventType.SCOPE_CONFIRMATION_REQUIRED,
                "SCOPE_CONFIRMATION_REQUIRED", "Candidate incident scope requires confirmation", common);
    }

    private void append(AgentWorkItem workItem,
                        AuthenticatedPrincipal principal,
                        String discoveryRequestId,
                        WorkEventType type,
                        String phase,
                        String summary,
                        Map<String, Object> payload) {
        workbenchStore.appendLocalEvent(principal, workItem.workItemId(), new WorkEventDraft(
                discoveryRequestId + ":" + type.name(), type, phase, summary, payload,
                workItem.routingRequestId()));
    }

    private String timeExpression(String goal) {
        if (goal == null) return "";
        if (goal.contains("前天")) return "前天";
        if (goal.contains("昨晚")) return "昨晚";
        if (goal.contains("今天")) return "今天";
        Matcher recent = RECENT_HOURS.matcher(goal);
        if (recent.find()) return recent.group();
        Matcher explicit = ISO_RANGE.matcher(goal);
        return explicit.find() ? explicit.group() : "";
    }

    private List<IncidentScopeAnomalyType> anomalyTypes(String goal) {
        String value = goal == null ? "" : goal;
        List<IncidentScopeAnomalyType> result = new ArrayList<>();
        if ((value.contains("超时") || value.contains("未完成"))
                && (value.contains("库存未释放") || value.contains("库存没有释放") || value.contains("未释放库存"))) {
            result.add(IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED);
        }
        if ((value.contains("取消") || value.contains("已关闭"))
                && (value.contains("库存未释放") || value.contains("库存没有释放") || value.contains("未释放库存"))) {
            result.add(IncidentScopeAnomalyType.ORDER_CANCELLED_INVENTORY_UNRELEASED);
        }
        if (value.contains("死信") || value.toLowerCase().contains("dead letter")) {
            result.add(IncidentScopeAnomalyType.DEAD_LETTER_PENDING);
        }
        if (value.contains("状态不一致") || value.contains("库存异常") || value.contains("库存释放异常")) {
            result.add(IncidentScopeAnomalyType.ORDER_INVENTORY_STATE_MISMATCH);
        }
        return result.stream().distinct().toList();
    }

    private List<String> explicitValues(ExecutionDecision decision, String goal, String singular, String plural) {
        List<String> result = new ArrayList<>();
        result.addAll(values(decision.extractedInputs().get(singular)));
        result.addAll(values(decision.extractedInputs().get(plural)));
        return result.stream().filter(value -> goal != null && goal.contains(value)).distinct().sorted().toList();
    }

    private List<String> values(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim)
                    .filter(value -> !value.isBlank()).distinct().toList();
        }
        String value = raw == null ? "" : String.valueOf(raw).trim();
        return value.isBlank() ? List.of() : List.of(value);
    }

    private void putIdentifiers(Map<String, ValidatedIdentifier> target,
                                String type,
                                TreeSet<String> values) {
        int index = 0;
        for (String value : values) {
            target.put(type + "[" + index++ + "]", new ValidatedIdentifier(
                    type, value, IdentifierSource.SERVER_RESOLVED_FROM_SCOPE_DISCOVERY));
        }
    }

    private Map<String, Object> publicCandidate(IncidentScopeCandidate candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", candidate.requestId());
        result.put("orderNo", candidate.orderNo());
        result.put("deductNo", candidate.deductNo());
        result.put("deadLetterIds", candidate.deadLetterIds());
        result.put("queueNames", candidate.queueNames());
        result.put("anomalyTypes", candidate.anomalyTypes().stream().map(Enum::name).toList());
        result.put("inclusionReasons", candidate.inclusionReasons());
        result.put("relationQuality", candidate.relationQuality().name());
        result.put("completeness", candidate.completeness());
        result.put("provenance", candidate.provenance());
        return Map.copyOf(result);
    }

    private String clarificationReason(boolean hasAnchor, List<IncidentScopeAnomalyType> anomalies) {
        if (!hasAnchor && anomalies.isEmpty()) return "请补充大致时间、订单号或明确的业务异常现象";
        if (!hasAnchor) return "请补充大致时间或订单号，系统会自动发现内部标识";
        return "请明确订单超时、取消未释放、死信积压或状态不一致等业务现象";
    }

    private String safeFailureCode(RuntimeException exception) {
        String name = exception.getClass().getSimpleName().toUpperCase();
        return name.contains("NARROW") ? "SCOPE_TOO_BROAD" : "SCOPE_DISCOVERY_FAILED";
    }

    private Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> result = new LinkedHashMap<>(left);
        result.putAll(right);
        return Map.copyOf(result);
    }

    private void add(TreeSet<String> target, String value) {
        if (value != null && !value.isBlank()) target.add(value.trim());
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to digest discovered execution input", exception);
        }
    }
}
