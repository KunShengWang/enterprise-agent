package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.BusinessKeyType;
import com.agent.platform.ordercare.incident.model.ComparisonOperator;
import com.agent.platform.ordercare.incident.model.ConflictSeverity;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceComparisonRule;
import com.agent.platform.ordercare.incident.model.EvidenceConflict;
import com.agent.platform.ordercare.incident.model.EvidenceConflictType;
import com.agent.platform.ordercare.incident.model.EvidenceConsistencyResult;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Component
public class EvidenceConsistencyChecker {

    private static final Duration DEFAULT_FRESHNESS = Duration.ofMinutes(10);

    private final List<EvidenceComparisonRule> rules = List.of(
            new EvidenceComparisonRule(
                    "terminal-orders-vs-unreleased-deducts",
                    EvidenceSubtype.ORDER_STATUS_SET, "terminalDistinctRequestIdCount",
                    EvidenceSubtype.INVENTORY_DEDUCT_SET, "unreleasedDistinctRequestIdCount",
                    BusinessKeyType.REQUEST_ID, Duration.ofMinutes(2), ComparisonOperator.EQUAL_COUNT),
            new EvidenceComparisonRule(
                    "terminal-orders-vs-dead-letters",
                    EvidenceSubtype.ORDER_STATUS_SET, "terminalRequestIds",
                    EvidenceSubtype.DEAD_LETTER_SET, "requestIds",
                    BusinessKeyType.REQUEST_ID, Duration.ofMinutes(2), ComparisonOperator.EQUAL_SET),
            new EvidenceComparisonRule(
                    "unreleased-deducts-vs-dead-letters",
                    EvidenceSubtype.INVENTORY_DEDUCT_SET, "unreleasedRequestIds",
                    EvidenceSubtype.DEAD_LETTER_SET, "requestIds",
                    BusinessKeyType.REQUEST_ID, Duration.ofMinutes(2), ComparisonOperator.EQUAL_SET)
    );

    public EvidenceConsistencyResult check(IncidentSnapshot snapshot,
                                           List<EvidenceRecord> evidence,
                                           Set<EvidenceSubtype> requiredSubtypes) {
        List<EvidenceRecord> facts = evidence == null ? List.of() : evidence.stream()
                .filter(item -> item.evidenceClass() == EvidenceClass.FACT)
                .filter(item -> item.status() == EvidenceStatus.ACCEPTED)
                .sorted(Comparator.comparing(EvidenceRecord::observedAt)
                        .thenComparing(EvidenceRecord::evidenceId))
                .toList();
        List<EvidenceConflict> conflicts = new ArrayList<>();
        List<EvidenceConsistencyResult.NotComparableEvidence> notComparable = new ArrayList<>();
        Map<EvidenceSubtype, EvidenceRecord> latest = latestBySubtype(facts);

        for (EvidenceSubtype required : requiredSubtypes == null ? Set.<EvidenceSubtype>of() : requiredSubtypes) {
            if (!latest.containsKey(required)) {
                conflicts.add(conflict(
                        EvidenceConflictType.MISSING_EVIDENCE,
                        required.name(),
                        ConflictSeverity.HIGH,
                        List.of(),
                        Map.of("missingSubtype", required.name())));
            }
        }
        for (EvidenceRecord fact : facts) {
            String scopeHash = string(fact.facts().get("scopeHash"));
            if (!scopeHash.isBlank() && snapshot != null && !scopeHash.equals(snapshot.scopeHash())) {
                conflicts.add(conflict(EvidenceConflictType.SCOPE_MISMATCH, fact.evidenceSubtype().name(),
                        ConflictSeverity.HIGH, List.of(fact.evidenceId()),
                        Map.of("expectedScopeHash", snapshot.scopeHash(), "actualScopeHash", scopeHash)));
            }
            if (Boolean.TRUE.equals(fact.facts().get("truncated"))) {
                conflicts.add(conflict(EvidenceConflictType.TRUNCATED_RESULT, fact.evidenceSubtype().name(),
                        ConflictSeverity.HIGH, List.of(fact.evidenceId()), Map.of("truncated", true)));
            }
            Instant freshnessReference = snapshot == null ? Instant.now() : snapshot.investigationStartedAt();
            if (freshnessReference != null
                    && fact.observedAt() != null
                    && Duration.between(fact.observedAt(), freshnessReference).compareTo(DEFAULT_FRESHNESS) > 0) {
                conflicts.add(conflict(EvidenceConflictType.STALE_DATA, fact.evidenceSubtype().name(),
                        ConflictSeverity.MEDIUM, List.of(fact.evidenceId()),
                        Map.of("observedAt", fact.observedAt().toString())));
            }
            appendInvariantConflicts(fact, conflicts);
        }

        for (EvidenceComparisonRule rule : rules) {
            EvidenceRecord left = latest.get(rule.leftSubtype());
            EvidenceRecord right = latest.get(rule.rightSubtype());
            if (left == null || right == null) {
                continue;
            }
            String incomparable = notComparableReason(snapshot, rule, left, right);
            if (incomparable != null) {
                notComparable.add(new EvidenceConsistencyResult.NotComparableEvidence(
                        rule.ruleId(), List.of(left.evidenceId(), right.evidenceId()), incomparable));
                if (incomparable.startsWith("observedAt skew")) {
                    conflicts.add(conflict(EvidenceConflictType.TIME_SKEW, rule.ruleId(),
                            ConflictSeverity.MEDIUM, List.of(left.evidenceId(), right.evidenceId()),
                            Map.of("reason", incomparable)));
                }
                continue;
            }
            if (rule.operator() == ComparisonOperator.EQUAL_COUNT) {
                long leftCount = number(left.facts().get(rule.leftMetric()));
                long rightCount = number(right.facts().get(rule.rightMetric()));
                if (leftCount != rightCount) {
                    conflicts.add(conflict(EvidenceConflictType.COUNT_MISMATCH, rule.ruleId(),
                            ConflictSeverity.HIGH, List.of(left.evidenceId(), right.evidenceId()),
                            Map.of("leftMetric", rule.leftMetric(), "leftCount", leftCount,
                                    "rightMetric", rule.rightMetric(), "rightCount", rightCount,
                                    "businessKeyType", rule.businessKeyType().name())));
                }
            }
            else {
                TreeSet<String> leftSet = strings(left.facts().get(rule.leftMetric()));
                TreeSet<String> rightSet = strings(right.facts().get(rule.rightMetric()));
                TreeSet<String> leftOnly = new TreeSet<>(leftSet);
                leftOnly.removeAll(rightSet);
                TreeSet<String> rightOnly = new TreeSet<>(rightSet);
                rightOnly.removeAll(leftSet);
                if (!leftOnly.isEmpty() || !rightOnly.isEmpty()) {
                    conflicts.add(conflict(EvidenceConflictType.SET_DIFFERENCE, rule.ruleId(),
                            ConflictSeverity.HIGH, List.of(left.evidenceId(), right.evidenceId()),
                            Map.of(
                                    "leftOnlyCount", leftOnly.size(),
                                    "rightOnlyCount", rightOnly.size(),
                                    "leftOnly", bounded(leftOnly),
                                    "rightOnly", bounded(rightOnly),
                                    "businessKeyType", rule.businessKeyType().name())));
                }
            }
        }
        conflicts.sort(Comparator.comparing((EvidenceConflict value) -> value.severity().ordinal()).reversed()
                .thenComparing(value -> value.conflictType().name())
                .thenComparing(EvidenceConflict::metricKey));
        return new EvidenceConsistencyResult(List.copyOf(conflicts), List.copyOf(notComparable));
    }

    public List<EvidenceComparisonRule> rules() {
        return rules;
    }

    private Map<EvidenceSubtype, EvidenceRecord> latestBySubtype(List<EvidenceRecord> evidence) {
        Map<EvidenceSubtype, EvidenceRecord> latest = new HashMap<>();
        for (EvidenceRecord item : evidence) {
            latest.put(item.evidenceSubtype(), item);
        }
        return latest;
    }

    private String notComparableReason(IncidentSnapshot snapshot,
                                       EvidenceComparisonRule rule,
                                       EvidenceRecord left,
                                       EvidenceRecord right) {
        if (!left.facts().containsKey(rule.leftMetric()) || !right.facts().containsKey(rule.rightMetric())) {
            return "required metric is missing";
        }
        String leftScope = string(left.facts().get("scopeHash"));
        String rightScope = string(right.facts().get("scopeHash"));
        if (leftScope.isBlank() || rightScope.isBlank() || !leftScope.equals(rightScope)
                || (snapshot != null && !snapshot.scopeHash().equals(leftScope))) {
            return "scopeHash is not equal";
        }
        if (Boolean.TRUE.equals(left.facts().get("truncated"))
                || Boolean.TRUE.equals(right.facts().get("truncated"))) {
            return "one side is truncated";
        }
        Duration skew = Duration.between(left.observedAt(), right.observedAt()).abs();
        if (skew.compareTo(rule.maxObservedAtSkew()) > 0) {
            return "observedAt skew exceeds " + rule.maxObservedAtSkew();
        }
        return null;
    }

    private void appendInvariantConflicts(EvidenceRecord fact, List<EvidenceConflict> conflicts) {
        if (fact.evidenceSubtype() == EvidenceSubtype.INVENTORY_INVARIANT) {
            TreeSet<String> violations = strings(fact.facts().get("invariantViolationStockItemIds"));
            if (!violations.isEmpty()) {
                conflicts.add(conflict(EvidenceConflictType.INVARIANT_VIOLATION,
                        "inventoryInvariant", ConflictSeverity.HIGH, List.of(fact.evidenceId()),
                        Map.of("stockItemIds", bounded(violations), "count", violations.size())));
            }
        }
        if (fact.evidenceSubtype() == EvidenceSubtype.INVENTORY_DEDUCT_SET) {
            Object rawItems = fact.facts().get("items");
            if (rawItems instanceof Collection<?> items) {
                Map<String, Set<String>> deductsByRequest = new HashMap<>();
                for (Object raw : items) {
                    if (!(raw instanceof Map<?, ?> item)) {
                        continue;
                    }
                    String requestId = string(item.get("requestId"));
                    String deductNo = string(item.get("deductNo"));
                    if (!requestId.isBlank() && !deductNo.isBlank()) {
                        deductsByRequest.computeIfAbsent(requestId, ignored -> new HashSet<>()).add(deductNo);
                    }
                }
                List<String> ambiguous = deductsByRequest.entrySet().stream()
                        .filter(entry -> entry.getValue().size() > 1)
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
                if (!ambiguous.isEmpty()) {
                    conflicts.add(conflict(EvidenceConflictType.DUPLICATE_OR_AMBIGUOUS_MAPPING,
                            "requestIdToDeductNo", ConflictSeverity.HIGH, List.of(fact.evidenceId()),
                            Map.of("requestIds", ambiguous, "count", ambiguous.size())));
                }
            }
        }
    }

    private EvidenceConflict conflict(EvidenceConflictType type,
                                      String metricKey,
                                      ConflictSeverity severity,
                                      List<String> evidenceIds,
                                      Map<String, Object> details) {
        return new EvidenceConflict(
                UUID.randomUUID().toString(), type, metricKey, severity, evidenceIds,
                new LinkedHashMap<>(details), "OPEN");
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        }
        catch (RuntimeException ignored) {
            return -1;
        }
    }

    private TreeSet<String> strings(Object value) {
        TreeSet<String> values = new TreeSet<>();
        if (value instanceof Collection<?> collection) {
            collection.stream().map(this::string).filter(item -> !item.isBlank()).forEach(values::add);
        }
        return values;
    }

    private List<String> bounded(TreeSet<String> values) {
        return values.stream().limit(100).toList();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
