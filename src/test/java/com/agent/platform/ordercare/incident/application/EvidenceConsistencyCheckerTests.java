package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.ConflictSeverity;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceConflictType;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvidenceConsistencyCheckerTests {

    @Test
    void comparesExplicitCrossSubtypeMetricsWithoutMixingPhysicalOrQueueCounts() {
        Instant observed = Instant.now();
        IncidentSnapshot snapshot = snapshot(observed);
        List<String> oneHundred = ids(100);
        List<String> ninetyThree = oneHundred.subList(0, 93);
        EvidenceRecord orders = evidence("ev-order", EvidenceSubtype.ORDER_STATUS_SET, observed, Map.of(
                "scopeHash", "scope-1",
                "truncated", false,
                "terminalDistinctRequestIdCount", 100,
                "terminalRequestIds", oneHundred));
        EvidenceRecord inventory = evidence("ev-inventory", EvidenceSubtype.INVENTORY_DEDUCT_SET, observed, Map.of(
                "scopeHash", "scope-1",
                "truncated", false,
                "unreleasedDistinctRequestIdCount", 93,
                "unreleasedRequestIds", ninetyThree,
                "items", List.of()));
        EvidenceRecord deadLetters = evidence("ev-dead", EvidenceSubtype.DEAD_LETTER_SET, observed, Map.of(
                "scopeHash", "scope-1",
                "truncated", false,
                "recordCount", 126,
                "distinctRequestIdCount", 100,
                "requestIds", oneHundred));
        EvidenceRecord queue = evidence("ev-queue", EvidenceSubtype.QUEUE_RUNTIME_STATUS, observed, Map.of(
                "scopeHash", "scope-1",
                "truncated", false,
                "messagesReady", 126,
                "consumerCount", 1));

        var result = new EvidenceConsistencyChecker().check(
                snapshot,
                List.of(orders, inventory, deadLetters, queue),
                Set.of(EvidenceSubtype.ORDER_STATUS_SET, EvidenceSubtype.INVENTORY_DEDUCT_SET,
                        EvidenceSubtype.DEAD_LETTER_SET, EvidenceSubtype.QUEUE_RUNTIME_STATUS));

        var countMismatches = result.conflicts().stream()
                .filter(conflict -> conflict.conflictType() == EvidenceConflictType.COUNT_MISMATCH)
                .toList();
        assertEquals(1, countMismatches.size());
        assertEquals("terminal-orders-vs-unreleased-deducts", countMismatches.get(0).metricKey());
        var setDifference = result.conflicts().stream()
                .filter(conflict -> conflict.conflictType() == EvidenceConflictType.SET_DIFFERENCE)
                .findFirst().orElseThrow();
        assertEquals(7, setDifference.details().get("rightOnlyCount"));
        assertFalse(result.conflicts().stream()
                .anyMatch(conflict -> conflict.metricKey().contains("QUEUE_RUNTIME")));
    }

    @Test
    void refusesComparisonWhenScopeOrObservationWindowDiffers() {
        Instant now = Instant.now();
        EvidenceRecord orders = evidence("ev-order", EvidenceSubtype.ORDER_STATUS_SET, now, Map.of(
                "scopeHash", "scope-1", "truncated", false,
                "terminalDistinctRequestIdCount", 10, "terminalRequestIds", ids(10)));
        EvidenceRecord inventory = evidence("ev-inventory", EvidenceSubtype.INVENTORY_DEDUCT_SET,
                now.plusSeconds(600), Map.of(
                        "scopeHash", "scope-2", "truncated", false,
                        "unreleasedDistinctRequestIdCount", 7, "unreleasedRequestIds", ids(7),
                        "items", List.of()));

        var result = new EvidenceConsistencyChecker().check(
                snapshot(now), List.of(orders, inventory), Set.of());

        assertFalse(result.notComparable().isEmpty());
        assertFalse(result.conflicts().stream()
                .anyMatch(conflict -> conflict.conflictType() == EvidenceConflictType.COUNT_MISMATCH));
    }

    private IncidentSnapshot snapshot(Instant now) {
        return new IncidentSnapshot(
                "snap-1", "inc-1", "alert-1", "STOCK_RELEASE_DLQ_BACKLOG", "tenant",
                new IncidentSnapshot.IncidentOrderScope(ids(100)),
                new IncidentSnapshot.IncidentBusinessScope(List.of("floworder.order.state.dlq")),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now.minusSeconds(5), now, now.plusSeconds(120), "scope-1");
    }

    private EvidenceRecord evidence(String id,
                                    EvidenceSubtype subtype,
                                    Instant observedAt,
                                    Map<String, Object> facts) {
        return new EvidenceRecord(
                id, "inc-1", "task-1", "run-1", EvidenceClass.FACT, subtype,
                "floworder", "source:" + id, Map.of(), observedAt,
                new LinkedHashMap<>(facts), "hash-" + id, EvidenceStatus.ACCEPTED,
                "", "idem-" + id, observedAt);
    }

    private List<String> ids(int count) {
        List<String> values = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            values.add("REQ-%03d".formatted(index));
        }
        return List.copyOf(values);
    }
}
